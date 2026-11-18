//! Rate, bass and reverb for local playback.
//!
//! Rate works like a turntable: slower is also lower. It runs the
//! equalized signal through the output resampler at a ratio of its own.
//! Bass is a low shelf after the rate, so it stays at the same pitch.
//! The UI writes settings behind a mutex; the player reads them once per
//! packet. Like the equalizer, this stage does not clip; the limiter in
//! `vis::Tapped` does.

use std::ops::RangeInclusive;
use std::sync::{Arc, Mutex};

use librespot_playback::{NUM_CHANNELS, SAMPLE_RATE};

use crate::eq::Biquad;
use crate::resample::Resampler;

/// How far the rate goes, in percent of normal speed.
pub const RATE_PERCENT: RangeInclusive<u16> = 50..=150;
/// How far bass goes either way, in decibels.
pub const BASS_DB: f32 = 12.0;
/// Where the bass shelf turns, in hertz.
const BASS_HZ: f64 = 100.0;

/// Rate, bass and reverb settings.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct EffectsSettings {
    pub on: bool,
    /// Percent of normal speed; pitch follows.
    pub rate_percent: u16,
    /// The lows raised or lowered, in decibels.
    pub bass_db: f32,
    /// How much reverb is heard, 0 dry to 1 all reverb.
    pub reverb_mix: f32,
    /// 0 a small room to 1 a large hall.
    pub reverb_room: f32,
}

impl Default for EffectsSettings {
    fn default() -> Self {
        Self {
            on: false,
            rate_percent: 100,
            bass_db: 0.0,
            reverb_mix: 0.0,
            reverb_room: 0.5,
        }
    }
}

impl EffectsSettings {
    /// The same settings kept within what the effects can do.
    pub fn clamped(mut self) -> Self {
        self.rate_percent = self
            .rate_percent
            .clamp(*RATE_PERCENT.start(), *RATE_PERCENT.end());
        self.bass_db = self.bass_db.clamp(-BASS_DB, BASS_DB);
        self.reverb_mix = self.reverb_mix.clamp(0.0, 1.0);
        self.reverb_room = self.reverb_room.clamp(0.0, 1.0);
        self
    }

    /// How fast the music plays: 1.0 while the effects are off.
    pub fn rate(&self) -> f64 {
        if self.on {
            f64::from(self.rate_percent) / 100.0
        } else {
            1.0
        }
    }

    /// Off, or on with nothing to change.
    pub fn is_neutral(&self) -> bool {
        !self.on || (self.rate_percent == 100 && self.bass_db == 0.0 && self.reverb_mix == 0.0)
    }
}

/// How fast local playback moves through the song: 1.0 is normal.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rate(pub f64);

impl Default for Rate {
    fn default() -> Self {
        Self(1.0)
    }
}

/// The settings as the popover and the player's thread share them.
pub type SharedEffects = Arc<Mutex<EffectsSettings>>;

pub fn shared() -> SharedEffects {
    Arc::new(Mutex::new(EffectsSettings::default()))
}

/// Freeverb's comb filter lengths at 44.1 kHz, left channel.
const COMBS: [usize; 8] = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
/// Freeverb's all-pass lengths at 44.1 kHz, left channel.
const ALL_PASSES: [usize; 4] = [556, 441, 341, 225];
/// The right channel's extra length, which decorrelates the two sides.
const STEREO_SPREAD: usize = 23;
/// Keeps eight combs' sum near full scale.
const INPUT_GAIN: f64 = 0.015;
const WET_GAIN: f64 = 3.0;
/// The share of each echo the comb's low-pass keeps from the last one.
const DAMPING: f64 = 0.2;
/// Comb feedback at the smallest room; the largest adds `ROOM_SPAN`.
/// Freeverb's own top of 0.98 rings for over ten seconds.
const ROOM_FLOOR: f64 = 0.7;
const ROOM_SPAN: f64 = 0.25;

/// A feedback comb with a one-pole low-pass in its loop.
struct Comb {
    buffer: Vec<f64>,
    at: usize,
    filtered: f64,
}

