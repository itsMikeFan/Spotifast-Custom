# Audio effects: rate and reverb

Date: 2026-10-29. Branch: `feature/audio-effects`.

## Goal

Let the listener change how local playback sounds while it plays: a
turntable-style **rate** (slower is also lower, faster is also higher) and a
**reverb**. The controls live in a popover opened from a new button in the
player bar. This is the "slowed + reverb" and "nightcore" sound, made live.

Success: a song can be slowed to 80% with a large hall reverb, the progress
bar and synced lyrics stay in step with what is heard, the setting survives a
restart, and Reset returns to the untouched sound.

## Scope

In:

- One `Rate` control, 50% to 150% in 1% steps. 100% is normal. The popover
  also shows the pitch change in semitones (`12 * log2(rate)`), for reading
  only.
- Reverb with two controls: `Mix` (0 to 100%, wet against dry) and
  `Room size` (0 to 100%, small room to large hall).
- An on/off switch for all effects and a Reset button that restores rate
  100%, mix 0%, room size 50%, and switches effects off.
- Settings persist with the other settings and load from older settings
  files that do not have them.

Out (not built): independent pitch or speed (time stretching), reverb damping
or width controls, presets, effects for Connect, Sonos or other remote
devices, the Winamp skin window, the Settings page, telling the operating
system's media controls about the rate.

Effects only change audio decoded on this computer, as the equalizer does.

## Audio path

`Tapped::write` in `src/vis.rs` already runs, per decoded packet:
equalizer, visualiser tap, volume, limiter, then the device sink. The effects
go between the equalizer and the tap:

```
decoder -> equalizer -> rate -> reverb -> visualiser tap -> volume -> limiter -> sink
```

- **Rate before reverb**, as slowed + reverb tracks are made: the room is not
  pitched along with the music.
- **Before the tap**, so every visualiser shows what is heard (the
  project rule: post-equalizer, pre-volume).
- **Before the limiter**, which already catches any level reverb adds.

New module `src/effects.rs`, shaped like `src/eq.rs`:

- `EffectsSettings { on, rate, reverb_mix, reverb_room }` with `Default`,
  `clamped()`, and serde defaults. `SharedEffects = Arc<Mutex<EffectsSettings>>`,
  passed to the player the same way `SharedEq` is.
- `Processor::process(Vec<f64>) -> Vec<f64>` reads the settings once per
  packet and returns the processed packet. It may return a different number
  of frames than it was given; the sink already counts frames, not packets.
- `Processor::reset()` clears the resampler and reverb state. It runs where
  the equalizer is reset (`take_processing_reset`: explicit skips and
  confirmed seeks). Natural gapless transitions keep the reverb tail.
- Off, or at rate 100% and mix 0%, the packet passes through untouched.

**Rate** reuses `crate::resample::Resampler` (polyphase windowed sinc) with
`from = 441 * rate_percent` and `to = 44_100`, so 1% steps give small
rational ratios. A rate change builds a new resampler at the next packet.
The resampler works in `f32`; the decoder's samples started as `f32`, so the
round trip loses nothing audible. The plan checks that the resampler lowers
its cutoff when it reduces the frame count (rates above 100%). If it does
not, the plan adds that rather than bringing in a crate.

**Reverb** is a small Freeverb (eight comb and four all-pass filters per
channel, the right channel's delays offset by 23 samples), written in the
module. Freeverb is public domain. Room size maps to comb feedback
(0.7 to 0.95; Freeverb's own 0.98 rings for over ten seconds); damping is
fixed at 0.5.

Backpressure keeps the decoder in step: the sink blocks when its queue is
full, so at 150% the decoder simply runs 1.5 times as fast.

## Position, progress bar and lyrics

The UI interpolates the position in `LocalState::position_now`
(`src/player.rs`) as last reported position plus wall-clock time since. At
another rate that is wrong, so the elapsed time is multiplied by the active
rate (1.0 while effects are off). The seek bar, lyrics and media controls all
read `position_now`, so they follow.

What librespot does (checked in the pinned fork, `playback/src/player.rs`):

- **Below 100%** the decoder falls behind wall-clock time, and librespot
  sends a `PositionCorrection` once it lags by one second. Spotifast already
  re-anchors on that event. Connect turns each correction into a state
  update to Spotify (`SeekChanged`), so playback at 80% sends one about every
  5 seconds, and at 50% one about every 2 seconds.
- **Above 100%** librespot sends no correction, so the scaled interpolation
  is what keeps the UI right.

**Known limit:** other Spotify apps watching this computer extrapolate the
position at normal speed. Above 100% they show it falling behind; below 100%
they jump back every few seconds. Spotify's Connect state has no playback
rate. This is documented, not worked around.

## Interface

- A new icon button in the player bar, beside volume, using the existing
  `assets/icons/audio-lines.svg`. It is highlighted
  while effects are on and not at default.
- Clicking it opens a popover: an `Effects` switch, then `Rate` (with its
  semitone readout), `Reverb mix` and `Room size` sliders, and `Reset`.
- Moving a slider while effects are off switches them on.
- When playback is on another device, the sliders are disabled and the
  popover says effects apply to this computer only.
- All strings go through `gettext` and `assets/i18n/spotifast.pot`.
- UI emits `Action`s (`ToggleEffects`, `SetEffectsRate`, `SetReverbMix`,
  `SetReverbRoom`, `ResetEffects`), applied in `src/app.rs` like the
  equalizer's, which write the shared settings and mark settings for saving.

## Settings

Four new fields in the settings file (`src/settings.rs`), flat like the
equalizer's `eq_*` fields: `effects_on`, `effects_rate_percent`,
`reverb_mix`, `reverb_room`. They default when missing, are written
atomically by the existing save path, and are clamped on load.

## Testing

Unit tests in `src/effects.rs`:

- Off, and on at defaults, return the samples unchanged.
- Rate 150% returns two-thirds the frames (within the resampler's latency);
  rate 50% returns twice as many.
- A 1 kHz sine at rate 120% comes out at 1.2 kHz.
- The reverb's response to an impulse decays below -60 dB within a few
  seconds at the largest room, and adds no level at mix 0%.
- `reset()` clears the tail.

Elsewhere:

- `position_now` advances 1.5 seconds per wall-clock second at 150%.
- A settings file without `effects` loads with the defaults.
- A demo-feature UI test that opens the popover.

Then the full checks from `CONTRIBUTING.md`, a Windows build, and listening
on this PC. Only Windows is tested by hand; macOS and Linux are only
compiled in CI, if at all.

## Docs

`docs/_reference/what-spotify-allows.md` loses "Playback speed" from the
unsupported list (crossfade stays). `docs/_guide/using-spotifast.md` gets a
short section on effects, including the Connect limit.
