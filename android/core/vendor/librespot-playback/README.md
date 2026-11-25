librespot-playback from crmne/librespot 3ba977f, vendored for the Android app's
crossfade. The only change: three default-empty `Sink` hooks
(`position`, `track_start`, `preloaded`) in `src/audio_backend/mod.rs`, called
from `src/player.rs` (search for "Spotifast"). Keep it to that when upgrading.
Also: Spirc's prefetch of upcoming tracks waits until the track being loaded
is in (`deferred_prefetch`), so a tapped song doesn't share a slow connection.