impl Comb {
    fn new(length: usize) -> Self {
        Self {
            buffer: vec![0.0; length],
            at: 0,
            filtered: 0.0,
        }
    }

    fn clear(&mut self) {
        self.buffer.fill(0.0);
        self.filtered = 0.0;
    }

    fn run(&mut self, input: f64, feedback: f64) -> f64 {
        let output = self.buffer[self.at];
        self.filtered = output * (1.0 - DAMPING) + self.filtered * DAMPING;
        self.buffer[self.at] = input + self.filtered * feedback;
        self.at = (self.at + 1) % self.buffer.len();
        output
    }
}

struct AllPass {
    buffer: Vec<f64>,
    at: usize,
}

impl AllPass {
    fn new(length: usize) -> Self {
        Self {
            buffer: vec![0.0; length],
            at: 0,
        }
    }

    fn run(&mut self, input: f64) -> f64 {
        let delayed = self.buffer[self.at];
        self.buffer[self.at] = input + delayed * 0.5;
        self.at = (self.at + 1) % self.buffer.len();
        delayed - input
    }
}

/// Freeverb (Jezar, public domain): eight parallel combs into four
/// all-passes, per channel.
struct Reverb {
    channels: [(Vec<Comb>, Vec<AllPass>); 2],
}

impl Reverb {
    fn new() -> Self {
        let channel = |spread: usize| {
            (
                COMBS
                    .iter()
                    .map(|&length| Comb::new(length + spread))
                    .collect(),
                ALL_PASSES
                    .iter()
                    .map(|&length| AllPass::new(length + spread))
                    .collect(),
            )
        };
        Self {
            channels: [channel(0), channel(STEREO_SPREAD)],
        }
    }

    /// Silences the room without giving its memory back.
    fn clear(&mut self) {
        for (combs, all_passes) in &mut self.channels {
            combs.iter_mut().for_each(Comb::clear);
            for all_pass in all_passes {
                all_pass.buffer.fill(0.0);
            }
        }
    }

    fn process(&mut self, samples: &mut [f64], mix: f64, room: f64) {
        let feedback = ROOM_FLOOR + ROOM_SPAN * room;
        for frame in samples.as_chunks_mut::<2>().0 {
            let input = (frame[0] + frame[1]) * INPUT_GAIN;
            for (sample, (combs, all_passes)) in frame.iter_mut().zip(&mut self.channels) {
                let mut wet: f64 = combs.iter_mut().map(|comb| comb.run(input, feedback)).sum();
                for all_pass in all_passes.iter_mut() {
                    wet = all_pass.run(wet);
                }
                *sample = *sample * (1.0 - mix) + wet * WET_GAIN * mix;
            }
        }
    }
}

/// The effects on the player's thread.
pub struct Processor {
    shared: SharedEffects,
    /// The resampler for the rate it was built for.
    rate: Option<(u16, Resampler)>,
    /// The bass shelf, one per channel, for the gain it was built for.
    bass: Option<(f32, [Biquad; 2])>,
    reverb: Reverb,
    /// The reverb holds sound that has not died away yet.
    ringing: bool,
}

impl Processor {
    pub fn new(shared: SharedEffects) -> Self {
        Self {
            shared,
            rate: None,
            bass: None,
            reverb: Reverb::new(),
            ringing: false,
        }
    }

    /// Discards the previous signal without changing the settings.
    pub fn reset(&mut self) {
        self.rate = None;
        self.bass = None;
        self.silence_reverb();
    }

    /// A reverb switched off forgets its room, or the old song's tail
    /// would come back under whatever plays when it is switched on again.
    fn silence_reverb(&mut self) {
        if self.ringing {
            self.reverb.clear();
            self.ringing = false;
        }
    }

