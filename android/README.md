# Spotifast Custom for Android

A phone version of this Spotifast: your playlists and Liked Songs, playback
through librespot with the desktop's rate, bass and reverb effects, and
background playback with lock-screen and headset controls.

- `core/` is the Rust side: librespot and Spotify Connect, with
  `src/effects.rs`, `eq.rs`, `limiter.rs` and `resample.rs` from the desktop
  app compiled in as they are.
- `app/` is the Kotlin and Jetpack Compose app.

## Install

Every push that touches `android/` builds the APK in GitHub Actions and puts
it on the **android-latest** prerelease. Open that release on the phone,
download `spotifast-custom.apk` and install it (allow installs from your
browser when Android asks). Later builds install over it and keep the
sign-in.

## Sign in

1. Enter the Client ID of your own Spotify app, the one the desktop app
   uses. Its redirect URIs must include `http://127.0.0.1:8989/login`.
2. Allow playback once. Spotify Premium is needed to play.
