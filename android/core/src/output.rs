//! The sound's way out, with crossfade.
//!
//! librespot writes each song into the main lane, which blocks when it holds
//! more than [`MAIN_CAP`], so the player is still paced in real time. When a
//! song has the crossfade length left (and the next one is loaded), the rest
//! of it goes into the tail lane instead, without blocking: librespot races
//! through it and starts the next song, which lands in the main lane. One
//! thread plays both lanes at speaker pace, mixing them on equal-power
//! curves, then runs effects, limiter, visualizer tap and volume once on the
//! mixed sound and writes it to Kotlin's `AudioOut`.
//!
//! Skips, picking a song and seeks drop what is queued and ask `AudioOut` for
//! a short fade instead of a hard cut (`smooth skips`).

use std::collections::VecDeque;
use std::f64::consts::FRAC_PI_2;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, LazyLock, Mutex};

use jni::JavaVM;
use jni::objects::{GlobalRef, JValue};

use crate::{effects, limiter, vis, volume};

const RATE: usize = 44_100;
/// About 0.3 s of stereo sound queued ahead of the mixer.
const MAIN_CAP: usize = RATE * 2 * 3 / 10;
/// Frames the mixer hands on at a time.
const CHUNK: usize = 1024;

/// Crossfade length in ms; 0 is plain gapless.
pub static CROSSFADE_MS: AtomicU32 = AtomicU32::new(0);
pub static SMOOTH_SKIPS: AtomicBool = AtomicBool::new(true);
/// Keep songs of one album back to back seamless, for live and mixed albums.
pub static ALBUMS_GAPLESS: AtomicBool = AtomicBool::new(true);
/// How long the app should keep showing the old song after the new one
/// starts: half the blend, so the title changes when the new song is the
/// louder. Taken (and reset) by the event loop.
pub static HOLD_MS: AtomicU64 = AtomicU64::new(0);

#[derive(Default)]
struct Song {
    uri: String,
    album: String,
    duration_ms: u32,
}

#[derive(Default)]
struct Lanes {
    /// Interleaved stereo, as librespot writes it.
    main: VecDeque<f64>,
    tail: VecDeque<f64>,
    /// The rest of the current song goes to the tail.
    capturing: bool,
    /// The blend's length in frames, fixed once the next song starts.
    fade_frames: usize,
    tail_played: usize,
    paused: bool,
    /// A skip is waiting for the mixer: Some(fade).
    flush: Option<bool>,
    playing_song: Song,
    next: Option<Song>,
    last_position: Option<u32>,
}

impl Lanes {
    /// Drops everything queued and has the mixer cut (or fade) what is audible.
    fn skip(&mut self) {
        self.main.clear();
        self.tail.clear();
        self.capturing = false;
        self.fade_frames = 0;
        self.tail_played = 0;
        self.flush = Some(SMOOTH_SKIPS.load(Ordering::Relaxed));
    }

    /// How long the blend into the next song should be, in ms, or 0 for none.
    fn blend_ms(&self) -> u32 {
        let wanted = CROSSFADE_MS.load(Ordering::Relaxed);
        let Some(next) = &self.next else { return 0 };
        let now = &self.playing_song;
        let same_album = !now.album.is_empty() && now.album == next.album;
        if wanted == 0 || next.uri == now.uri || (same_album && ALBUMS_GAPLESS.load(Ordering::Relaxed)) {
            return 0;
        }
        // A short song gets a blend of a third of the shorter one.
        wanted.min(now.duration_ms / 3).min(next.duration_ms / 3)
    }
}

pub struct Output {
    lanes: Mutex<Lanes>,
    changed: Condvar,
    /// Where the sound goes; replaced on each connect.
    target: Mutex<Option<(JavaVM, GlobalRef)>>,
}

pub static OUTPUT: LazyLock<Arc<Output>> = LazyLock::new(|| {
    let output = Arc::new(Output {
        lanes: Mutex::new(Lanes::default()),
        changed: Condvar::new(),
        target: Mutex::new(None),
    });
    let mixer = output.clone();
    std::thread::Builder::new()
        .name("spotifast-out".into())
        .spawn(move || mixer.run())
        .expect("output thread");
    output
});

