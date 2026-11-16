//! Offline example data for the standalone preview APK.
use crate::{
    library::{Item, Kind},
    player::{Command, NowPlaying},
};

const TRACKS: [(&str, &str, &str, i64); 5] = [
    ("Glasshouse", "Northbound", "Between Stations", 221_000),
    ("Daybreak Radio", "Hana Vale", "First Light", 183_000),
    ("Blue Hour", "Low Tide", "Night Swim", 246_000),
    ("Signal Fires", "June Static", "Day Shift", 208_000),
    ("Soft Landing", "Northbound", "Between Stations", 197_000),
];

pub fn clock() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |time| time.as_millis() as u64)
}

fn track(index: usize, playing: bool) -> NowPlaying {
    let (title, artist, album, duration_ms) = TRACKS[index];
    let up_next = (1..TRACKS.len())
        .map(|offset| {
            let next = (index + offset) % TRACKS.len();
            (
                TRACKS[next].0.into(),
                TRACKS[next].1.into(),
                format!("demo:{next}"),
            )
        })
        .collect();
    NowPlaying {
        uri: format!("spotify:preview:{index}"),
        title: title.into(),
        artist: artist.into(),
        album: album.into(),
        source: "Preview mix".into(),
        album_artist: artist.into(),
        release: "2025".into(),
        album_position: format!("Track {} · Disc 1", index + 1),
        image: format!("demo:{index}"),
        playing,
        duration_ms,
        timestamp: clock(),
        up_next,
        ..Default::default()
    }
}

pub fn player() -> NowPlaying {
    NowPlaying {
        position_ms: 56_000,
        ..track(0, true)
    }
}

pub fn library() -> Vec<Item> {
    [
        (
            Kind::Playlist,
            "Playlist",
            [
                "Liked Songs",
                "After Hours",
                "Slow Motion",
                "Wildflower",
                "On the Road",
            ],
        ),
        (
            Kind::Album,
            "Album",
            [
                "Between Stations",
                "First Light",
                "Night Swim",
                "Day Shift",
                "Soft Landing",
            ],
        ),
        (
            Kind::Artist,
            "Artist",
            [
                "Northbound",
                "Hana Vale",
                "Low Tide",
                "June Static",
                "Aster Echo",
            ],
        ),
        (
            Kind::Podcast,
            "Podcast",
            [
                "The Listening Room",
                "On Record",
                "Open Frequency",
                "Inside the Mix",
                "Track Notes",
            ],
        ),
    ]
    .into_iter()
    .enumerate()
    .flat_map(|(category, (kind, subtitle, names))| {
        names
            .into_iter()
            .enumerate()
            .map(move |(index, name)| Item {
                name: name.into(),
                kind,
                subtitle: subtitle.into(),
                uri: if category == 0 && index == 0 {
                    "spotify:preview:collection".into()
                } else {
                    format!("spotify:preview:{index}")
                },
                image: format!("demo:{}", index + category * 5),
            })
    })
    .collect()
}

pub fn apply(now: &mut NowPlaying, command: Command) {
    let index = now
        .uri
        .rsplit(':')
        .next()
        .and_then(|value| value.parse::<usize>().ok())
        .unwrap_or(0)
        % TRACKS.len();
    let options = (now.shuffle, now.repeat);
    let timestamp = clock();
    now.position_ms = now.position_at(timestamp);
    now.timestamp = timestamp;
    match command {
        Command::Play(uri) => {
            let index = uri
                .rsplit(':')
                .next()
                .and_then(|value| value.parse::<usize>().ok())
                .unwrap_or(0)
                % TRACKS.len();
            *now = track(index, true);
        }
        Command::Pause => now.playing = false,
        Command::Resume => now.playing = true,
        Command::Next => *now = track((index + 1) % TRACKS.len(), now.playing),
        Command::Previous => *now = track((index + TRACKS.len() - 1) % TRACKS.len(), now.playing),
        Command::SeekTo(position) => now.position_ms = position.clamp(0, now.duration_ms),
        Command::Shuffle(on) => {
            now.shuffle = on;
            return;
        }
        Command::Repeat(mode) => {
            now.repeat = mode;
            return;
        }
    }
    (now.shuffle, now.repeat) = options;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn library_playback_and_transport_are_interactive() {
        let mut now = player();
        let entries = library();
        apply(&mut now, Command::Play(entries[2].uri.clone()));
        assert_eq!(now.title, "Blue Hour");
        apply(&mut now, Command::Pause);
        apply(&mut now, Command::SeekTo(120_000));
        assert_eq!(now.position_at(clock() + 10_000), 120_000);
        apply(&mut now, Command::Next);
        assert_eq!(now.title, "Signal Fires");
        assert_eq!(now.up_next[0].0, "Soft Landing");
    }
}
