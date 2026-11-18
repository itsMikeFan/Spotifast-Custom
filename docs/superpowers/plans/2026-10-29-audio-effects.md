# Audio Effects (Rate + Reverb) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A player-bar popover that adds a turntable-style rate (50% to 150%) and a two-control reverb to local playback, saved between restarts.

**Architecture:** A new `src/effects.rs` (settings, rate stage, Freeverb) runs inside `vis::Tapped::write` between the equalizer and the visualiser tap. The rate stage reuses `crate::resample::Resampler`. The UI's position interpolation (`LocalState::position_now`) is scaled by the active rate, which reaches the engine through a new `PlayerCommand::Rate`.

**Tech Stack:** Rust 2024 (1.98), egui fork, librespot 0.8 fork. No new crates.

**Spec:** `docs/superpowers/specs/2026-10-29-audio-effects-design.md`

## Prerequisites (ask the user before each)

1. Install Rust with rustup (`rustup-init.exe` from rustup.rs, default MSVC host). `rust-toolchain`/`rust-version` require 1.98.
2. Install Visual Studio 2022 Build Tools with the "Desktop development with C++" workload (MSVC linker and Windows SDK).
3. `cargo build` once on the untouched branch to confirm the baseline builds and `cargo test --locked --all-targets` passes before Task 1. Record any test that already fails.

## Global Constraints

- Rate range 50% to 150% in 1% steps, stored as an integer percent. 100 is normal.
- Reverb `mix` 0.0 to 1.0, `room` 0.0 to 1.0. Defaults: rate 100, mix 0.0, room 0.5, effects off.
- Chain order: decoder, equalizer, rate, reverb, visualiser tap, volume, limiter, sink.
- Effects apply to local playback only. No new crates (`AGENTS.md`).
- All user-visible strings through `gettext`/`pgettext`, added to `assets/i18n/spotifast.pot`.
- No em dashes in strings or docs (`AGENTS.md`).
- Every commit compiles and passes `cargo clippy --locked --all-targets -- -D warnings` and `cargo fmt --all --check`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Dragging Rate continuously rebuilds the resampler every packet: output must stay finite, with no long silence or burst. Test in Task 1.
2. Skip or seek with reverb on: no tail from the old song may reach the new one. Test in Task 3.
3. Pause and resume at 150%: the shown position must not jump on either. Test in Task 4.
4. A settings file with out-of-range values (rate 999, mix -1): clamped on load, not rejected. Test in Task 5.
5. Playing on another device: the effects sliders are disabled and nothing is sent to the engine. Test in Task 6.

---

### Task 1: Effects settings and the rate stage

**Files:**
- Create: `src/effects.rs`
- Modify: `src/lib.rs` (add `pub mod effects;` beside `pub mod eq;`)

**Interfaces:**
- Produces:
  - `pub const RATE_PERCENT: RangeInclusive<u16> = 50..=150;`
  - `#[derive(Clone, Copy, Debug, PartialEq)] pub struct EffectsSettings { pub on: bool, pub rate_percent: u16, pub reverb_mix: f32, pub reverb_room: f32 }`, `Default` per Global Constraints.
  - `EffectsSettings::clamped(self) -> Self`, `EffectsSettings::rate(&self) -> f64` (1.0 when off, else `rate_percent / 100`), `EffectsSettings::is_neutral(&self) -> bool` (off, or rate 100 and mix 0).
  - `pub type SharedEffects = Arc<Mutex<EffectsSettings>>;` and `pub fn shared() -> SharedEffects`.
  - `pub struct Processor` with `new(shared: SharedEffects) -> Self`, `reset(&mut self)`, `process(&mut self, samples: Vec<f64>) -> Vec<f64>` (interleaved stereo at 44.1 kHz in, interleaved stereo at 44.1 kHz out, frame count may differ).

