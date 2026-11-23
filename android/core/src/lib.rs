//! The Android app's native core: one librespot session, player and Spirc,
//! with the desktop's own effects, played through an Android `AudioTrack`
//! that the Kotlin side hands in. Kotlin calls the `Java_..._Core_*`
//! functions below; everything else is internal.

#![allow(dead_code)]

// The desktop's DSP, shared file for file so both apps sound the same.
#[path = "../../../src/effects.rs"]
mod effects;
#[path = "../../../src/eq.rs"]
mod eq;
#[path = "../../../src/limiter.rs"]
mod limiter;
mod output;
#[path = "../../../src/resample.rs"]
mod resample;
mod vis;
mod volume;
// The desktop's Google Cast and Spotify Connect receiver activation.
#[path = "../../../src/cast.rs"]
mod cast;
#[path = "../../../src/zeroconf.rs"]
mod zeroconf;

use std::sync::{LazyLock, Mutex};
use std::time::Instant;

use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{GlobalRef, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jstring};
use librespot_connect::{
    ConnectConfig, LoadContextOptions, LoadRequest, LoadRequestOptions, PlayingTrack, Spirc,
};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::{DeviceType, SessionConfig};
use librespot_core::session::Session;
use librespot_metadata::audio::UniqueFields;
use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::config::{Bitrate, PlayerConfig};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::Mixer;
use librespot_playback::player::{Player, PlayerEvent};

/// The client the playback grant belongs to (src/auth.rs on the desktop).
const PLAYBACK_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

/// The desktop's device ID: the SHA-1 of the device name, in hex.
fn device_id(name: &str) -> String {
    use sha1::{Digest, Sha1};
    Sha1::digest(name.as_bytes())
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

static RUNTIME: LazyLock<tokio::runtime::Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
});
static SPIRC: Mutex<Option<Spirc>> = Mutex::new(None);
/// The connected session, which Cast activation borrows the account from.
static SESSION: Mutex<Option<Session>> = Mutex::new(None);
/// The player behind [SPIRC], kept to preload the song a load starts with.
static PLAYER: Mutex<Option<std::sync::Arc<Player>>> = Mutex::new(None);
/// Songs queued from this app that librespot's queue list doesn't show yet:
/// it only republishes that list on the next playback change.
/// Bitrate, normalisation, gapless and autoplay, read on each connect.
static OPTIONS: Mutex<(i32, bool, bool, bool)> = Mutex::new((320, false, true, true));
static QUEUED: Mutex<Vec<String>> = Mutex::new(Vec::new());
static STATE: LazyLock<Mutex<State>> = LazyLock::new(Mutex::default);
static EFFECTS: LazyLock<effects::SharedEffects> = LazyLock::new(effects::shared);

/// What the app shows about playback.
#[derive(Default)]
struct State {
    connected: bool,
    playing: bool,
    uri: String,
    name: String,
    artists: String,
    duration_ms: u32,
    /// Where the song was when `at` was taken.
    position_ms: u32,
    at: Option<Instant>,
    shuffle: bool,
    repeat_context: bool,
    repeat_track: bool,
    /// Why playback stopped short, from the player or librespot's log; cleared once a song plays.
    error: String,
}

impl State {
    /// The position now: song time moves at the effects' rate while playing.
    fn position_now(&self, rate: f64) -> u32 {
        let elapsed = match (self.playing, self.at) {
            (true, Some(at)) => (at.elapsed().as_secs_f64() * 1000.0 * rate) as u32,
            _ => 0,
        };
        self.position_ms
            .saturating_add(elapsed)
            .min(self.duration_ms.max(self.position_ms))
    }

    fn json(&self, rate: f64, next: &[String]) -> String {
        serde_json::json!({
            "next": next,
            "connected": self.connected,
            "playing": self.playing,
            "uri": self.uri,
            "name": self.name,
            "artists": self.artists,
            "duration_ms": self.duration_ms,
            "position_ms": self.position_now(rate),
            "shuffle": self.shuffle,
            "volume": volume::volume_to_percent(volume::VOLUME.load(std::sync::atomic::Ordering::Relaxed)),
            // 0 off, 1 the playlist, 2 this song.
            "repeat": if self.repeat_track { 2 } else if self.repeat_context { 1 } else { 0 },
            "error": self.error,
        })
        .to_string()
    }