    /// Runs interleaved stereo samples through the effects. The result
    /// may hold more or fewer frames than were given.
    pub fn process(&mut self, samples: Vec<f64>) -> Vec<f64> {
        let settings = self
            .shared
            .lock()
            .map(|settings| settings.clamped())
            .unwrap_or_default();
        if settings.is_neutral() {
            self.reset();
            return samples;
        }
        let mut samples = self.change_rate(&settings, samples);
        self.shape_bass(settings.bass_db, &mut samples);
        if settings.reverb_mix > 0.0 {
            self.reverb.process(
                &mut samples,
                f64::from(settings.reverb_mix),
                f64::from(settings.reverb_room),
            );
            self.ringing = true;
        } else {
            self.silence_reverb();
        }
        samples
    }

    fn shape_bass(&mut self, gain_db: f32, samples: &mut [f64]) {
        if gain_db == 0.0 {
            self.bass = None;
            return;
        }
        let shelf = Biquad::low_shelf(BASS_HZ, f64::from(gain_db));
        let (built, filters) = self.bass.get_or_insert((gain_db, [shelf; 2]));
        if *built != gain_db {
            *built = gain_db;
            *filters = filters.map(|filter| filter.retuned(shelf));
        }
        for frame in samples.as_chunks_mut::<2>().0 {
            for (sample, filter) in frame.iter_mut().zip(filters.iter_mut()) {
                *sample = filter.run(*sample);
            }
        }
    }