impl Output {
    fn lanes(&self) -> std::sync::MutexGuard<'_, Lanes> {
        self.lanes.lock().unwrap_or_else(|p| p.into_inner())
    }

    pub fn set_target(&self, vm: JavaVM, out: GlobalRef) {
        *self.target.lock().unwrap_or_else(|p| p.into_inner()) = Some((vm, out));
    }

    // ---- The player's side ----

    pub fn write(&self, samples: &[f64]) {
        let mut lanes = self.lanes();
        if lanes.capturing {
            lanes.tail.extend(samples);
        } else {
            while lanes.main.len() >= MAIN_CAP && lanes.flush.is_none() && !lanes.paused {
                lanes = self.changed.wait(lanes).unwrap_or_else(|p| p.into_inner());
            }
            lanes.main.extend(samples);
        }
        self.changed.notify_all();
    }

    pub fn position(&self, position_ms: u32, duration_ms: u32) {
        let mut lanes = self.lanes();
        // A jump that playback alone can't make is a seek.
        if let Some(last) = lanes.last_position
            && (position_ms + 1000 < last || position_ms > last + 1500)
        {
            lanes.skip();
            self.changed.notify_all();
        }
        lanes.last_position = Some(position_ms);
        if lanes.capturing || lanes.fade_frames > 0 {
            return;
        }
        let blend = lanes.blend_ms();
        if blend > 0 && duration_ms.saturating_sub(position_ms) <= blend {
            // What the mixer has not played of this song joins the tail, so
            // the tail is the song's last stretch in order.
            let queued: Vec<f64> = lanes.main.drain(..).collect();
            lanes.tail.extend(queued);
            lanes.capturing = true;
            lanes.fade_frames = blend as usize * RATE / 1000;
            lanes.tail_played = 0;
            self.changed.notify_all();
        }
    }

    pub fn track_start(&self, uri: &str, album: &str, duration_ms: u32, natural: bool) {
        let mut lanes = self.lanes();
        if natural && lanes.capturing {
            // The next song starts now: the tail is complete, and the blend
            // lasts as long as it is.
            lanes.capturing = false;
            lanes.fade_frames = (lanes.tail.len() / 2).max(1);
            HOLD_MS.store((lanes.fade_frames / 2 * 1000 / RATE) as u64, Ordering::Relaxed);
        } else if !natural {
            lanes.skip();
        }
        lanes.playing_song = Song { uri: uri.into(), album: album.into(), duration_ms };
        lanes.next = None;
        lanes.last_position = None;
        self.changed.notify_all();
    }

    pub fn preloaded(&self, uri: &str, album: &str, duration_ms: u32) {
        self.lanes().next = Some(Song { uri: uri.into(), album: album.into(), duration_ms });
    }

    pub fn pause(&self, paused: bool) {
        self.lanes().paused = paused;
        self.changed.notify_all();
    }

    // ---- The mixer thread ----

    fn run(&self) {
        let mut effects = effects::Processor::new(crate::EFFECTS.clone());
        let mut limiter = limiter::Limiter::new(RATE as f64);
        let mut gain = volume::Gain::new(RATE as f64);
        let mut java_paused = true;
        loop {
            let mut lanes = self.lanes();
            if let Some(fade) = lanes.flush.take() {
                drop(lanes);
                self.call("skip", "(Z)V", &[JValue::Bool(fade.into())]);
                vis::clear();
                java_paused = true;
                continue;
            }
            if lanes.paused || (lanes.main.is_empty() && lanes.tail.is_empty()) {
                if lanes.paused && !java_paused {
                    drop(lanes);
                    self.call("stop", "()V", &[]);
                    java_paused = true;
                    continue;
                }
                drop(self.changed.wait(lanes).unwrap_or_else(|p| p.into_inner()));
                continue;
            }
            let chunk = mix(&mut lanes);
            self.changed.notify_all();
            drop(lanes);
            if chunk.is_empty() {
                continue;
            }
            // Same order as the desktop's `vis::Tapped`: effects, then the
            // limiter, since bass and reverb can push past full scale.
            let mut samples = effects.process(chunk.into());
            limiter.process(&mut samples, 1.0);
            vis::push(&samples);
            // After the tap, so the bars don't follow the volume.
            gain.process(&mut samples);
            let floats: Vec<f32> = samples.iter().map(|s| *s as f32).collect();
            self.write_java(&floats);
            java_paused = false;
        }
    }

    fn write_java(&self, samples: &[f32]) {
        let target = self.target.lock().unwrap_or_else(|p| p.into_inner());
        let Some((vm, out)) = target.as_ref() else { return };
        let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
        // This thread stays attached, so local references must be freed here.
        let _ = env.with_local_frame(4, |env| -> jni::errors::Result<()> {
            let array = env.new_float_array(samples.len() as i32)?;
            env.set_float_array_region(&array, 0, samples)?;
            env.call_method(out, "write", "([FI)V", &[JValue::Object(&array), JValue::Int(samples.len() as i32)])?;
            Ok(())
        });
        crate::clear_exception(&mut env);
    }

    fn call(&self, method: &str, signature: &str, args: &[JValue]) {
        let target = self.target.lock().unwrap_or_else(|p| p.into_inner());
        let Some((vm, out)) = target.as_ref() else { return };
        let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
        let _ = env.call_method(out, method, signature, args);
        crate::clear_exception(&mut env);
    }
}

