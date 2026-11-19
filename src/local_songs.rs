//! Songs from this PC shown in Spotify playlists.
//!
//! Spotify's Web API cannot add a local file to a playlist, so Spotifast
//! keeps the list itself, on this computer only: `local-songs.json` in the
//! `localsongs` folder beside the app, which also holds the audio. A song
//! is either added to a playlist, after its Spotify songs, or stands in for
//! one Spotify no longer plays, at that song's place. The player plays it
//! as it plays any `spotify:local:` entry, from its tags and length.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::api::models::{Album, ArtistRef, Track};

const LOCAL_URI_PREFIX: &str = "spotify:local:";

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
pub struct LocalSongs {
    #[serde(default)]
    pub songs: Vec<LocalSong>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct LocalSong {
    /// The `spotify:playlist:` URI the song shows in.
    pub playlist: String,
    /// The `spotify:local:` URI the player knows the file by.
    pub uri: String,
    /// The unavailable Spotify song it stands in for, if any.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub replaces: Option<String>,
}

/// The `localsongs` folder beside the app.
pub fn dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(Path::to_path_buf))
        .unwrap_or_default()
        .join("localsongs")
}

pub fn list_file() -> PathBuf {
    dir().join("local-songs.json")
}

impl LocalSongs {
    /// The saved list; a missing or unreadable file is an empty one.
    pub fn load() -> Self {
        std::fs::read_to_string(list_file())
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default()
    }

    pub fn encode(&self) -> Option<String> {
        serde_json::to_string_pretty(self).ok()
    }

    pub fn in_playlist<'a>(&'a self, playlist: &'a str) -> impl Iterator<Item = &'a LocalSong> {
        self.songs
            .iter()
            .filter(move |song| song.playlist == playlist)
    }

    pub fn contains(&self, playlist: &str, uri: &str) -> bool {
        self.in_playlist(playlist).any(|song| song.uri == uri)
    }

    /// Adds `song`; a second stand-in for the same Spotify song replaces
    /// the first. Answers whether the list changed.
    pub fn add(&mut self, song: LocalSong) -> bool {
        if self.songs.contains(&song) {
            return false;
        }
        if song.replaces.is_some() {
            self.songs
                .retain(|held| held.playlist != song.playlist || held.replaces != song.replaces);
        }
        self.songs.push(song);
        true
    }

    /// Removes the local song `uri` from `playlist`.
    pub fn remove(&mut self, playlist: &str, uri: &str) -> bool {
        let before = self.songs.len();
        self.songs
            .retain(|song| song.playlist != playlist || song.uri != uri);
        self.songs.len() != before
    }
}

/// The row a `spotify:local:` URI shows: the title, artist, album and
/// length it carries.
pub fn track(uri: &str) -> Track {
    let mut parts = uri
        .strip_prefix(LOCAL_URI_PREFIX)
        .unwrap_or_default()
        .split(':')
        .map(decode);
    let artist = parts.next().unwrap_or_default();
    let album = parts.next().unwrap_or_default();
    let title = parts.next().unwrap_or_default();
    let seconds: u32 = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    Track {
        name: if title.is_empty() {
            "Local song".to_string()
        } else {
            title
        },
        uri: uri.to_string(),
        duration_ms: seconds.saturating_mul(1000),
        artists: (!artist.is_empty())
            .then(|| ArtistRef {
                name: artist,
                ..Default::default()
            })
            .into_iter()
            .collect(),
        album: Some(Album {
            name: album,
            ..Default::default()
        }),
        is_local: true,
        ..Default::default()
    }
}

/// One part of a `spotify:local:` URI, form-encoded by librespot.
fn decode(part: &str) -> String {
    percent_encoding::percent_decode_str(&part.replace('+', " "))
        .decode_utf8_lossy()
        .into_owned()
}

/// Copies `file` into `folder` unless a copy is already there, gives an
/// untagged MP3 a title from its file name, and answers with the `spotify:local:` URI the player will play it
/// by. Waits for the disk, so it never runs on the UI thread.
pub fn import(file: &Path, folder: &Path) -> Result<String, String> {
    let unreadable = || {
        format!(
            "Spotifast can't read the length of {}. Try an MP3 or FLAC file.",
            file.display()
        )
    };
    // The copy is read alone first: songs with the same tags and length
    // are one song to the player, so a second copy is not kept.
    let incoming = folder.join(".incoming");
    std::fs::create_dir_all(&incoming).map_err(|error| error.to_string())?;
    let staged = incoming.join(file.file_name().ok_or_else(unreadable)?);
    let result = (|| {
        std::fs::copy(file, &staged).map_err(|error| error.to_string())?;
        if is_mp3(&staged) {
            tag_if_untagged(&staged).map_err(|error| error.to_string())?;
        }
        let uri = uri_of(&incoming).ok_or_else(unreadable)?;
        let held =
            librespot_playback::local_file::create_local_file_lookup(&[folder.to_path_buf()])
                .iter()
                .any(|(known, path)| known.to_uri() == uri && !path.starts_with(&incoming));
        if !held {
            std::fs::rename(&staged, free_name(folder, file)).map_err(|error| error.to_string())?;
        }
        Ok(uri)
    })();
    let _ = std::fs::remove_dir_all(&incoming);
    result
}