    fn change_rate(&mut self, settings: &EffectsSettings, samples: Vec<f64>) -> Vec<f64> {
        let percent = settings.rate_percent;
        if percent == 100 {
            self.rate = None;
            return samples;
        }
        let from = SAMPLE_RATE / 100 * u32::from(percent);
        match &mut self.rate {
            Some((built, _)) if *built == percent => {}
            // A new rate keeps the old one's history, so dragging is seamless.
            Some((built, resampler)) => {
                resampler.retune(from, SAMPLE_RATE);
                *built = percent;
            }
            // shortcut: starting from 100% begins with 31 frames of
            // silence, one tiny dip each time the rate leaves normal speed;
            // keep a resampler running at 100% too if it is audible.
            None => {
                self.rate = Resampler::new(from, SAMPLE_RATE, NUM_CHANNELS as usize)
                    .map(|resampler| (percent, resampler));
            }
        }
        let Some((_, resampler)) = &mut self.rate else {
            return samples;
        };
        let narrowed: Vec<f32> = samples.iter().map(|&sample| sample as f32).collect();
        resampler
            .process(&narrowed)
            .into_iter()
            .map(f64::from)
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FRAMES_PER_SECOND: usize = 44_100;

    fn processor(settings: EffectsSettings) -> (SharedEffects, Processor) {
        let shared = shared();
        *shared.lock().unwrap() = settings;
        let processor = Processor::new(std::sync::Arc::clone(&shared));
        (shared, processor)
    }

    fn rate(percent: u16) -> EffectsSettings {
        EffectsSettings {
            on: true,
            rate_percent: percent,
            ..EffectsSettings::default()
        }
    }

    /// Interleaved stereo sine, both channels alike, starting at `from`.
    fn sine(hz: f64, from: usize, frames: usize) -> Vec<f64> {
        (from..from + frames)
            .flat_map(|frame| {
                let value = (2.0 * std::f64::consts::PI * hz * frame as f64
                    / FRAMES_PER_SECOND as f64)
                    .sin()
                    * 0.5;
                [value, value]
            })
            .collect()
    }

    fn frames_out(processor: &mut Processor, packets: usize, frames: usize) -> usize {
        (0..packets)
            .map(|packet| {
                processor
                    .process(sine(440.0, packet * frames, frames))
                    .len()
                    / 2
            })
            .sum()
    }

    #[test]
    fn neutral_settings_pass_samples_through() {
        let input = sine(440.0, 0, 1024);
        for settings in [
            EffectsSettings::default(),
            EffectsSettings {
                on: true,
                ..EffectsSettings::default()
            },
        ] {
            let (_, mut processor) = processor(settings);
            assert_eq!(processor.process(input.clone()), input);
        }
    }

    #[test]
    fn rate_150_returns_two_thirds_the_frames() {
        let (_, mut processor) = processor(rate(150));
        let out = frames_out(&mut processor, 100, 1024);
        assert!(out.abs_diff(102_400 * 2 / 3) <= 64, "{out} frames");
    }

    #[test]
    fn rate_50_returns_twice_the_frames() {
        let (_, mut processor) = processor(rate(50));
        let out = frames_out(&mut processor, 100, 1024);
        assert!(out.abs_diff(204_800) <= 64, "{out} frames");
    }

    #[test]
    fn rate_120_raises_a_1khz_sine_to_1200hz() {
        let (_, mut processor) = processor(rate(120));
        let out: Vec<f64> = (0..FRAMES_PER_SECOND / 1024)
            .flat_map(|packet| processor.process(sine(1000.0, packet * 1024, 1024)))
            .collect();
        // Past the resampler's start-up, where it fades in from silence.
        let left: Vec<f64> = out.iter().step_by(2).skip(64).copied().collect();
        let crossings = left
            .windows(2)
            .filter(|pair| (pair[0] < 0.0) != (pair[1] < 0.0))
            .count();
        let hz = crossings as f64 / 2.0 / (left.len() as f64 / FRAMES_PER_SECOND as f64);
        assert!((hz - 1200.0).abs() < 12.0, "{hz} Hz");
    }

    #[test]
    fn dragging_rate_stays_finite() {
        let (shared, mut processor) = processor(rate(50));
        for (index, percent) in (50..=150).enumerate() {
            shared.lock().unwrap().rate_percent = percent;
            let out = processor.process(sine(440.0, index * 512, 512));
            assert!(out.iter().all(|sample| sample.is_finite()));
            if index >= 3 {
                assert!(!out.is_empty(), "packet {index} at {percent}% was silent");
            }
        }
    }

    #[test]
    fn clamped_keeps_values_in_range() {
        let high = EffectsSettings {
            rate_percent: 999,
            reverb_room: 2.0,
            ..EffectsSettings::default()
        }
        .clamped();
        assert_eq!((high.rate_percent, high.reverb_room), (150, 1.0));
        let low = EffectsSettings {
            rate_percent: 0,
            reverb_mix: -1.0,
            ..EffectsSettings::default()
        }
        .clamped();
        assert_eq!((low.rate_percent, low.reverb_mix), (50, 0.0));
    }

    fn reverb(mix: f32, room: f32) -> EffectsSettings {
        EffectsSettings {
            on: true,
            reverb_mix: mix,
            reverb_room: room,
            ..EffectsSettings::default()
        }
    }

    /// A full-scale click on both channels, then `seconds` of silence,
    /// through the processor in 1024-frame packets.
    fn impulse_response(processor: &mut Processor, seconds: f64) -> Vec<f64> {
        let frames = (seconds * FRAMES_PER_SECOND as f64) as usize;
        let mut input = vec![0.0; frames * 2];
        input[0] = 1.0;
        input[1] = 1.0;
        input
            .chunks(1024 * 2)
            .flat_map(|packet| processor.process(packet.to_vec()))
            .collect()
    }

    fn peak(samples: &[f64]) -> f64 {
        samples
            .iter()
            .fold(0.0, |peak, sample| sample.abs().max(peak))
    }

    #[test]
    fn reverb_at_zero_mix_adds_nothing() {
        let (_, mut processor) = processor(reverb(0.0, 1.0));
        let input = sine(440.0, 0, 1024);
        assert_eq!(processor.process(input.clone()), input);
    }

    #[test]
    fn reverb_tail_decays_below_minus_60_db() {
        let (_, mut processor) = processor(reverb(1.0, 1.0));
        let out = impulse_response(&mut processor, 8.0);
        let last_half_second = &out[out.len() - FRAMES_PER_SECOND..];
        assert!(peak(last_half_second) < 0.001, "{}", peak(last_half_second));
    }

    #[test]
    fn reverb_is_heard_after_the_impulse() {
        let (_, mut processor) = processor(reverb(1.0, 1.0));
        let out = impulse_response(&mut processor, 1.0);
        let at = |ms: usize| ms * FRAMES_PER_SECOND / 1000 * 2;
        assert!(peak(&out[at(50)..at(500)]) > 0.001);
    }

    fn silence(processor: &mut Processor, seconds: f64) -> Vec<f64> {
        let samples = vec![0.0; (seconds * FRAMES_PER_SECOND as f64) as usize * 2];
        samples
            .chunks(1024 * 2)
            .flat_map(|packet| processor.process(packet.to_vec()))
            .collect()
    }

    #[test]
    fn switching_effects_off_forgets_the_reverb_tail() {
        let (shared, mut processor) = processor(reverb(1.0, 1.0));
        impulse_response(&mut processor, 0.1);
        shared.lock().unwrap().on = false;
        silence(&mut processor, 0.1);
        shared.lock().unwrap().on = true;
        assert_eq!(peak(&silence(&mut processor, 0.5)), 0.0);
    }

    #[test]
    fn mix_at_zero_forgets_the_reverb_tail() {
        let (shared, mut processor) = processor(reverb(1.0, 1.0));
        impulse_response(&mut processor, 0.1);
        shared.lock().unwrap().reverb_mix = 0.0;
        silence(&mut processor, 0.1);
        shared.lock().unwrap().reverb_mix = 1.0;
        assert_eq!(peak(&silence(&mut processor, 0.5)), 0.0);
    }

    #[test]
    fn changing_rate_keeps_a_steady_level() {
        let (shared, mut processor) = processor(rate(120));
        let steady = vec![0.25; 512 * 2];
        for _ in 0..10 {
            processor.process(steady.clone());
        }
        for percent in [130, 90, 149] {
            shared.lock().unwrap().rate_percent = percent;
            let out = processor.process(steady.clone());
            assert!(!out.is_empty());
            for sample in out {
                assert!((sample - 0.25).abs() < 0.01, "{sample} at {percent}%");
            }
        }
    }

    fn bass(gain_db: f32) -> EffectsSettings {
        EffectsSettings {
            on: true,
            bass_db: gain_db,
            ..EffectsSettings::default()
        }
    }

    /// How much the processor changes a sine's level, in decibels, once
    /// the filter has settled.
    fn gain_db_at(settings: EffectsSettings, hz: f64) -> f64 {
        let (_, mut processor) = processor(settings);
        let out: Vec<f64> = (0..FRAMES_PER_SECOND / 1024)
            .flat_map(|packet| processor.process(sine(hz, packet * 1024, 1024)))
            .collect();
        20.0 * (peak(&out[out.len() / 2..]) / 0.5).log10()
    }

    #[test]
    fn bass_boost_raises_the_lows_and_leaves_the_highs() {
        let low = gain_db_at(bass(12.0), 30.0);
        assert!((low - 12.0).abs() < 1.0, "{low} dB at 30 Hz");
        let high = gain_db_at(bass(12.0), 5000.0);
        assert!(high.abs() < 0.5, "{high} dB at 5 kHz");
    }

    #[test]
    fn bass_cut_lowers_the_lows() {
        let low = gain_db_at(bass(-12.0), 30.0);
        assert!((low + 12.0).abs() < 1.0, "{low} dB at 30 Hz");
    }

    #[test]
    fn moving_bass_stays_finite() {
        let (shared, mut processor) = processor(bass(-12.0));
        for (index, step) in (-12..=12).enumerate() {
            shared.lock().unwrap().bass_db = step as f32;
            let out = processor.process(sine(60.0, index * 512, 512));
            assert!(out.iter().all(|sample| sample.is_finite()));
        }
    }

    #[test]
    fn bass_is_clamped_to_twelve_db() {
        assert_eq!(bass(99.0).clamped().bass_db, 12.0);
        assert_eq!(bass(-99.0).clamped().bass_db, -12.0);
    }

    #[test]
    fn reset_clears_the_tail() {
        let (_, mut processor) = processor(reverb(1.0, 1.0));
        impulse_response(&mut processor, 0.1);
        processor.reset();
        let silence = vec![0.0; FRAMES_PER_SECOND / 10 * 2];
        let out: Vec<f64> = silence
            .chunks(1024 * 2)
            .flat_map(|packet| processor.process(packet.to_vec()))
            .collect();
        assert_eq!(peak(&out), 0.0);
    }
}
