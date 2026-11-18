//! Finds a song Spotify no longer plays on YouTube and saves it as an MP3
//! in the localsongs folder, where it stands in for the Spotify song.
//!
//! The work is done by yt-dlp and ffmpeg, which Spotifast downloads into
//! the `tools` folder beside the app once the listener agrees. Everything
//! here waits on the network, the disk or those programs, so it runs on the
//! backend's blocking threads, never on the UI thread.

use std::path::{Path, PathBuf};
use std::process::Command;

/// One YouTube video a search found.
#[derive(Clone, Debug, PartialEq)]
pub struct Candidate {
    pub id: String,
    pub title: String,
    pub channel: String,
    pub duration_ms: Option<u32>,
}

/// What the dialog shows for a search: a match close enough to download
/// at once, or the best few to choose from.
#[derive(Clone, Debug, PartialEq)]
pub enum Found {
    Exact(Candidate),
    Choices(Vec<Candidate>),
}

/// The `tools` folder beside the app.
pub fn tools_dir() -> PathBuf {
    crate::local_songs::dir()
        .parent()
        .map(Path::to_path_buf)
        .unwrap_or_default()
        .join("tools")
}

fn exe(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_string()
    }
}

fn yt_dlp() -> PathBuf {
    let bundled = tools_dir().join(exe("yt-dlp"));
    if bundled.exists() {
        bundled
    } else {
        PathBuf::from(exe("yt-dlp"))
    }
}

/// Whether yt-dlp and ffmpeg are in the tools folder.
pub fn tools_ready() -> bool {
    let tools = tools_dir();
    tools.join(exe("yt-dlp")).exists() && tools.join(exe("ffmpeg")).exists()
}

/// A command that opens no console window on Windows.
fn quiet(program: &Path) -> Command {
    #[allow(unused_mut)]
    let mut command = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}