/// Up to [CHUNK] frames: the main lane alone, or the tail fading out over
/// the main lane fading in.
fn mix(lanes: &mut Lanes) -> Vec<f64> {
    let mut out = Vec::with_capacity(CHUNK * 2);
    if lanes.tail.is_empty() {
        let n = lanes.main.len().min(CHUNK * 2) & !1;
        out.extend(lanes.main.drain(..n));
        return out;
    }
    while out.len() < CHUNK * 2 && lanes.tail.len() >= 2 {
        let total = if lanes.capturing {
            lanes.fade_frames.max(lanes.tail_played + lanes.tail.len() / 2)
        } else {
            lanes.fade_frames
        }
        .max(1);
        let (out_gain, in_gain) = curves(lanes.tail_played as f64 / total as f64);
        // While the old song is still arriving, the new one hasn't started.
        let next = !lanes.capturing && lanes.main.len() >= 2;
        for _ in 0..2 {
            let a = lanes.tail.pop_front().unwrap_or(0.0);
            let b = if next { lanes.main.pop_front().unwrap_or(0.0) } else { 0.0 };
            out.push(a * out_gain + b * in_gain);
        }
        lanes.tail_played += 1;
    }
    if lanes.tail.len() < 2 && !lanes.capturing {
        lanes.tail.clear();
        lanes.tail_played = 0;
        lanes.fade_frames = 0;
    }
    out
}

/// Equal power: the two gains' squares always sum to one, so the blend
/// keeps its loudness instead of dipping in the middle.
pub fn curves(t: f64) -> (f64, f64) {
    let t = t.clamp(0.0, 1.0);
    ((t * FRAC_PI_2).cos(), (t * FRAC_PI_2).sin())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_blend_keeps_its_power() {
        for i in 0..=20 {
            let (a, b) = curves(i as f64 / 20.0);
            assert!((a * a + b * b - 1.0).abs() < 1e-9);
        }
        assert_eq!(curves(0.0), (1.0, 0.0));
    }

    fn lanes(crossfade_ms: u32) -> Lanes {
        CROSSFADE_MS.store(crossfade_ms, Ordering::Relaxed);
        Lanes {
            playing_song: Song { uri: "a".into(), album: "x".into(), duration_ms: 200_000 },
            next: Some(Song { uri: "b".into(), album: "y".into(), duration_ms: 200_000 }),
            ..Lanes::default()
        }
    }

    #[test]
    fn albums_repeats_and_short_songs_change_the_blend() {
        let mut l = lanes(6000);
        assert_eq!(l.blend_ms(), 6000);
        l.next.as_mut().unwrap().album = "x".into();
        ALBUMS_GAPLESS.store(true, Ordering::Relaxed);
        assert_eq!(l.blend_ms(), 0, "the next song of the same album stays seamless");
        let mut l = lanes(6000);
        l.next.as_mut().unwrap().uri = "a".into();
        assert_eq!(l.blend_ms(), 0, "repeat one doesn't fade into itself");
        let mut l = lanes(12_000);
        l.next.as_mut().unwrap().duration_ms = 9_000;
        assert_eq!(l.blend_ms(), 3_000);
        assert_eq!(lanes(0).blend_ms(), 0);
        let mut l = lanes(6000);
        l.next = None;
        assert_eq!(l.blend_ms(), 0, "nothing loaded to fade into");
    }

    #[test]
    fn the_tail_fades_out_while_the_next_song_fades_in() {
        let mut l = Lanes::default();
        l.tail.extend(std::iter::repeat_n(1.0, 200));
        l.main.extend(std::iter::repeat_n(1.0, 400));
        l.fade_frames = 100;
        let out = mix(&mut l);
        assert_eq!(out.len(), 200);
        assert!((out[0] - 1.0).abs() < 1e-9, "starts as the old song alone");
        // Equal power sums to more than 1 in the middle for identical sound.
        assert!(out[100] > 1.3);
        assert!(l.tail.is_empty() && l.fade_frames == 0);
        assert_eq!(l.main.len(), 200, "the new song carried on from where the blend left it");
        assert_eq!(mix(&mut l).len(), 200);
    }

    #[test]
    fn a_skip_drops_everything_queued() {
        let mut l = lanes(6000);
        l.main.extend([0.5; 10]);
        l.tail.extend([0.5; 10]);
        l.capturing = true;
        l.skip();
        assert!(l.main.is_empty() && l.tail.is_empty() && !l.capturing && l.flush.is_some());
    }
}
