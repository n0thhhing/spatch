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
        queued: false,
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
        can_next: true,
        can_previous: true,
        album_uri: format!("spotify:album:{i}"),
        artist_uri: format!("spotify:artist:{i}"),
        ..Default::default()
    }
}

pub fn now_playing() -> NowPlaying {
    NowPlaying { position_ms: 56_000, ..track(0, true) }
}

/// What the core's offline index would say for a search: the demo songs whose title, artist or album match.
pub fn search(query: &str) -> Vec<crate::spotify::search::Hit> {
    let needle = query.trim().to_lowercase();
    TRACKS
        .iter()
        .enumerate()
        .filter(|(_, (title, artist, album, _))| {
            format!("{title} {artist} {album}").to_lowercase().contains(&needle)
        })
        .map(|(i, (title, artist, album, _))| crate::spotify::search::Hit {
            section: "Songs",
            title: (*title).into(),
            subtitle: format!("{artist} • {album}"),
            uri: format!("spotify:preview:{i}"),
            image: format!("demo:{i}"),
            category: -1,
        })
        .collect()
}

/// Catalog adapter for the preview bridge; reuses the same example tracks as offline search.
pub fn catalog(query: &str) -> Vec<String> {
    std::iter::once(String::new()).chain(search(query).into_iter().flat_map(|hit| [
        hit.title, hit.subtitle, hit.uri, hit.image, hit.category.to_string(), "0".into(),
    ])).collect()
}

pub fn playlist() -> Vec<Track> {
    (0..40)
        .map(|n| {
            let i = n % TRACKS.len();
            Track { title: TRACKS[i].0.into(), artist: TRACKS[i].1.into(), uri: format!("spotify:preview:{i}"), image: format!("demo:{i}"), duration_ms: TRACKS[i].3 as u32, added: 40 - n as u32,
                album: TRACKS[i].2.into(), album_uri: format!("spotify:album:{i}"), artist_uri: format!("spotify:artist:{i}"), explicit: i % 3 == 1, ..Default::default() }
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
                can_add: group == 0 && i != 0,
                subtitle: subtitle.into(),
                uri: match (group, i) {
                    (0, 0) => "spotify:preview:collection".into(),
                    (1, _) => format!("spotify:album:{i}"),
                    (2, _) => format!("spotify:artist:{i}"),
                    (3, _) => format!("spotify:show:{i}"),
                    _ => format!("spotify:playlist:demo-{i}"),
                },
                // Liked Songs has no cover in Spotify's library either.
                image: if (group, i) == (0, 0) { String::new() } else { format!("demo:{}", i + group * 5) },
            })
        })
        .collect()
}

/// An album or artist page for the demo: its tracks, and for an artist a few releases.
pub fn page(uri: &str) -> crate::spotify::metadata::Page {
    let artist = uri.contains("artist");
    crate::spotify::metadata::Page {
        name: if uri.contains("show") { "Open Frequency".into() } else if artist { "Northbound".into() } else { "Between Stations".into() },
        subtitle: if uri.contains("show") { "Radio Co".into() } else if artist { "Artist".into() } else { "Northbound • 2025".into() },
        image: "demo:0".into(),
        tracks: playlist().into_iter().take(8).collect(),
        releases: if artist {
            (0..5).map(|i| (TRACKS[i].2.to_string(), "Northbound • 2025".to_string(), format!("spotify:album:{i}"), format!("demo:{}", i + 5))).collect()
        } else {
            Vec::new()
        },
    }
}

pub fn devices() -> Vec<crate::spotify::connect::Device> {
    let device = |name: &str, kind: &str, id: &str, active, this_device| crate::spotify::connect::Device { name: name.into(), kind: kind.into(), id: id.into(), active, this_device };
    vec![device("This phone", "Smartphone", "phone", true, true), device("Kitchen speaker", "Speaker", "kitchen", false, false), device("Desktop", "Computer", "desktop", false, false)]
}

/// Spotify's recently played, as the demo imagines it: the library's playlists and albums, newest first.
pub fn recent() -> Vec<Item> {
    library().into_iter().filter(|i| matches!(i.kind, Kind::Playlist | Kind::Album)).rev().take(6).collect()
}

