//! The app's own volume: kept here instead of in librespot's soft mixer, so
//! it is applied after the visualizer tap (the bars don't shrink as you turn
//! down, as on the desktop) and ramps instead of jumping.

use std::sync::atomic::{AtomicU16, Ordering};

use librespot_core::Error;
use librespot_playback::config::VolumeCtrl;
use librespot_playback::mixer::mappings::MappedCtrl;
use librespot_playback::mixer::{Mixer, MixerConfig};

/// 0..=u16::MAX, as Spotify Connect counts it. Starts at full, as before
/// the slider existed.
pub static VOLUME: AtomicU16 = AtomicU16::new(u16::MAX);

/// The desktop's curve (src/player.rs): cubic over 60 dB, so 50% here sounds
/// like 50% on the PC.
pub fn gain(volume: u16) -> f64 {
    VolumeCtrl::Cubic(VolumeCtrl::DEFAULT_DB_RANGE).to_mapped(volume)
}

pub fn percent_to_volume(percent: i32) -> u16 {
    (percent.clamp(0, 100) as u32 * u32::from(u16::MAX) / 100) as u16
}

pub fn volume_to_percent(volume: u16) -> u8 {
    ((u32::from(volume) * 100 + u32::from(u16::MAX) / 2) / u32::from(u16::MAX)) as u8
}

/// What Spirc sees: it reports [VOLUME] to Spotify and sets it when another
/// app moves the phone's volume. librespot itself never scales the sound.
pub struct AppMixer;

impl Mixer for AppMixer {
    fn open(_: MixerConfig) -> Result<Self, Error> {
        Ok(Self)
    }

    fn volume(&self) -> u16 {
        VOLUME.load(Ordering::Relaxed)
    }

    fn set_volume(&self, volume: u16) {
        VOLUME.store(volume, Ordering::Relaxed);
    }
}

/// Applies [VOLUME] to interleaved stereo, moving toward a new level over
/// about 20 ms so a dragged slider doesn't crackle.
pub struct Gain {
    now: f64,
    step: f64,
}

impl Gain {
    pub fn new(sample_rate: f64) -> Self {
        Self {
            now: gain(VOLUME.load(Ordering::Relaxed)),
            step: 1.0 / (0.020 * sample_rate),
        }
    }

    pub fn process(&mut self, samples: &mut [f64]) {
        let target = gain(VOLUME.load(Ordering::Relaxed));
        for frame in samples.chunks_mut(2) {
            if self.now != target {
                let d = target - self.now;
                self.now = if d.abs() <= self.step { target } else { self.now + self.step * d.signum() };
            }
            for s in frame {
                *s *= self.now;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_ends_are_silence_and_full() {
        assert_eq!(gain(0), 0.0);
        assert_eq!(gain(u16::MAX), 1.0);
        assert!(gain(percent_to_volume(50)) > 0.0 && gain(percent_to_volume(50)) < 0.5);
        assert_eq!(volume_to_percent(percent_to_volume(37)), 37);
    }

    #[test]
    fn a_change_ramps_instead_of_jumping() {
        let mut g = Gain { now: 1.0, step: 1.0 / 882.0 };
        VOLUME.store(0, Ordering::Relaxed);
        let mut samples = vec![1.0; 2 * 100];
        g.process(&mut samples);
        assert!(samples[0] < 1.0 && samples[0] > 0.99);
        assert!(samples[199] > 0.8, "100 frames is far less than 20 ms");
        VOLUME.store(u16::MAX, Ordering::Relaxed);
    }
}