    fn at(&mut self, position_ms: u32, playing: bool) {
        self.position_ms = position_ms;
        self.playing = playing;
        self.at = Some(Instant::now());
    }
}

fn state() -> std::sync::MutexGuard<'static, State> {
    STATE.lock().unwrap_or_else(|p| p.into_inner())
}

fn rate() -> f64 {
    EFFECTS.lock().map_or(1.0, |s| s.clamped().rate())
}

/// Hands librespot's sound to [output::OUTPUT], which crossfades and plays it.
struct AndroidSink;

fn clear_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
}

impl Sink for AndroidSink {
    fn start(&mut self) -> SinkResult<()> {
        output::OUTPUT.pause(false);
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        output::OUTPUT.pause(true);
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, _converter: &mut Converter) -> SinkResult<()> {
        if let AudioPacket::Samples(samples) = packet {
            output::OUTPUT.write(&samples);
        }
        Ok(())
    }

    fn position(&mut self, position_ms: u32, duration_ms: u32) {
        output::OUTPUT.position(position_ms, duration_ms);
    }

    fn track_start(&mut self, uri: &str, album: &str, duration_ms: u32, natural: bool) {
        output::OUTPUT.track_start(uri, album, duration_ms, natural);
    }

    fn preloaded(&mut self, uri: &str, album: &str, duration_ms: u32) {
        output::OUTPUT.preloaded(uri, album, duration_ms);
    }
}

/// Connects, with a fresh streaming token or the credentials librespot kept
/// in `cache_dir` from the last sign-in. Blocks until Spotify answers.
fn connect(
    vm: JavaVM,
    out: GlobalRef,
    cache_dir: String,
    token: Option<String>,
    device_name: String,
) -> anyhow::Result<()> {
    // shortcut: librespot keeps the reusable credentials as a file in the
    // app's private storage, not in Android Keystore; move them there if the
    // phone may be rooted or shared.
    let cache = Cache::new(Some(&cache_dir), Some(&cache_dir), None, None)?;
    let credentials = match token {
        Some(token) => Credentials::with_access_token(token),
        None => cache
            .credentials()
            .ok_or_else(|| anyhow::anyhow!("not signed in for playback"))?,
    };
    output::OUTPUT.set_target(vm, out);
    if let Some(old) = SPIRC.lock().unwrap_or_else(|p| p.into_inner()).take() {
        let _ = old.shutdown();
    }
    RUNTIME.block_on(async move {
        // librespot picks Spotify's Android client ID when built for Android,
        // but the playback grant is issued to the desktop client: login5 then
        // refuses it with INVALID_CREDENTIALS. A stable device ID, as on the
        // desktop, keeps the saved credentials and the Connect device the same.
        let (bitrate, normalisation, gapless, autoplay) = *OPTIONS.lock().unwrap_or_else(|p| p.into_inner());
        let session_config = SessionConfig {
            client_id: PLAYBACK_CLIENT_ID.to_string(),
            device_id: device_id(&device_name),
            autoplay: Some(autoplay),
            ..SessionConfig::default()
        };
        let session = Session::new(session_config, Some(cache));
        let mixer: std::sync::Arc<dyn Mixer> = std::sync::Arc::new(volume::AppMixer);
        let player_config = PlayerConfig {
            bitrate: match bitrate {
                96 => Bitrate::Bitrate96,
                160 => Bitrate::Bitrate160,
                _ => Bitrate::Bitrate320,
            },
            normalisation,
            gapless,
            position_update_interval: Some(std::time::Duration::from_secs(1)),
            ..PlayerConfig::default()
        };
        let player = Player::new(
            player_config,
            session.clone(),
            mixer.get_soft_volume(),
            || Box::new(AndroidSink),
        );
        tokio::spawn(follow(player.get_player_event_channel()));
        *PLAYER.lock().unwrap_or_else(|p| p.into_inner()) = Some(player.clone());
        let config = ConnectConfig {
            name: device_name,
            device_type: DeviceType::Smartphone,
            // The app's slider, which Kotlin sets before connecting. Spotify
            // Connect sees it, so the PC app shows and moves it too.
            initial_volume: volume::VOLUME.load(std::sync::atomic::Ordering::Relaxed),
            disable_volume: false,
            ..ConnectConfig::default()
        };
        let (spirc, task) = Spirc::new(config, session.clone(), credentials, player, mixer).await?;
        *SESSION.lock().unwrap_or_else(|p| p.into_inner()) = Some(session);
        *SPIRC.lock().unwrap_or_else(|p| p.into_inner()) = Some(spirc);
        state().connected = true;
        tokio::spawn(async move {
            task.await;
            let mut state = state();
            state.connected = false;
            state.playing = false;
        });
        anyhow::Ok(())
    })
}

