//! The player's visualizer, from the newest sound the player wrote: the
//! desktop's player bar analyser (src/vis.rs `WideAnalyser`, Winamp's
//! seventy-five semitone bands with falling peaks) and its waveform.
//! Copied rather than included, since the desktop file also holds its sink.

use std::collections::VecDeque;
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

const SAMPLE_RATE: u32 = 44_100;
/// Half a second of mono sound.
const KEPT: usize = SAMPLE_RATE as usize / 2;
/// How far behind the newest sample to look: the AudioTrack holds about a
/// quarter second, so this shows what the speaker plays, not what was queued.
pub const LAG: usize = SAMPLE_RATE as usize / 4;
pub const FFT_SAMPLES: usize = 512;
const SPECTRUM_BINS: usize = FFT_SAMPLES / 2;
pub const COLUMNS: usize = 75;
const MAX_HEIGHT: f32 = 15.0;
const FALLOFF: f32 = 12.0 / 16.0;
pub const STEP: Duration = Duration::from_micros(16_667);
const CHANNEL_SUM: f32 = 2.0;
const SPEC_SCALE: f32 = 0.5;

static RECENT: Mutex<VecDeque<f32>> = Mutex::new(VecDeque::new());
static ANALYSER: LazyLock<Mutex<WideAnalyser>> = LazyLock::new(Mutex::default);

/// Keeps the newest half second of interleaved stereo `samples`, as mono.
pub fn push(samples: &[f64]) {
    let mut recent = RECENT.lock().unwrap_or_else(|p| p.into_inner());
    recent.extend(
        samples
            .chunks(2)
            .map(|frame| (frame.iter().sum::<f64>() / frame.len() as f64) as f32),
    );
    let excess = recent.len().saturating_sub(KEPT);
    recent.drain(..excess);
}

/// Forgets the sound, so the bars fall when playback stops or skips.
pub fn clear() {
    RECENT.lock().unwrap_or_else(|p| p.into_inner()).clear();
}

/// `count` samples ending `LAG` before the newest.
fn window(count: usize) -> Vec<f32> {
    let recent = RECENT.lock().unwrap_or_else(|p| p.into_inner());
    let end = recent.len().saturating_sub(LAG);
    let start = end.saturating_sub(count);
    recent.range(start..end).copied().collect()
}

/// The bars' levels then their peak caps, each 0 to 1, `WIDE_BANDS` of each.
pub fn spectrum() -> Vec<f32> {
    let samples = window(WIDE_SAMPLES);
    let mut analyser = ANALYSER.lock().unwrap_or_else(|p| p.into_inner());
    let levels = analyser.step(&samples, Instant::now());
    levels.iter().chain(analyser.peaks().iter()).copied().collect()
}

/// The waveform: `points` values from -1 to 1.
pub fn scope(points: usize) -> Vec<f32> {
    scope_line(&window(points * SCOPE_STEP), points)
}

/// The classic analyser's FFT, as Winamp's own source has it: 512
/// samples under a Hann window, 256 magnitudes out, each halved. The
/// player bar's analyser runs the same transform over more samples.
struct Fft {
    bit_reversed: Vec<usize>,
    envelope: Vec<f32>,
    twiddles: Vec<(f32, f32)>,
    real: Vec<f32>,
    imaginary: Vec<f32>,
}

impl Fft {
    fn new(n: usize) -> Self {
        let mut bit_reversed: Vec<usize> = (0..n).collect();
        let mut j = 0;
        for i in 0..n {
            if j > i {
                bit_reversed.swap(i, j);
            }
            let mut m = n >> 1;
            while m >= 1 && j >= m {
                j -= m;
                m >>= 1;
            }
            j += m;
        }
        let envelope = (0..n)
            .map(|i| {
                let phase = i as f32 / n as f32 * std::f32::consts::TAU;
                0.5 + 0.5 * (phase - std::f32::consts::FRAC_PI_2).sin()
            })
            .collect();
        let mut twiddles = Vec::new();
        let mut size = 2;
        while size <= n {
            let theta = -std::f32::consts::TAU / size as f32;
            twiddles.push((theta.cos(), theta.sin()));
            size <<= 1;
        }
        Self {
            bit_reversed,
            envelope,
            twiddles,
            real: vec![0.0; n],
            imaginary: vec![0.0; n],
        }
    }