/// The `spotify:local:` URI of the one song in `folder`.
fn uri_of(folder: &Path) -> Option<String> {
    librespot_playback::local_file::create_local_file_lookup(&[folder.to_path_buf()])
        .iter()
        .next()
        .map(|(uri, _)| uri.to_uri())
}

fn is_mp3(path: &Path) -> bool {
    path.extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| extension.eq_ignore_ascii_case("mp3"))
}

/// `folder/<file's name>`, numbered when that name is taken.
fn free_name(folder: &Path, file: &Path) -> PathBuf {
    let stem = file
        .file_stem()
        .map(|stem| stem.to_string_lossy().into_owned())
        .unwrap_or_else(|| "song".to_string());
    let extension = file
        .extension()
        .map(|extension| format!(".{}", extension.to_string_lossy()))
        .unwrap_or_default();
    let mut target = folder.join(format!("{stem}{extension}"));
    let mut number = 2;
    while target.exists() {
        target = folder.join(format!("{stem} ({number}){extension}"));
        number += 1;
    }
    target
}

/// The player finds a song only by its tags, so an MP3 without an ID3v2
/// tag gets one: "Artist - Title" file names give both, others the title.
pub fn tag_if_untagged(path: &Path) -> std::io::Result<()> {
    let audio = std::fs::read(path)?;
    if audio.starts_with(b"ID3") {
        return Ok(());
    }
    let stem = path
        .file_stem()
        .map(|stem| stem.to_string_lossy().into_owned())
        .unwrap_or_default();
    let (artist, title) = match stem.split_once(" - ") {
        Some((artist, title)) => (artist.trim(), title.trim()),
        None => ("", stem.trim()),
    };
    let mut tagged = id3_tag(title, artist, "", None);
    tagged.extend_from_slice(&audio);
    std::fs::write(path, tagged)
}

/// A minimal ID3v2.4 tag with UTF-8 title, artist and album frames, and
/// `cover` (JPEG or PNG bytes) as the front cover the app shows.
pub fn id3_tag(title: &str, artist: &str, album: &str, cover: Option<&[u8]>) -> Vec<u8> {
    let mut frames = Vec::new();
    for (id, text) in [(b"TIT2", title), (b"TPE1", artist), (b"TALB", album)] {
        if text.is_empty() {
            continue;
        }
        frames.extend_from_slice(id);
        frames.extend_from_slice(&syncsafe(text.len() as u32 + 1));
        frames.extend_from_slice(&[0, 0, 3]);
        frames.extend_from_slice(text.as_bytes());
    }
    if let Some(picture) = cover {
        let mime: &[u8] = if picture.starts_with(&[0x89, b'P', b'N', b'G']) {
            b"image/png"
        } else {
            b"image/jpeg"
        };
        // Encoding, MIME type, front cover, empty description, picture.
        let mut body = vec![0];
        body.extend_from_slice(mime);
        body.extend_from_slice(&[0, 3, 0]);
        body.extend_from_slice(picture);
        frames.extend_from_slice(b"APIC");
        frames.extend_from_slice(&syncsafe(body.len() as u32));
        frames.extend_from_slice(&[0, 0]);
        frames.extend_from_slice(&body);
    }
    let mut tag = b"ID3\x04\x00\x00".to_vec();
    tag.extend_from_slice(&syncsafe(frames.len() as u32));
    tag.extend_from_slice(&frames);
    tag
}

fn syncsafe(size: u32) -> [u8; 4] {
    [
        ((size >> 21) & 0x7f) as u8,
        ((size >> 14) & 0x7f) as u8,
        ((size >> 7) & 0x7f) as u8,
        (size & 0x7f) as u8,
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_local_uri_shows_its_title_artist_and_length() {
        let track = track("spotify:local:The+Artist:An%20Album:A+Song:215");
        assert_eq!(track.name, "A Song");
        assert_eq!(track.artists[0].name, "The Artist");
        assert_eq!(track.album.unwrap().name, "An Album");
        assert_eq!(track.duration_ms, 215_000);
        assert!(track.is_local);
    }

    #[test]
    fn a_stand_in_replaces_the_previous_one_for_the_same_song() {
        let mut songs = LocalSongs::default();
        let stand_in = |uri: &str| LocalSong {
            playlist: "spotify:playlist:p".into(),
            uri: uri.into(),
            replaces: Some("spotify:track:gone".into()),
        };
        assert!(songs.add(stand_in("spotify:local:a::one:1")));
        assert!(!songs.add(stand_in("spotify:local:a::one:1")));
        assert!(songs.add(stand_in("spotify:local:a::two:2")));
        assert_eq!(songs.songs.len(), 1);
        assert!(songs.remove("spotify:playlist:p", "spotify:local:a::two:2"));
        assert!(songs.songs.is_empty());
    }

    #[test]
    fn the_tag_holds_each_frame_with_its_syncsafe_size() {
        let tag = id3_tag("Song", "Artist", "", None);
        assert!(tag.starts_with(b"ID3\x04\x00\x00"));
        // 2 frames of 10 header bytes, an encoding byte and the text.
        assert_eq!(&tag[6..10], &[0, 0, 0, 10 + 5 + 10 + 7]);
        assert_eq!(&tag[10..14], b"TIT2");
        assert_eq!(&tag[20..25], b"\x03Song");
    }
}