- [ ] **Step 1: Write the failing tests** in `#[cfg(test)] mod tests` of `src/effects.rs`:
  - `neutral_settings_pass_samples_through`: off, and on with defaults, return the input `Vec` unchanged (`assert_eq!`).
  - `rate_150_returns_two_thirds_the_frames`: feed 100 packets of 1024 frames; total output frames is within 64 of `102_400 * 2 / 3`.
  - `rate_50_returns_twice_the_frames`: same, within 64 of `204_800`.
  - `rate_120_raises_a_1khz_sine_to_1200hz`: 1 s of 1 kHz sine at rate 120; count zero crossings of the left channel over the output, divide by output seconds at 44.1 kHz; result within 1% of 1200 Hz.
  - `dragging_rate_stays_finite`: change `rate_percent` from 50 to 150 by 1 before each of 101 packets of 512 frames; every output sample is finite, and no packet's output is empty after the first three.
  - `clamped_keeps_values_in_range`: `rate_percent: 999` gives 150, `0` gives 50, `reverb_mix: -1.0` gives 0.0, `reverb_room: 2.0` gives 1.0.

- [ ] **Step 2: Run them to verify they fail**

Run: `cargo test --locked --lib effects::`
Expected: compile error, `effects` items not found.

- [ ] **Step 3: Implement the settings and rate stage in `src/effects.rs`**

Module doc in the style of `src/eq.rs`. Rate: keep `Option<(u16, Resampler)>`. When `rate()` is not 1.0, build `Resampler::new(441 * rate_percent as u32, 44_100, 2)` if the stored percent differs, and convert `f64` to `f32` and back around `Resampler::process`. `// shortcut: a rate change rebuilds the resampler and drops its 31-frame history, a tiny skip while dragging; crossfade two resamplers if it is audible.` `reset()` drops the resampler. The reverb is added in Task 2; leave `process` calling only the rate stage.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cargo test --locked --lib effects::`
Expected: 6 passed.

- [ ] **Step 5: Commit** `src/effects.rs src/lib.rs` as "Add effects settings and turntable rate stage".

### Task 2: Freeverb reverb

**Files:**
- Modify: `src/effects.rs`

**Interfaces:**
- Consumes: Task 1's `Processor`, `EffectsSettings`.
- Produces: `Processor::process` now applies rate, then reverb. No new public items.

- [ ] **Step 1: Write the failing tests:**
  - `reverb_at_zero_mix_adds_nothing`: on, rate 100, mix 0.0, room 1.0; output equals input.
  - `reverb_tail_decays_below_minus_60_db`: on, rate 100, mix 1.0, room 1.0; a single full-scale impulse then 8 s of silence in 1024-frame packets; peak absolute sample over the final 0.5 s is below `0.001`.
  - `reverb_is_heard_after_the_impulse`: same settings; some sample between 50 ms and 500 ms after the impulse exceeds `0.001`.
  - `reset_clears_the_tail`: after an impulse and 100 ms of silence, `reset()`, then 100 ms of silence yields all zeros.

- [ ] **Step 2: Run them to verify they fail**

Run: `cargo test --locked --lib effects::`
Expected: the three non-zero-mix tests fail (no reverb yet).

- [ ] **Step 3: Implement Freeverb** as a private `Reverb` struct in `src/effects.rs`, state in `f64`:
  - Comb delays (samples at 44.1 kHz) `[1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617]`; all-pass `[556, 441, 341, 225]`; right channel adds 23 to each.
  - Input gain `0.015`, all-pass feedback `0.5`, damping fixed `0.5` (comb low-pass `damp1 = 0.2`, `damp2 = 0.8`).
  - Comb feedback `0.7 + 0.25 * room` (0.7 to 0.95, per the spec).
  - Mono input `(l + r) * 0.015` into all combs, per Freeverb. Wet output scaled by `3.0`. `out = dry * (1 - mix) + wet * mix`.
  - Skipped entirely when `mix == 0.0`. `reset()` zeroes every buffer.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cargo test --locked --lib effects::`
Expected: 10 passed.

- [ ] **Step 5: Commit** as "Add Freeverb reverb to the effects stage".

