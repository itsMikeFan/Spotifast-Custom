//! Bar heights for the player's visualizer: the newest sound the player
//! wrote, through an FFT, grouped into log-spaced bands.

use std::sync::Mutex;

pub const BANDS: usize = 24;
const N: usize = 1024;
const RATE: f32 = 44_100.0;

/// The latest mono samples, oldest first.
static RECENT: Mutex<Vec<f32>> = Mutex::new(Vec::new());

/// Keeps the newest `N` mono samples of interleaved stereo `samples`.
// shortcut: these are the samples written to the AudioTrack, about a quarter
// second ahead of what is heard; delay them if the bars look early.
pub fn push(samples: &[f64]) {
    let mut recent = RECENT.lock().unwrap_or_else(|p| p.into_inner());
    recent.extend(
        samples
            .chunks(2)
            .map(|frame| (frame.iter().sum::<f64>() / frame.len() as f64) as f32),
    );
    let excess = recent.len().saturating_sub(N);
    recent.drain(..excess);
}

pub fn bands() -> Vec<f32> {
    let recent = RECENT.lock().unwrap_or_else(|p| p.into_inner()).clone();
    levels(&recent)
}

/// `BANDS` levels from 0 to 1 for up to `N` samples.
fn levels(samples: &[f32]) -> Vec<f32> {
    if samples.len() < N {
        return vec![0.0; BANDS];
    }
    let mut re: Vec<f32> = samples
        .iter()
        .enumerate()
        .map(|(i, s)| s * (0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / N as f32).cos()))
        .collect();
    let mut im = vec![0.0f32; N];
    fft(&mut re, &mut im);
    // Bands from 40 Hz to 16 kHz, evenly spaced on a log scale.
    let (low, high) = (40.0f32, 16_000.0f32);
    (0..BANDS)
        .map(|band| {
            let from = low * (high / low).powf(band as f32 / BANDS as f32);
            let to = low * (high / low).powf((band + 1) as f32 / BANDS as f32);
            let first = ((from / RATE * N as f32) as usize).max(1);
            let last = ((to / RATE * N as f32) as usize).max(first + 1).min(N / 2);
            let peak = (first..last)
                .map(|bin| (re[bin] * re[bin] + im[bin] * im[bin]).sqrt())
                .fold(0.0f32, f32::max);
            // About 60 dB of range above the noise floor.
            let db = 20.0 * (peak / (N as f32 / 4.0)).max(1e-6).log10();
            ((db + 60.0) / 60.0).clamp(0.0, 1.0)
        })
        .collect()
}

/// In-place radix-2 FFT; `re.len()` must be a power of two.
fn fft(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j |= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2;
    while len <= n {
        let angle = -std::f32::consts::TAU / len as f32;
        for start in (0..n).step_by(len) {
            for k in 0..len / 2 {
                let (sin, cos) = (angle * k as f32).sin_cos();
                let (a, b) = (start + k, start + k + len / 2);
                let (tr, ti) = (re[b] * cos - im[b] * sin, re[b] * sin + im[b] * cos);
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
            }
        }
        len <<= 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_tone_lights_its_own_band_and_silence_none() {
        assert!(levels(&vec![0.0; N]).iter().all(|&level| level == 0.0));
        // 550 Hz, mid-band, at half scale.
        let tone: Vec<f32> = (0..N)
            .map(|i| 0.5 * (std::f32::consts::TAU * 550.0 * i as f32 / RATE).sin())
            .collect();
        let levels = levels(&tone);
        let loudest = (0..BANDS)
            .max_by(|&a, &b| levels[a].total_cmp(&levels[b]))
            .unwrap();
        let band_hz = |band: usize| 40.0 * 400f32.powf(band as f32 / BANDS as f32);
        assert!(
            band_hz(loudest) <= 550.0 && 550.0 < band_hz(loudest + 1),
            "band {loudest}"
        );
        assert!(levels[loudest] > 0.8);
        assert!(levels[0] < 0.3, "low bands stay quiet");
    }
}
