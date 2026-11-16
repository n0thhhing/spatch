//! Offline example data for the standalone preview APK (feature `preview`).

use crate::spotify::library::{Item, Kind};
use crate::spotify::player::{Command, NextTrack, NowPlaying};
use crate::spotify::playlist::Track;

const TRACKS: [(&str, &str, &str, i64); 5] = [
    ("Glasshouse", "Northbound", "Between Stations", 221_000),
    ("Daybreak Radio", "Hana Vale", "First Light", 183_000),
    ("Blue Hour", "Low Tide", "Night Swim", 246_000),
    ("Signal Fires", "June Static", "Day Shift", 208_000),
    ("Soft Landing", "Northbound", "Between Stations", 197_000),
];

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |t| t.as_millis() as u64)
}

fn index_of(uri: &str) -> usize {
    uri.rsplit(':').next().and_then(|v| v.parse::<usize>().ok()).unwrap_or(0) % TRACKS.len()
}

fn next_track(i: usize) -> NextTrack {
    NextTrack {
        title: TRACKS[i].0.into(),
        artist: TRACKS[i].1.into(),
        image: format!("demo:{i}"),
        uri: format!("spotify:preview:{i}"),
        uid: format!("preview-{i}"),
    }
}

fn track(i: usize, playing: bool) -> NowPlaying {
    let (title, artist, album, duration_ms) = TRACKS[i];
    NowPlaying {
        uri: format!("spotify:preview:{i}"),
        title: title.into(),
        artist: artist.into(),
        album: album.into(),
        source: "Preview mix".into(),
        context: "spotify:preview:collection".into(),
        image: format!("demo:{i}"),
        playing,
        duration_ms,
        timestamp: now_ms(),
        up_next: (1..TRACKS.len()).map(|o| next_track((i + o) % TRACKS.len())).collect(),
        ..Default::default()
    }
}

pub fn now_playing() -> NowPlaying {
    NowPlaying { position_ms: 56_000, ..track(0, true) }
}

pub fn playlist() -> Vec<Track> {
    (0..40)
        .map(|n| {
            let i = n % TRACKS.len();
            Track { title: TRACKS[i].0.into(), artist: TRACKS[i].1.into(), uri: format!("spotify:preview:{i}"), image: format!("demo:{i}") }
        })
        .collect()
}

pub fn library() -> Vec<Item> {
    let groups: [(Kind, &str, [&str; 5]); 4] = [
        (Kind::Playlist, "Playlist", ["Liked Songs", "After Hours", "Slow Motion", "Wildflower", "On the Road"]),
        (Kind::Album, "Album", ["Between Stations", "First Light", "Night Swim", "Day Shift", "Soft Landing"]),
        (Kind::Artist, "Artist", ["Northbound", "Hana Vale", "Low Tide", "June Static", "Aster Echo"]),
        (Kind::Podcast, "Podcast", ["The Listening Room", "On Record", "Open Frequency", "Inside the Mix", "Track Notes"]),
    ];
    groups
        .into_iter()
        .enumerate()
        .flat_map(|(group, (kind, subtitle, names))| {
            names.into_iter().enumerate().map(move |(i, name)| Item {
                name: name.into(),
                kind,
                subtitle: subtitle.into(),
                uri: if group == 0 && i == 0 { "spotify:preview:collection".into() } else { format!("spotify:preview:{i}") },
                image: format!("demo:{}", i + group * 5),
            })
        })
        .collect()
}

/// Plays a command against the demo player, as the real one would answer it.
pub fn apply(now: &mut NowPlaying, command: Command) {
    let here = index_of(&now.uri);
    let (shuffle, repeat) = (now.shuffle, now.repeat);
    let at = now.position_at(now_ms());
    let keeps_options = matches!(command, Command::Shuffle(_) | Command::Repeat(_) | Command::AddToQueue(_));
    match command {
        Command::Pause | Command::Resume => {
            now.position_ms = at;
            now.timestamp = now_ms();
            now.playing = matches!(command, Command::Resume);
        }
        Command::Play(uri) => *now = track(index_of(&uri), true),
        Command::Next => *now = track((here + 1) % TRACKS.len(), now.playing),
        Command::Previous => *now = track((here + TRACKS.len() - 1) % TRACKS.len(), now.playing),
        Command::SkipTo { uri, .. } => *now = track(index_of(&uri), now.playing),
        Command::SeekTo(ms) => {
            now.position_ms = ms.clamp(0, now.duration_ms);
            now.timestamp = now_ms();
        }
        Command::Shuffle(on) => now.shuffle = on,
        Command::Repeat(mode) => now.repeat = mode,
        Command::AddToQueue(uri) => now.up_next.insert(0, next_track(index_of(&uri))),
    }
    if !keeps_options {
        now.shuffle = shuffle;
        now.repeat = repeat;
    }
}