### Task 3: Effects in the audio path

**Files:**
- Modify: `src/vis.rs:146-249` (`Tapped`), and its test helper `recorded_tap` near line 683.
- Modify: `src/player.rs:52-66` (`EngineConfig` gets `pub effects: crate::effects::SharedEffects`), the `Tapped::new` calls near `player.rs:870-900`, and the test config near `player.rs:1842`.
- Modify: `src/app.rs` `engine_config` (~line 13404) gains an `effects: crate::effects::SharedEffects` parameter after `eq`; its callers in `App::new` (~line 975) and `Action::RestartEngine` (~line 11952).
- Modify: `src/backend.rs:8420` test call.
- Modify: `src/winamp.rs`: `WinampState` keeps `pub effects: crate::effects::SharedEffects` beside `eq`, so `app.winamp.effects` is the app's handle (same as `app.winamp.eq`).

**Interfaces:**
- Consumes: `crate::effects::{Processor, SharedEffects, shared}`.
- Produces: `Tapped::new(inner, control, tap, volume, applies_volume, eq, effects: SharedEffects, normalisation)`. `App.winamp.effects: SharedEffects`.

- [ ] **Step 1: Write the failing tests** in `src/vis.rs` tests:
  - `effects_run_between_the_equalizer_and_the_tap`: rate 150 via the shared settings; write 20 packets of 1024 frames through `Tapped`; the recorded inner sink receives within 64 of `20_480 * 2 / 3` frames, and the tap received the same count.
  - Extend the existing test asserting "the old limiter delay and equalizer tail must not reach the new track" (vis.rs ~line 727) to also run with effects on, mix 1.0, room 1.0: after the processing reset, the first packet of the new track contains no reverb tail (equal to the new input processed from silence).

- [ ] **Step 2: Run them to verify they fail**

Run: `cargo test --locked --lib vis::`
Expected: compile error on the new `Tapped::new` argument.

- [ ] **Step 3: Wire it in.** `Tapped` holds `effects: crate::effects::Processor`. In `write`, call `self.effects.reset()` beside `self.eq.reset()`, and run `samples = self.effects.process(samples)` right after `self.eq.process`. `AudioPacket::Raw` passes through untouched as today. `App::new` creates `crate::effects::shared()` filled from settings (Task 5 adds the fields; until then use `EffectsSettings::default()`).

- [ ] **Step 4: Run the checks**

Run: `cargo test --locked --lib vis::` then `cargo clippy --locked --all-targets -- -D warnings`
Expected: pass, no warnings.

- [ ] **Step 5: Commit** as "Run effects between the equalizer and the visualisers".

### Task 4: Position follows the rate

**Files:**
- Modify: `src/player.rs`: `LocalState` (~172), `position_now` (~227), `PlayerCommand` (~287), `send_command` (~735), engine `LocalState` creation (~541).
- Modify: `src/app.rs` optimistic position interpolation (~line 2018).

**Interfaces:**
- Consumes: `EffectsSettings::rate()` from Task 1, `EngineConfig.effects` from Task 3.
- Produces: `LocalState.rate: crate::effects::Rate`, where `#[derive(Clone, Copy, Debug, PartialEq)] pub struct Rate(pub f64)` lives in `src/effects.rs` with `Default` = `Rate(1.0)` (so `LocalState::default()` stays correct). `PlayerCommand::Rate(f64)`. `LocalState::set_rate(&mut self, rate: f64)`.

- [ ] **Step 1: Write the failing tests** in `src/player.rs` tests:
  - `position_advances_at_the_playback_rate`: playing, `position_ms: 10_000`, `position_at` 1 s ago, `rate: Rate(1.5)`; `position_now()` is within 50 of `11_500`.
  - `changing_rate_keeps_the_shown_position`: playing at 1.0 from 1 s ago at 10_000; `set_rate(1.5)`; `position_ms` is within 50 of 11_000, `position_at` is now, and `rate` is 1.5.
  - `pause_and_resume_at_150_do_not_jump`: through `KeptPlayer::toggle`'s state logic (or the same re-anchoring it uses), the position after pausing equals `position_now()` just before, and right after resuming equals it again.
  - `rate_does_not_interrupt_audio`: `command_interrupts_audio(&playing, &PlayerCommand::Rate(1.5))` is false.