    fn spectrum(&mut self, wave: &[f32], out: &mut [f32]) {
        let n = self.real.len();
        for i in 0..n {
            let from = self.bit_reversed[i];
            self.real[i] = wave.get(from).copied().unwrap_or(0.0) * self.envelope[from];
            self.imaginary[i] = 0.0;
        }
        let mut size = 2;
        let mut stage = 0;
        while size <= n {
            let (wpr, wpi) = self.twiddles[stage];
            let (mut wr, mut wi) = (1.0f32, 0.0f32);
            let half = size >> 1;
            for m in 0..half {
                let mut i = m;
                while i < n {
                    let j = i + half;
                    let tr = wr * self.real[j] - wi * self.imaginary[j];
                    let ti = wr * self.imaginary[j] + wi * self.real[j];
                    self.real[j] = self.real[i] - tr;
                    self.imaginary[j] = self.imaginary[i] - ti;
                    self.real[i] += tr;
                    self.imaginary[i] += ti;
                    i += size;
                }
                let previous = wr;
                wr = wr * wpr - wi * wpi;
                wi = wi * wpr + previous * wpi;
            }
            size <<= 1;
            stage += 1;
        }
        for (i, slot) in out.iter_mut().enumerate() {
            *slot = (self.real[i] * self.real[i] + self.imaginary[i] * self.imaginary[i]).sqrt()
                * SPEC_SCALE;
        }
    }
}

/// Winamp's bands over a 256-bin spectrum.
/// Winamp's bands over a 256-bin spectrum; see [`Analyser::bands`].
fn bands(spectrum: &[f32; SPECTRUM_BINS]) -> [f32; COLUMNS + 1] {
    let bla = 255.0 / 2f32.powf(75.0 / 12.0);
    let warp = |x: f32| (2f32.powf(x / 12.0) - 1.0) * bla;
    let sample = |index: usize| spectrum.get(index).copied().unwrap_or(0.0);
    let hermite = |x: f32, y0: f32, y1: f32, y2: f32, y3: f32| {
        let c1 = 0.5 * (y2 - y0);
        let c3 = 1.5 * (y1 - y2) + 0.5 * (y3 - y0);
        let c2 = y0 - y1 + c1 - c3;
        ((c3 * x + c2) * x + c1) * x + y1
    };
    let mut columns = [0.0; COLUMNS + 1];
    let mut next = warp(0.0) + 1.0;
    for (x, column) in columns.iter_mut().take(COLUMNS).enumerate() {
        let low = next;
        next = warp(x as f32 + 1.0) + 1.0;
        let mut value = 0.0f32;
        let mut bin = low.floor() as usize;
        let end = (next.floor() as usize).min(SPECTRUM_BINS - 1);
        let mut fraction = low;
        let mut mult = (bin as f32 + 1.0) - low;
        let mut herm = true;
        loop {
            if bin == end {
                mult = next - fraction;
                herm = true;
            }
            if herm {
                value += hermite(
                    fraction - bin as f32,
                    sample(bin.saturating_sub(1)),
                    sample(bin),
                    sample(bin + 1),
                    sample(bin + 2),
                ) * mult;
            } else {
                value += sample(bin);
            }
            herm = false;
            bin += 1;
            if bin > end {
                break;
            }
            fraction = bin as f32;
        }
        *column = value.min(255.0);
    }
    columns
}


/// Samples in one spectrum of the player bar's analyser: Winamp's own.
pub const WIDE_SAMPLES: usize = FFT_SAMPLES;
/// The player bar's bands: every one of Winamp's semitone columns as a bar
/// of its own, where the skin groups them four to a bar.
pub const WIDE_BANDS: usize = COLUMNS;
/// How fast a bar falls, in heights per second: Winamp's twelve
/// sixteenths of a row each sixtieth of a second, over fifteen rows.
const WIDE_FALL: f32 = FALLOFF / MAX_HEIGHT / 0.016_667;
/// How long a peak cap hangs above its band before it drops, and how fast
/// it then falls, in heights per second squared.
const PEAK_HOLD: f32 = 0.35;
const PEAK_GRAVITY: f32 = 2.4;

/// The player bar's spectrum analyser: Winamp's classic analyser, with
/// each of its seventy-five columns as a bar, heights from 0 to 1 instead
/// of whole rows, and falls timed by the clock, so they move smoothly at
/// any frame rate.
pub struct WideAnalyser {
    fft: Fft,
    wave: [f32; FFT_SAMPLES],
    spectrum: [f32; SPECTRUM_BINS],
    levels: [f32; WIDE_BANDS],
    /// Each band's peak cap, how long it has hung there, and how fast it
    /// is falling.
    peaks: [f32; WIDE_BANDS],
    held: [f32; WIDE_BANDS],
    speed: [f32; WIDE_BANDS],
    last: Option<Instant>,
}