fn run(command: &mut Command) -> Result<String, String> {
    let output = command
        .output()
        .map_err(|error| format!("Couldn't start yt-dlp: {error}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let last = stderr.lines().rev().find(|line| !line.trim().is_empty());
        return Err(last.unwrap_or("yt-dlp failed").trim().to_string());
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

const YT_DLP_URL: &str = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
const FFMPEG_URL: &str = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

/// Downloads yt-dlp and ffmpeg into the tools folder.
#[cfg(windows)]
pub fn install_tools() -> Result<(), String> {
    let tools = tools_dir();
    std::fs::create_dir_all(&tools).map_err(|error| error.to_string())?;
    let http = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(600))
        .build()
        .map_err(|error| error.to_string())?;
    let fetch = |url: &str, to: &Path| -> Result<(), String> {
        let bytes = http
            .get(url)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .and_then(reqwest::blocking::Response::bytes)
            .map_err(|error| format!("Couldn't download {url}: {error}"))?;
        let partial = to.with_extension("part");
        std::fs::write(&partial, &bytes).map_err(|error| error.to_string())?;
        std::fs::rename(&partial, to).map_err(|error| error.to_string())
    };
    if !tools.join("yt-dlp.exe").exists() {
        fetch(YT_DLP_URL, &tools.join("yt-dlp.exe"))?;
    }
    if !tools.join("ffmpeg.exe").exists() {
        let zip = tools.join("ffmpeg.zip");
        fetch(FFMPEG_URL, &zip)?;
        let unpacked = tools.join("ffmpeg-unpacked");
        let _ = std::fs::remove_dir_all(&unpacked);
        std::fs::create_dir_all(&unpacked).map_err(|error| error.to_string())?;
        // Windows 10 and later ship bsdtar, which reads zip files.
        let tar =
            PathBuf::from(std::env::var_os("SystemRoot").unwrap_or_else(|| "C:\\Windows".into()))
                .join("System32")
                .join("tar.exe");
        let status = quiet(&tar)
            .arg("-xf")
            .arg(&zip)
            .arg("-C")
            .arg(&unpacked)
            .status()
            .map_err(|error| format!("Couldn't unpack ffmpeg: {error}"))?;
        if !status.success() {
            return Err("Couldn't unpack ffmpeg".to_string());
        }
        for name in ["ffmpeg.exe", "ffprobe.exe"] {
            let found =
                find_file(&unpacked, name).ok_or("The ffmpeg download holds no ffmpeg.exe")?;
            std::fs::rename(found, tools.join(name)).map_err(|error| error.to_string())?;
        }
        let _ = std::fs::remove_dir_all(&unpacked);
        let _ = std::fs::remove_file(&zip);
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn install_tools() -> Result<(), String> {
    Err("Install yt-dlp and ffmpeg with your package manager, then try again.".to_string())
}

#[cfg(windows)]
fn find_file(folder: &Path, name: &str) -> Option<PathBuf> {
    for entry in std::fs::read_dir(folder).ok()?.flatten() {
        let path = entry.path();
        if path.is_dir() {
            if let Some(found) = find_file(&path, name) {
                return Some(found);
            }
        } else if path
            .file_name()
            .is_some_and(|file| file.eq_ignore_ascii_case(name))
        {
            return Some(path);
        }
    }
    None
}

/// Searches YouTube for `artist title` and judges the results against the
/// song's length.
pub fn search(title: &str, artist: &str, duration_ms: u32) -> Result<Found, String> {
    let output = run(quiet(&yt_dlp())
        .args(["--flat-playlist", "--no-warnings", "--encoding", "utf-8"])
        .args(["--print", "%(id)s\t%(duration)s\t%(channel)s\t%(title)s"])
        .arg(format!("ytsearch8:{artist} {title}")))?;
    let candidates: Vec<Candidate> = output.lines().filter_map(parse_line).collect();
    if candidates.is_empty() {
        return Err("YouTube found nothing for this song.".to_string());
    }
    Ok(judge(title, artist, duration_ms, candidates))
}

fn parse_line(line: &str) -> Option<Candidate> {
    let mut fields = line.splitn(4, '\t');
    let id = fields.next()?.trim().to_string();
    let duration = fields.next()?.trim();
    let channel = fields.next()?.trim().to_string();
    let title = fields.next()?.trim().to_string();
    if id.is_empty() || title.is_empty() {
        return None;
    }
    Some(Candidate {
        id,
        title,
        channel: if channel == "NA" {
            String::new()
        } else {
            channel
        },
        duration_ms: duration
            .parse::<f64>()
            .ok()
            .map(|seconds| (seconds * 1000.0) as u32),
    })
}

/// Words that say nothing about which recording a video holds.
const NOISE: &[&str] = &[
    "official",
    "oficial",
    "video",
    "videoclip",
    "clip",
    "audio",
    "lyrics",
    "lyric",
    "versuri",
    "hd",
    "hq",
    "4k",
    "music",
    "topic",
    "ft",
    "feat",
    "featuring",
    "the",
    "and",
    "si",
];

/// Lowercase words without accents, punctuation, or [`NOISE`].
fn words(text: &str) -> Vec<String> {
    let plain: String = text
        .to_lowercase()
        .chars()
        .map(|c| match c {
            'ă' | 'â' | 'à' | 'á' | 'ä' | 'ã' | 'å' => 'a',
            'î' | 'ì' | 'í' | 'ï' => 'i',
            'ș' | 'ş' | 'ś' | 'š' => 's',
            'ț' | 'ţ' => 't',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'ó' | 'ò' | 'ô' | 'ö' | 'õ' => 'o',
            'ú' | 'ù' | 'û' | 'ü' => 'u',
            'ç' | 'ć' | 'č' => 'c',
            'ñ' => 'n',
            c if c.is_alphanumeric() => c,
            _ => ' ',
        })
        .collect();
    plain
        .split_whitespace()
        .filter(|word| !NOISE.contains(word))
        .map(str::to_string)
        .collect()
}

/// The share of `wanted`'s words that `held` contains.
fn coverage(wanted: &[String], held: &[String]) -> f32 {
    if wanted.is_empty() {
        return 1.0;
    }
    let found = wanted.iter().filter(|word| held.contains(word)).count();
    found as f32 / wanted.len() as f32
}

/// An exact match has every word of the title and of the lead artist in the
/// video's title or channel, and a length within 5 seconds of the song's.
/// Otherwise the three best by words and length are offered.
pub fn judge(title: &str, artist: &str, duration_ms: u32, candidates: Vec<Candidate>) -> Found {
    let title_words = words(title);
    let artist_words = words(artist);
    let score = |candidate: &Candidate| -> (f32, bool) {
        let held = words(&format!("{} {}", candidate.title, candidate.channel));
        let title_share = coverage(&title_words, &held);
        let artist_share = coverage(&artist_words, &held);
        let gap = candidate
            .duration_ms
            .map(|ms| ms.abs_diff(duration_ms))
            .unwrap_or(u32::MAX);
        let exact = title_share == 1.0 && artist_share == 1.0 && gap <= 5_000;
        let closeness = 1.0 / (1.0 + gap as f32 / 10_000.0);
        (title_share * 2.0 + artist_share + closeness, exact)
    };
    if let Some(exact) = candidates.iter().find(|candidate| score(candidate).1) {
        return Found::Exact(exact.clone());
    }
    let mut ranked: Vec<(f32, Candidate)> = candidates
        .into_iter()
        .map(|candidate| (score(&candidate).0, candidate))
        .collect();
    ranked.sort_by(|a, b| b.0.total_cmp(&a.0));
    Found::Choices(ranked.into_iter().take(3).map(|(_, c)| c).collect())
}

/// Downloads the video `id` as an MP3 into `folder`, tagged with the
/// Spotify song's title, artist and album so it shows and plays as that
/// song, and answers with the file.
pub fn download(
    id: &str,
    folder: &Path,
    title: &str,
    artist: &str,
    album: &str,
) -> Result<PathBuf, String> {
    std::fs::create_dir_all(folder).map_err(|error| error.to_string())?;
    let name = file_name(&if artist.is_empty() {
        title.to_string()
    } else {
        format!("{artist} - {title}")
    });
    let template = folder.join(format!("{name}.%(ext)s"));
    let output = run(quiet(&yt_dlp())
        .args(["--no-playlist", "--no-warnings", "--encoding", "utf-8"])
        .args(["-x", "--audio-format", "mp3", "--audio-quality", "0"])
        .arg("--ffmpeg-location")
        .arg(tools_dir())
        .args(["--print", "after_move:filepath", "-o"])
        .arg(template)
        .arg(format!("https://www.youtube.com/watch?v={id}")))?;
    let file = output
        .lines()
        .rev()
        .find(|line| !line.trim().is_empty())
        .map(|line| PathBuf::from(line.trim()))
        .filter(|path| path.exists())
        .unwrap_or_else(|| folder.join(format!("{name}.mp3")));
    retag(&file, title, artist, album).map_err(|error| error.to_string())?;
    Ok(file)
}

/// `text` without the characters Windows refuses in a file name.
fn file_name(text: &str) -> String {
    let cleaned: String = text
        .chars()
        .map(|c| {
            if "<>:\"/\\|?*".contains(c) || c.is_control() {
                '_'
            } else {
                c
            }
        })
        .collect();
    let trimmed = cleaned.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        "song".to_string()
    } else {
        trimmed.chars().take(120).collect()
    }
}

/// Replaces an MP3's ID3v2 tag with one naming the Spotify song.
fn retag(path: &Path, title: &str, artist: &str, album: &str) -> std::io::Result<()> {
    let audio = std::fs::read(path)?;
    let start = id3_len(&audio).min(audio.len());
    let mut tagged = crate::local_songs::id3_tag(title, artist, album);
    tagged.extend_from_slice(&audio[start..]);
    std::fs::write(path, tagged)
}

/// The length of the ID3v2 tag `audio` starts with, or 0.
fn id3_len(audio: &[u8]) -> usize {
    if audio.len() < 10 || !audio.starts_with(b"ID3") {
        return 0;
    }
    let size = audio[6..10]
        .iter()
        .fold(0usize, |size, byte| (size << 7) | usize::from(byte & 0x7f));
    let footer = if audio[5] & 0x10 != 0 { 10 } else { 0 };
    10 + size + footer
}

#[cfg(test)]
mod tests {
    use super::*;

    fn video(title: &str, channel: &str, seconds: u32) -> Candidate {
        Candidate {
            id: title.to_string(),
            title: title.to_string(),
            channel: channel.to_string(),
            duration_ms: Some(seconds * 1000),
        }
    }

    #[test]
    fn a_video_with_the_title_artist_and_length_is_exact() {
        let found = judge(
            "Aș Pleca Cu Tine-n Lume (Live)",
            "Ovidiu Rusu",
            496_000,
            vec![
                video("Ovidiu Rusu - As pleca cu tine-n lume", "Ovidiu Rusu", 200),
                video(
                    "Ovidiu Rusu - As pleca cu tine-n lume (LIVE) [Official Video]",
                    "Petrecerea Romanilor",
                    498,
                ),
            ],
        );
        assert_eq!(
            found,
            Found::Exact(video(
                "Ovidiu Rusu - As pleca cu tine-n lume (LIVE) [Official Video]",
                "Petrecerea Romanilor",
                498
            ))
        );
    }

    #[test]
    fn without_an_exact_match_the_three_closest_are_offered() {
        let found = judge(
            "Mami Ti-Am Promis - Live",
            "Tzanca Uraganu",
            883_000,
            vec![
                video("Something else entirely", "Nobody", 883),
                video("Tzanca Uraganu - Mami ti-am promis", "Tzanca Uraganu", 240),
                video("Tzanca Uraganu - Mami ti-am promis LIVE 2023", "Fan", 700),
                video("Mami ti-am promis", "Cover band", 240),
            ],
        );
        let Found::Choices(choices) = found else {
            panic!("no video is exact");
        };
        assert_eq!(choices.len(), 3);
        assert_eq!(
            choices[0].title,
            "Tzanca Uraganu - Mami ti-am promis LIVE 2023"
        );
        assert!(choices.iter().all(|c| c.title != "Something else entirely"));
    }

    #[test]
    fn a_search_line_reads_as_a_candidate() {
        assert_eq!(
            parse_line("abc123\t215.0\tSome Channel\tA Title\twith tab"),
            Some(Candidate {
                id: "abc123".into(),
                title: "A Title\twith tab".into(),
                channel: "Some Channel".into(),
                duration_ms: Some(215_000),
            })
        );
        assert_eq!(parse_line("abc\tNA\tNA\tTitle").unwrap().duration_ms, None);
    }

    #[test]
    fn retagging_replaces_the_old_tag() {
        let old = crate::local_songs::id3_tag("Old", "Someone", "");
        let mut file = old.clone();
        file.extend_from_slice(b"\xff\xfbaudio");
        assert_eq!(id3_len(&file), old.len());
        assert_eq!(id3_len(b"\xff\xfbaudio"), 0);
        assert_eq!(file_name("AC/DC: Back?"), "AC_DC_ Back_");
    }
}
