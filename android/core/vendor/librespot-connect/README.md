librespot-connect from crmne/librespot 3ba977f, vendored for the Android app's
Up next taps. The only change: `Spirc::skip_to(uri)` (search for "Spotifast"),
which moves straight to a song in the upcoming list without loading the songs
before it, as a Connect `skip_next` with a track does. Keep it to that when upgrading.