impl Default for WideAnalyser {
    fn default() -> Self {
        Self {
            fft: Fft::new(FFT_SAMPLES),
            wave: [0.0; FFT_SAMPLES],
            spectrum: [0.0; SPECTRUM_BINS],
            levels: [0.0; WIDE_BANDS],
            peaks: [0.0; WIDE_BANDS],
            held: [0.0; WIDE_BANDS],
            speed: [0.0; WIDE_BANDS],
            last: None,
        }
    }
}

impl WideAnalyser {
    /// Moves the bars towards the spectrum of `samples` (mono, -1 to 1,
    /// `WIDE_SAMPLES` of them): up at once, as Winamp's, and down at its
    /// rate for the time since the last step.
    pub fn step(&mut self, samples: &[f32], now: Instant) -> [f32; WIDE_BANDS] {
        let elapsed = self
            .last
            .map_or(STEP, |last| now.saturating_duration_since(last))
            .min(Duration::from_millis(250))
            .as_secs_f32();
        self.last = Some(now);
        self.wave.fill(0.0);
        for (slot, sample) in self.wave.iter_mut().zip(samples) {
            *slot = sample * CHANNEL_SUM;
        }
        self.fft.spectrum(&self.wave, &mut self.spectrum);
        let columns = bands(&self.spectrum);
        for (level, column) in self.levels.iter_mut().zip(columns) {
            let target = column.min(MAX_HEIGHT) / MAX_HEIGHT;
            *level = (*level - WIDE_FALL * elapsed).max(target);
            // Too low to see: at rest.
            if *level < 0.01 {
                *level = 0.0;
            }
        }
        for band in 0..WIDE_BANDS {
            let level = self.levels[band];
            if level >= self.peaks[band] {
                self.peaks[band] = level;
                self.held[band] = 0.0;
                self.speed[band] = 0.0;
            } else if self.held[band] < PEAK_HOLD {
                self.held[band] += elapsed;
            } else {
                self.speed[band] += PEAK_GRAVITY * elapsed;
                self.peaks[band] = (self.peaks[band] - self.speed[band] * elapsed).max(level);
            }
            if self.peaks[band] < 0.01 {
                self.peaks[band] = 0.0;
            }
        }
        self.levels
    }

    /// Where each band's peak cap hangs, from 0 to 1.
    pub fn peaks(&self) -> [f32; WIDE_BANDS] {
        self.peaks
    }

    /// Whether every band and peak has fallen to rest.
    pub fn settled(&self) -> bool {
        self.levels
            .iter()
            .chain(&self.peaks)
            .all(|level| *level == 0.0)
    }
}

/// The player bar's waveform: Winamp's scope, every seventh sample with
/// nothing smoothed or lined up, so it trembles as Winamp's does, for
/// `points` columns instead of seventy-five. Each value runs from -1 to 1
/// across the bar's height; like Winamp's, whose sixteen rows span half of
/// full scale, anything louder than half full scale reaches the edge.
pub fn scope_line(samples: &[f32], points: usize) -> Vec<f32> {
    (0..points)
        .map(|point| {
            let sample = samples.get(point * SCOPE_STEP).copied().unwrap_or(0.0);
            (sample * 2.0).clamp(-1.0, 1.0)
        })
        .collect()
}

/// Winamp's scope reads every seventh sample.
pub const SCOPE_STEP: usize = 7;

#[cfg(test)]

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn silence_rests_and_a_tone_lifts_one_part_of_the_spectrum() {
        let mut a = WideAnalyser::default();
        assert!(a.step(&[0.0; WIDE_SAMPLES], Instant::now()).iter().all(|l| *l == 0.0));
        let tone: Vec<f32> = (0..WIDE_SAMPLES)
            .map(|i| (i as f32 * 1000.0 * std::f32::consts::TAU / SAMPLE_RATE as f32).sin() * 0.5)
            .collect();
        let levels = a.step(&tone, Instant::now());
        let loudest = levels.iter().enumerate().max_by(|x, y| x.1.total_cmp(y.1)).unwrap().0;
        assert!(levels[loudest] > 0.5);
        assert!(levels[0] < levels[loudest] && levels[COLUMNS - 1] < levels[loudest]);
    }

    #[test]
    fn the_waveform_has_the_points_asked_for() {
        assert_eq!(scope_line(&[0.9; 70], 10), vec![1.0; 10]);
        assert_eq!(scope_line(&[], 4), vec![0.0; 4]);
    }
}