- [ ] **Step 2: Run them to verify they fail**

Run: `cargo test --locked --lib player::`
Expected: compile errors (`rate`, `set_rate`, `PlayerCommand::Rate` missing).

- [ ] **Step 3: Implement.** `position_now` multiplies `elapsed` by `self.rate.0` before adding. `set_rate` re-anchors (`position_ms = position_now()`, `position_at = Some(now)` only while playing) then stores the rate. `send_command` handles `PlayerCommand::Rate(rate)` by locking `self.state` and calling `set_rate`; no Spotify request. The engine's initial `LocalState` takes `rate` from `config.effects`. In `app.rs`, the `pending_local_position` interpolation multiplies its `elapsed` by `self.local.rate.0`.

- [ ] **Step 4: Run the tests**

Run: `cargo test --locked --lib player::` and `cargo test --locked --lib app::`
Expected: pass.

- [ ] **Step 5: Commit** as "Move the shown position at the effects rate".

### Task 5: Settings and actions

**Files:**
- Modify: `src/settings.rs`: fields after `mono` (~line 417), defaults (~line 548), clamp on load where the EQ values are clamped, test near line 1357.
- Modify: `src/model.rs` `Action` enum after `ApplyUserEqPreset` (~line 1426).
- Modify: `src/app.rs`: `effects_settings(settings: &Settings) -> crate::effects::EffectsSettings` beside `eq_settings` (~line 13433); `push_effects(&mut self)` beside `push_eq` (~line 4146); handlers after the EQ ones (~line 12180); `App::new` fills the shared effects from settings.

**Interfaces:**
- Consumes: Task 1 types, Task 3's `app.winamp.effects`, Task 4's `PlayerCommand::Rate`.
- Produces: `Settings { effects_on: bool, effects_rate_percent: u16, reverb_mix: f32, reverb_room: f32 }`. Actions: `ToggleEffects`, `SetEffectsRate(u16)`, `SetReverbMix(f32)`, `SetReverbRoom(f32)`, `ResetEffects`.

- [ ] **Step 1: Write the failing tests:**
  - `settings.rs`: `settings_without_effects_load_with_defaults`: `serde_json::from_str::<Settings>("{}")` loads with `effects_on == false`, `effects_rate_percent == 100`, `reverb_mix == 0.0`, `reverb_room == 0.5`.
  - `settings.rs`: `out_of_range_effects_are_clamped_on_load`: `effects_rate_percent = 999`, `reverb_mix = -1` load as 150 and 0.0.
  - `app.rs`: `effects_actions_reach_the_player_and_settings`: apply `SetEffectsRate(80)` with effects off; then `app.settings.effects_on` is true (moving a slider switches effects on), `app.winamp.effects.lock().unwrap().rate_percent == 80`, `settings_dirty` is set, and `app.local.rate == Rate(0.8)`. Apply `ResetEffects`; all four values return to defaults and `effects_on` is false.

- [ ] **Step 2: Run them to verify they fail**

Run: `cargo test --locked --lib settings::` and `cargo test --locked --lib app::tests::effects`
Expected: compile errors.

- [ ] **Step 3: Implement.** Follow the `eq_*` fields' serde and doc style. `push_effects` writes the shared settings, sets `settings_dirty`, calls `self.local.set_rate(rate)` for the optimistic UI, and sends `self.backend.player(PlayerCommand::Rate(rate))`. The three `Set*` actions set `effects_on = true` before pushing.

- [ ] **Step 4: Run the tests**

Run: the same commands. Expected: pass.

- [ ] **Step 5: Commit** as "Save effects settings and apply effects actions".

### Task 6: The effects popover