async fn follow(mut events: tokio::sync::mpsc::UnboundedReceiver<PlayerEvent>) {
    while let Some(event) = events.recv().await {
        if matches!(event, PlayerEvent::TrackChanged { .. }) {
            // Keep the old title until the crossfade's midpoint.
            let hold = output::HOLD_MS.swap(0, std::sync::atomic::Ordering::Relaxed);
            if hold > 0 {
                tokio::time::sleep(std::time::Duration::from_millis(hold)).await;
            }
        }
        let mut state = state();
        match event {
            PlayerEvent::TrackChanged { audio_item } => {
                state.uri = audio_item.uri.clone();
                state.name = audio_item.name.clone();
                state.duration_ms = audio_item.duration_ms;
                state.artists = match &audio_item.unique_fields {
                    UniqueFields::Track { artists, .. } => artists
                        .iter()
                        .map(|artist| artist.name.as_str())
                        .collect::<Vec<_>>()
                        .join(", "),
                    UniqueFields::Local { artists, .. } => artists.clone().unwrap_or_default(),
                    UniqueFields::Episode { show_name, .. } => show_name.clone(),
                };
            }
            PlayerEvent::Unavailable { .. } => state.error = "Spotify says this song can't be played on this account.".into(),
            PlayerEvent::AudioKeyUnavailable { .. } => state.error = "Spotify refused the key to decrypt this song.".into(),
            PlayerEvent::Playing { position_ms, .. }
            | PlayerEvent::PositionChanged { position_ms, .. }
            | PlayerEvent::PositionCorrection { position_ms, .. }
            | PlayerEvent::Seeked { position_ms, .. } => {
                let playing = state.playing || matches!(event, PlayerEvent::Playing { .. });
                if playing {
                    state.error.clear();
                }
                state.at(position_ms, playing);
            }
            PlayerEvent::Paused { position_ms, .. } => {
                // Let the bars fall rather than freeze on the last sound.
                vis::clear();
                state.at(position_ms, false)
            }
            PlayerEvent::Loading { position_ms, .. } => state.at(position_ms, false),
            PlayerEvent::Stopped { .. } => {
                vis::clear();
                state.playing = false;
            }
            PlayerEvent::EndOfTrack { .. } => state.playing = false,
            PlayerEvent::ShuffleChanged { shuffle } => state.shuffle = shuffle,
            PlayerEvent::RepeatChanged { context, track } => {
                state.repeat_context = context;
                state.repeat_track = track;
            }
            _ => {}
        }
    }
}

fn spirc(command: impl FnOnce(&Spirc) -> Result<(), librespot_core::Error>) {
    if let Some(spirc) = SPIRC.lock().unwrap_or_else(|p| p.into_inner()).as_ref()
        && let Err(error) = command(spirc)
    {
        log::warn!("playback command failed: {error}");
    }
}

fn string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    if value.is_null() {
        return None;
    }
    env.get_string(value).ok().map(Into::into)
}

/// Logs to logcat and keeps librespot's latest warning or error for the app to
/// show: a song that never starts otherwise fails silently on the phone.
struct Logger {
    #[cfg(target_os = "android")]
    android: android_logger::AndroidLogger,
}