pub fn profile() -> crate::spotify::profile::Profile {
    crate::spotify::profile::Profile {
        display_name: "Preview Listener".into(),
        username: "preview".into(),
        image_url: "demo:0".into(),
        profile_uri: "spotify:user:preview".into(),
    }
}

pub fn track_from_entry(uri: &str, title: &str, artist: &str, image: &str) -> Track {
    let i = index_of(uri);
    let known = uri.starts_with("spotify:preview:");
    Track {
        title: if title.is_empty() && known { TRACKS[i].0.into() } else { title.into() },
        artist: if artist.is_empty() && known { TRACKS[i].1.into() } else { artist.into() },
        uri: uri.into(),
        image: if image.is_empty() && known { format!("demo:{i}") } else { image.into() },
        album: if known { TRACKS[i].2.into() } else { String::new() },
        album_uri: if known { format!("spotify:album:{i}") } else { String::new() },
        artist_uri: if known { format!("spotify:artist:{i}") } else { String::new() },
        duration_ms: if known { TRACKS[i].3 as u32 } else { 0 },
        explicit: known && i % 3 == 1,
        ..Default::default()
    }
}

/// Plays a command against the demo player, as the real one would answer it.
pub fn apply(now: &mut NowPlaying, command: Command) {
    let here = index_of(&now.uri);
    let (shuffle, repeat) = (now.shuffle, now.repeat);
    let at = now.position_at(now_ms());
    let keeps_options = matches!(command, Command::Shuffle(_) | Command::Repeat(_) | Command::AddToQueue(_) | Command::PlayNext(_) | Command::Speed(_));
    match command {
        Command::Pause | Command::Resume => {
            now.position_ms = at;
            now.timestamp = now_ms();
            now.playing = matches!(command, Command::Resume);
        }
        Command::Play { uri, shuffle } => {
            *now = track(index_of(&uri), true);
            if let Some(on) = shuffle {
                now.shuffle = on;
            }
        }
        Command::PlayFrom { track: uri, .. } => *now = track(index_of(&uri), true),
        Command::Next => {
            if let Some(next) = now.up_next.first().cloned() {
                return apply(now, Command::SkipTo { uri: next.uri, uid: next.uid });
            }
            *now = track((here + 1) % TRACKS.len(), now.playing);
        }
        Command::Previous(allow_seeking) => {
            if allow_seeking && at > 3000 {
                now.position_ms = 0;
                now.timestamp = now_ms();
            } else {
                *now = track((here + TRACKS.len() - 1) % TRACKS.len(), now.playing);
            }
        }
        Command::SkipTo { uri, uid } => {
            let tail = now.up_next.iter().position(|t| t.uri == uri && t.uid == uid)
                .map(|at| now.up_next[at + 1..].to_vec());
            let revision = now.queue_revision + 1;
            let mut next = track(index_of(&uri), now.playing);
            if let Some(tail) = tail { next.up_next = tail; }
            next.queue_revision = revision;
            *now = next;
        }
        Command::SeekTo(ms) => {
            now.position_ms = ms.clamp(0, now.duration_ms);
            now.timestamp = now_ms();
        }
        Command::Shuffle(on) => now.shuffle = on,
        Command::Repeat(mode) => now.repeat = mode,
        Command::RepeatFlags { context, track } => {
            if track == Some(true) { now.repeat = 2; }
            else if context == Some(true) { now.repeat = 1; }
            else if track == Some(false) || context == Some(false) { now.repeat = 0; }
        }
        Command::Speed(speed) => now.speed = speed,
        Command::PlayNext(uri) => {
            let mut track = next_track(index_of(&uri));
            now.queue_revision += 1;
            track.queued = true;
            track.uid = format!("queued-{}", now.queue_revision);
            now.up_next.insert(0, track);
        },
        Command::AddToQueue(uri) => {
            let mut track = next_track(index_of(&uri));
            now.queue_revision += 1;
            track.queued = true;
            track.uid = format!("queued-{}", now.queue_revision);
            let index = now.up_next.iter().position(|t| !t.queued).unwrap_or(now.up_next.len());
            now.up_next.insert(index, track);
        },
    }
    if !keeps_options {
        now.shuffle = shuffle;
        now.repeat = repeat;
    }
}