**Files:**
- Modify: `src/ui/player_bar.rs` `extras` (~line 911): a new button before the devices button (~line 985).
- Modify: `assets/i18n/spotifast.pot` with the new strings.
- Modify: `src/demo.rs` tests.

**Interfaces:**
- Consumes: Task 5's actions and settings; `theme::icon_button`, `Icon::AudioLines`, `egui::Popup::menu`, `super::widgets::{menu_frame, switch, thin_slider, SliderEvent}`.
- Produces: UI only.

Strings (exact): button tooltip `"Effects"`; switch `"Effects"`; sliders `"Rate (%)"`, `"Reverb mix (%)"`, `"Room size (%)"`; readout `"{semitones} semitones"` with `{semitones}` formatted as signed one decimal (`+3.9`, `-3.9`, `0.0`); button `"Reset"`; remote note `"Effects only change music playing on this computer."`

- [ ] **Step 1: Write the failing demo tests** in `src/demo.rs`:
  - `effects_popover_opens_from_the_player_bar`: `accessible_app`, find `("Effects", Role::Button)`, click it with `accessible_action(.., AccessibleAction::Click, None)`, next frame has `("Rate (%)", Role::Slider)`, `("Reverb mix (%)", Role::Slider)`, `("Room size (%)", Role::Slider)`.
  - `effects_sliders_are_disabled_when_playing_elsewhere`: with the demo's now-playing set to a remote device (`local: false`), the three sliders are disabled in the accessibility tree and no `SetEffects*` action is queued after dragging.
  - Add `("Effects", Role::Button)` to the translated player controls list (~line 2344).

- [ ] **Step 2: Run them to verify they fail**

Run: `cargo test --locked --all-features --lib demo::tests::effects`
Expected: node "Effects" not found.

- [ ] **Step 3: Implement** using the liked-songs popup pattern (`player_bar.rs` ~line 636): `egui::Popup::menu(&response).id(..).close_behavior(CloseOnClickOutside).frame(menu_frame(&palette))`. The button is `palette.accent` while `effects_on` and not neutral, else `palette.secondary`. Rate slider maps 0..1 to 50..150 and commits on drag (local and cheap, like local volume). Wrap the sliders in `ui.add_enabled_ui(local, ..)`, where `local` is `now.is_none_or(|now| now.local)`, and show the remote note when not local. Add the strings to `spotifast.pot`.

- [ ] **Step 4: Run the tests and lints**

Run: `cargo test --locked --all-features --lib demo::` then `cargo clippy --locked --all-targets --all-features -- -D warnings`
Expected: pass.

- [ ] **Step 5: Commit** as "Add the effects popover to the player bar".

### Task 7: Docs, full checks, and a listen

**Files:**
- Modify: `docs/_reference/what-spotify-allows.md` (the "Playback speed and crossfade" bullet becomes crossfade only; add rate under Spotifast's own features if the page lists them).
- Modify: `docs/_guide/using-spotifast.md`: a short "Effects" section: what each control does, local playback only, and the Connect position limit from the spec.
- Modify: `README.md` only if it lists the equalizer as a feature; then add effects beside it.

- [ ] **Step 1: Write the docs.** No em dashes.
- [ ] **Step 2: Run the full checks** from `CONTRIBUTING.md`:

```bash
cargo fmt --all --check
cargo clippy --locked --all-targets -- -D warnings
cargo clippy --locked --all-targets --all-features -- -D warnings
cargo test --locked --all-targets
cargo test --locked --all-targets --all-features
```

Expected: all pass, or only the failures recorded in Prerequisite 3.
- [ ] **Step 3: Build and listen.** `cargo build --release`, run `target/release/spotifast.exe` (the installed copy is not touched). Play a song; check rate 80% with mix 40% and room 80%, rate 130%, Reset, a skip and a seek with reverb on, the progress bar and lyrics against the sound, and that the settings survive a restart.
- [ ] **Step 4: Commit** as "Document audio effects".