impl log::Log for Logger {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        // Debug too, for the copyable log; logcat keeps its own Info filter.
        metadata.level() <= log::Level::Debug
    }

    fn log(&self, record: &log::Record) {
        #[cfg(target_os = "android")]
        log::Log::log(&self.android, record);
        if record.target().starts_with("librespot") || record.target().starts_with("spotifast") {
            let mut lines = LOG.lock().unwrap_or_else(|p| p.into_inner());
            if lines.len() >= 1500 {
                lines.pop_front();
            }
            let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() % 86_400);
            lines.push_back(format!(
                "{:02}:{:02}:{:02} {} {}: {}",
                secs / 3600, secs / 60 % 60, secs % 60, record.level(), record.target(), record.args()
            ));
        }
        // Only a refused audio key stops a song for good; librespot's other
        // warnings (dropped websockets, missing context info) it recovers from.
        if record.level() <= log::Level::Warn && record.target().ends_with("audio_key") {
            // try_lock: the state's own holders may log, and a missed message beats a deadlock.
            if let Ok(mut state) = STATE.try_lock() {
                state.error = record.args().to_string();
            }
        }
    }

    fn flush(&self) {}
}

/// The latest log lines, for Settings' "Copy playback log": phones don't let
/// a listener read logcat.
static LOG: Mutex<std::collections::VecDeque<String>> = Mutex::new(std::collections::VecDeque::new());

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_log<'a>(env: JNIEnv<'a>, _this: JObject<'a>) -> jstring {
    let text = LOG.lock().unwrap_or_else(|p| p.into_inner()).iter().cloned().collect::<Vec<_>>().join("\n");
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn init_logging() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let logger = Logger {
            #[cfg(target_os = "android")]
            android: android_logger::AndroidLogger::new(
                android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("spotifast"),
            ),
        };
        if log::set_logger(Box::leak(Box::new(logger))).is_ok() {
            log::set_max_level(log::LevelFilter::Debug);
        }
    });
}

/// A panic must not unwind into the JVM.
fn guard<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(body)).unwrap_or_else(|_| {
        log::error!("native panic");
        fallback
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_connect<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    out: JObject<'a>,
    cache_dir: JString<'a>,
    token: JString<'a>,
    device_name: JString<'a>,
) -> jstring {
    init_logging();
    let error = guard(Some("native panic".to_string()), || {
        let vm = env.get_java_vm().ok()?;
        let out = env.new_global_ref(&out).ok()?;
        let cache_dir = string(&mut env, &cache_dir)?;
        let token = string(&mut env, &token);
        let device_name = string(&mut env, &device_name).unwrap_or_else(|| "Spotifast".into());
        connect(vm, out, cache_dir, token, device_name)
            .err()
            .map(|error| format!("{error:#}"))
    });
    match error {
        None => JObject::null().into_raw(),
        Some(error) => {
            log::error!("connect failed: {error}");
            env.new_string(error)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
    }
}

/// Plays `context` (a playlist or Liked Songs) from `track`, or from the top.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_load<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    context: JString<'a>,
    track: JString<'a>,
    shuffle: jint,
) {
    let Some(context) = string(&mut env, &context) else {
        return;
    };
    let track = string(&mut env, &track);
    guard((), || {
        // As on the desktop: librespot resolves the whole playlist before it
        // fetches the song, so fetch the song now and the load picks it up.
        // A single song plays as its own context, with no track named.
        if let Some(uri) = track.as_deref().or(Some(context.as_str())).filter(|t| t.starts_with("spotify:track:"))
            && let Ok(uri) = librespot_core::SpotifyUri::from_uri(uri)
            && let Some(player) = PLAYER.lock().unwrap_or_else(|p| p.into_inner()).as_ref()
        {
            player.preload(uri);
        }
        spirc(|spirc| {
            spirc.activate()?;
            spirc.load(LoadRequest::from_context_uri(
                context,
                LoadRequestOptions {
                    start_playing: true,
                    playing_track: track.map(PlayingTrack::Uri),
                    // -1 keeps the current shuffle; 0 or 1 starts the playlist with it off or on.
                    context_options: (shuffle >= 0).then(|| {
                        LoadContextOptions::Options(librespot_connect::Options {
                            shuffle: shuffle == 1,
                            ..Default::default()
                        })
                    }),
                    ..LoadRequestOptions::default()
                },
            ))
        })
    });
}

/// The playlist's song URIs, kept for the pages after the first.
static PLAYLIST: Mutex<Option<(String, Vec<librespot_core::SpotifyUri>)>> = Mutex::new(None);

/// A page of a playlist's songs read through the playback session, shaped like
/// the Web API's playlist items. Spotify's Web API only lists the songs of
/// playlists the listener owns, so other people's playlists come from here.
async fn playlist_page(session: Session, uri: String, offset: usize, count: usize) -> anyhow::Result<serde_json::Value> {
    use librespot_metadata::{Metadata, Playlist, Track};
    let cached = PLAYLIST.lock().unwrap_or_else(|p| p.into_inner()).clone().filter(|(u, _)| *u == uri);
    let tracks = match cached {
        Some((_, tracks)) => tracks,
        None => {
            let id = librespot_core::SpotifyUri::from_uri(&uri)?;
            let tracks: Vec<_> = Playlist::get(&session, &id).await?.tracks().cloned().collect();
            *PLAYLIST.lock().unwrap_or_else(|p| p.into_inner()) = Some((uri, tracks.clone()));
            tracks
        }
    };
    let mut set = tokio::task::JoinSet::new();
    for (i, id) in tracks.iter().enumerate().skip(offset).take(count) {
        let (session, id) = (session.clone(), id.clone());
        set.spawn(async move { (i, Track::get(&session, &id).await.ok()) });
    }
    let mut found = Vec::new();
    while let Some(joined) = set.join_next().await {
        // A song Spotify won't describe is left out rather than ending the page.
        let Ok((i, Some(track))) = joined else { continue };
        let mut covers: Vec<_> = track.album.covers.iter().collect();
        covers.sort_by_key(|c| std::cmp::Reverse(c.width));
        let images: Vec<_> = covers
            .iter()
            .map(|c| serde_json::json!({ "url": format!("https://i.scdn.co/image/{}", c.id.to_base16()) }))
            .collect();
        let artists: Vec<_> = track.artists.iter().map(|a| serde_json::json!({ "name": a.name })).collect();
        found.push((i, serde_json::json!({ "item": {
            "uri": track.id.to_uri(),
            "name": track.name,
            "duration_ms": track.duration,
            "artists": artists,
            "album": { "name": track.album.name, "uri": track.album.id.to_uri(), "images": images },
        }})));
    }
    found.sort_by_key(|(i, _)| *i);
    let items: Vec<_> = found.into_iter().map(|(_, item)| item).collect();
    Ok(serde_json::json!({ "total": tracks.len(), "items": items }))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_playlistPage<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    uri: JString<'a>,
    offset: jint,
    count: jint,
) -> jstring {
    let uri = string(&mut env, &uri).unwrap_or_default();
    let json = guard(String::from(r#"{"error":"crashed"}"#), || {
        let Some(session) = SESSION.lock().unwrap_or_else(|p| p.into_inner()).clone() else {
            return r#"{"error":"not connected"}"#.to_string();
        };
        match RUNTIME.block_on(playlist_page(session, uri, offset.max(0) as usize, count.max(0) as usize)) {
            Ok(page) => page.to_string(),
            Err(error) => serde_json::json!({ "error": error.to_string() }).to_string(),
        }
    });
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Adds a song to Spotify's queue: it plays after the current song.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_queue<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    uri: JString<'a>,
) {
    let Some(uri) = string(&mut env, &uri) else {
        return;
    };
    guard((), || {
        QUEUED.lock().unwrap_or_else(|p| p.into_inner()).push(uri.clone());
        spirc(|spirc| spirc.add_to_queue(librespot_core::SpotifyUri::from_uri(&uri)?))
    });
}

/// The waveform: `points` values from -1 to 1.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_scope<'a>(
    env: JNIEnv<'a>,
    _this: JObject<'a>,
    points: jint,
) -> jni::sys::jfloatArray {
    let points = points.clamp(1, 400) as usize;
    let line = guard(vec![0.0; points], || vis::scope(points));
    let Ok(array) = env.new_float_array(line.len() as i32) else {
        return std::ptr::null_mut();
    };
    let _ = env.set_float_array_region(&array, 0, &line);
    array.into_raw()
}

/// The visualizer's 75 bar heights then their 75 peak caps, 0 to 1.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_bands<'a>(
    env: JNIEnv<'a>,
    _this: JObject<'a>,
) -> jni::sys::jfloatArray {
    let bands = guard(vec![0.0; 2 * vis::WIDE_BANDS], vis::spectrum);
    let Ok(array) = env.new_float_array(bands.len() as i32) else {
        return std::ptr::null_mut();
    };
    let _ = env.set_float_array_region(&array, 0, &bands);
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_command(
    _env: JNIEnv,
    _this: JObject,
    command: jint,
    value: jint,
) {
    guard((), || {
        spirc(|spirc| match command {
            0 => spirc.play(),
            1 => spirc.pause(),
            2 => spirc.next(),
            3 => spirc.prev(),
            4 => spirc.set_position_ms(value.max(0) as u32),
            5 => spirc.shuffle(value != 0),
            // 0 off, 1 the playlist, 2 this song.
            6 => spirc
                .repeat(value == 1)
                .and_then(|()| spirc.repeat_track(value == 2)),
            _ => Ok(()),
        })
    });
}

/// The app's volume, 0 to 100. Through Spirc when connected, so Spotify
/// Connect hears of it; straight to the mixer's value otherwise.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_setVolume(
    _env: JNIEnv,
    _this: JObject,
    percent: jint,
) {
    guard((), || {
        let value = volume::percent_to_volume(percent);
        volume::VOLUME.store(value, std::sync::atomic::Ordering::Relaxed);
        if let Some(spirc) = SPIRC.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
            let _ = spirc.set_volume(value);
        }
    });
}

/// Crossfade length (0 for plain gapless), fades on skips, and whether
/// songs of one album stay seamless.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_setTransitions(
    _env: JNIEnv,
    _this: JObject,
    crossfade_ms: jint,
    smooth_skips: jboolean,
    albums_gapless: jboolean,
) {
    use std::sync::atomic::Ordering::Relaxed;
    output::CROSSFADE_MS.store(crossfade_ms.clamp(0, 12_000) as u32, Relaxed);
    output::SMOOTH_SKIPS.store(smooth_skips != 0, Relaxed);
    output::ALBUMS_GAPLESS.store(albums_gapless != 0, Relaxed);
}

/// Takes effect on the next connect.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_setPlayerOptions(
    _env: JNIEnv,
    _this: JObject,
    bitrate: jint,
    normalisation: jboolean,
    gapless: jboolean,
    autoplay: jboolean,
) {
    *OPTIONS.lock().unwrap_or_else(|p| p.into_inner()) = (bitrate, normalisation != 0, gapless != 0, autoplay != 0);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_setEffects(
    _env: JNIEnv,
    _this: JObject,
    on: jboolean,
    rate_percent: jint,
    bass_db: jfloat,
    reverb_mix: jfloat,
    reverb_room: jfloat,
) {
    let settings = effects::EffectsSettings {
        on: on != 0,
        rate_percent: rate_percent.clamp(0, i32::from(u16::MAX)) as u16,
        bass_db,
        reverb_mix,
        reverb_room,
    }
    .clamped();
    if let Ok(mut shared) = EFFECTS.lock() {
        *shared = settings;
    }
}

/// [next] with the songs queued here in front, until librespot lists them or one starts.
fn upcoming(queued: &mut Vec<String>, current: &str, next: Vec<String>) -> Vec<String> {
    queued.retain(|uri| uri != current && !next.contains(uri));
    queued.iter().cloned().chain(next).collect()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_state<'a>(
    env: JNIEnv<'a>,
    _this: JObject<'a>,
) -> jstring {
    let json = guard(String::from("{}"), || {
        // shortcut: only the first 100 upcoming songs are shown; enough for an "Up next" list.
        let next: Vec<String> = SPIRC
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
            .map(|spirc| {
                spirc
                    .watch_queue_list()
                    .borrow()
                    .next_tracks
                    .iter()
                    .take(100)
                    .cloned()
                    .collect()
            })
            .unwrap_or_default();
        let state = state();
        let next = upcoming(&mut QUEUED.lock().unwrap_or_else(|p| p.into_inner()), &state.uri, next);
        state.json(rate(), &next)
    });
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Spotify Connect and Google Cast receivers on the local network, as JSON.
/// Blocks for `timeout_ms`; Kotlin holds a multicast lock meanwhile.
fn receivers(timeout_ms: u64) -> anyhow::Result<String> {
    let found = zeroconf::discover(std::time::Duration::from_millis(timeout_ms))?;
    let found = zeroconf::resolve_receivers(found)?;
    let rows: Vec<serde_json::Value> = found
        .iter()
        .map(|r| {
            serde_json::json!({
                "name": r.name,
                "device_id": r.device_id.clone().unwrap_or_else(|| if r.cast { cast::device_id(&r.name) } else { String::new() }),
                "address": r.address.to_string(),
                "port": r.port,
                "path": r.path,
                "cast": r.cast,
            })
        })
        .collect();
    Ok(serde_json::Value::Array(rows).to_string())
}

/// Starts Spotify on a receiver from [`receivers`] and signs it in with this
/// session's account, as the desktop's `activate_cast` and `activate_receiver`
/// do. It then joins Spotify Connect and can be transferred to.
fn activate(json: &str) -> anyhow::Result<()> {
    let row: serde_json::Value = serde_json::from_str(json)?;
    let text = |key: &str| row[key].as_str().unwrap_or_default().to_string();
    let receiver = zeroconf::Receiver {
        name: text("name"),
        device_id: Some(text("device_id")).filter(|id| !id.is_empty()),
        address: text("address").parse()?,
        port: row["port"].as_u64().unwrap_or_default() as u16,
        path: text("path"),
        cast: row["cast"].as_bool().unwrap_or_default(),
    };
    let session = SESSION
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
        .ok_or_else(|| {
            anyhow::anyhow!(
                "Play something on this phone first, so there is an account to hand over"
            )
        })?;
    if receiver.cast {
        return cast::start(
            receiver.address,
            receiver.port,
            &receiver.name,
            |client, device| RUNTIME.block_on(cast::device_token(&session, client, device)),
        );
    }
    let stored = session
        .cache()
        .and_then(|cache| cache.credentials())
        .ok_or_else(|| anyhow::anyhow!("no saved playback sign-in to hand over"))?;
    let http = reqwest::blocking::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(8))
        .build()?;
    let info = zeroconf::get_info(&http, &receiver)?;
    zeroconf::add_user(
        &http,
        &receiver,
        &info,
        &zeroconf::Credentials::from_playback(&stored)?,
        "Spotifast Custom",
    )
}

fn java_string(env: &JNIEnv, text: Option<String>) -> jstring {
    match text {
        None => JObject::null().into_raw(),
        Some(text) => env
            .new_string(text)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

/// JSON array of receivers, or `{"error": ...}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_receivers<'a>(
    env: JNIEnv<'a>,
    _this: JObject<'a>,
    timeout_ms: jint,
) -> jstring {
    let json = guard(None, || {
        Some(receivers(timeout_ms.max(0) as u64).unwrap_or_else(|error| {
            serde_json::json!({ "error": format!("{error:#}") }).to_string()
        }))
    });
    java_string(&env, json)
}

/// Returns null once the receiver is signed in, or why it failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_itsmikefan_spotifastcustom_Core_activate<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    receiver: JString<'a>,
) -> jstring {
    let Some(receiver) = string(&mut env, &receiver) else {
        return JObject::null().into_raw();
    };
    let error = guard(Some("native panic".to_string()), || {
        activate(&receiver).err().map(|error| format!("{error:#}"))
    });
    java_string(&env, error)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn songs_queued_here_show_until_librespot_lists_them() {
        let mut queued = vec!["q".to_string()];
        let s = |v: &[&str]| v.iter().map(|x| x.to_string()).collect::<Vec<_>>();
        assert_eq!(upcoming(&mut queued, "now", s(&["a"])), s(&["q", "a"]));
        assert_eq!(upcoming(&mut queued, "now", s(&["q", "a"])), s(&["q", "a"]));
        assert!(queued.is_empty(), "listed by librespot, so no longer added");
        let mut playing = vec!["q".to_string()];
        assert_eq!(upcoming(&mut playing, "q", s(&["a"])), s(&["a"]));
    }

    #[test]
    fn position_moves_only_while_playing_and_stops_at_the_end() {
        let mut state = State {
            duration_ms: 1_000,
            ..State::default()
        };
        state.position_ms = 400;
        state.at = Some(Instant::now() - std::time::Duration::from_millis(300));
        assert_eq!(state.position_now(1.0), 400, "paused stays put");
        state.playing = true;
        assert!((690..800).contains(&state.position_now(1.0)));
        assert_eq!(state.position_now(10.0), 1_000, "never past the end");
    }
}
