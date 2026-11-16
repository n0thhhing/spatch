//! The player: Spotify's ContextPlayer service, read as a state stream and driven by commands.

use crate::es_player as es;
use prost::Message;

pub const SERVICE: &str = "spotify.player.esperanto.proto.ContextPlayer";

/// What the UI shows of the player, decoded from ContextPlayerState.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NowPlaying {
    pub uri: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub source: String,
    pub album_artist: String,
    pub release: String,
    pub album_position: String,
    pub explicit: bool,
    pub image: String,
    pub playing: bool,
    pub shuffle: bool,
    /// 0 off, 1 the context, 2 the track (Spotify's own cycle).
    pub repeat: u8,
    pub position_ms: i64,
    pub duration_ms: i64,
    /// ContextPlayerState.timestamp: when position_ms was true, in ms since the epoch.
    pub timestamp: u64,
    /// Title, artist and artwork URL of the next tracks.
    pub up_next: Vec<(String, String, String)>,
}

/// Spotify's image URIs (spotify:image:<id>) as their public CDN URL; anything else unchanged.
pub fn image_url(uri: &str) -> String {
    match uri.strip_prefix("spotify:image:") {
        Some(id) => format!("https://i.scdn.co/image/{id}"),
        None => uri.to_string(),
    }
}

fn meta<'a>(track: &'a es::ContextTrack, keys: &[&str]) -> &'a str {
    keys.iter().find_map(|k| track.metadata.get(*k).filter(|v| !v.is_empty())).map(String::as_str).unwrap_or("")
}

impl NowPlaying {
    pub fn decode(bytes: &[u8]) -> Result<NowPlaying, prost::DecodeError> {
        let state = es::ContextPlayerState::decode(bytes)?;
        let track = state.track.as_ref().and_then(|t| t.context_track.clone()).unwrap_or_default();
        let up_next = state
            .next_tracks
            .iter()
            .filter_map(|t| t.context_track.as_ref())
            .filter(|t| !t.uri.starts_with("spotify:delimiter"))
            .take(10)
            .map(|t| (meta(t, &["title"]).to_string(), meta(t, &["artist_name"]).to_string(), image_url(meta(t, &["image_url", "image_large_url", "image_small_url"]))))
            .collect();
        let track_number = meta(&track, &["track_number"]);
        let disc_number = meta(&track, &["disc_number"]);
        let album_position = match (track_number, disc_number) {
            ("", _) => String::new(),
            (track, "" | "0") => format!("Track {track}"),
            (track, disc) => format!("Track {track} · Disc {disc}"),
        };
        Ok(NowPlaying {
            album_artist: meta(&track, &["album_artist_name"]).into(),
            release: meta(&track, &["album_release_date", "album_date", "album_year"]).into(),
            album_position,
            explicit: matches!(meta(&track, &["is_explicit", "explicit"]), "true" | "1"),
            uri: track.uri.clone(),
            title: meta(&track, &["title"]).to_string(),
            artist: meta(&track, &["artist_name", "album_artist_name"]).to_string(),
            album: meta(&track, &["album_title"]).to_string(),
            source: ["context_title", "title", "name"].into_iter()
                .find_map(|key| state.context_metadata.get(key).filter(|value| !value.is_empty()))
                .cloned().unwrap_or_default(),
            image: image_url(meta(&track, &["image_large_url", "image_url", "image_xlarge_url", "image_small_url"])),
            playing: state.is_playing && !state.is_paused,
            shuffle: state.options.as_ref().is_some_and(|o| o.shuffling_context),
            repeat: state.options.as_ref().map_or(0, |o| if o.repeating_track { 2 } else if o.repeating_context { 1 } else { 0 }),
            position_ms: state.position_as_of_timestamp.map(|p| p.value).unwrap_or(0),
            duration_ms: state.duration.map(|d| d.value).unwrap_or(0),
            timestamp: state.timestamp,
            up_next,
        })
    }

    /// Where playback is at `now_ms` (epoch ms), extrapolated from the last state while playing.
    pub fn position_at(&self, now_ms: u64) -> i64 {
        let mut at = self.position_ms;
        if self.playing && self.timestamp > 0 && now_ms > self.timestamp {
            at += (now_ms - self.timestamp) as i64;
        }
        if self.duration_ms > 0 { at.clamp(0, self.duration_ms) } else { at.max(0) }
    }
}

/// The request that opens the state stream (next tracks capped for Up Next).
pub fn state_request() -> Vec<u8> {
    es::GetStateRequest { next_tracks_cap: Some(es::OptionalInt64 { value: 10 }), prev_tracks_cap: Some(es::OptionalInt64 { value: 2 }), ..Default::default() }
        .encode_to_vec()
}

#[derive(Clone, Debug, PartialEq)]
pub enum Command {
    /// Plays a playlist, album, artist or show by URI from its start.
    Play(String),
    Pause,
    Resume,
    Next,
    Previous,
    SeekTo(i64),
    Shuffle(bool),
    /// 0 off, 1 the context, 2 the track.
    Repeat(u8),
}

/// The RPC method and request bytes for a command.
pub fn command(c: Command) -> (&'static str, Vec<u8>) {
    match c {
        Command::Play(uri) => (
            "Play",
            es::PlayRequest {
                prepare_play_request: Some(es::PreparePlayRequest {
                    context: Some(es::Context { url: format!("context://{uri}"), uri, ..Default::default() }),
                    play_origin: Some(es::PlayOrigin { feature_identifier: "spatch".into(), ..Default::default() }),
                    ..Default::default()
                }),
                ..Default::default()
            }
            .encode_to_vec(),
        ),
        Command::Pause => ("Pause", es::PauseRequest::default().encode_to_vec()),
        Command::Resume => ("Resume", es::ResumeRequest::default().encode_to_vec()),
        Command::Next => ("SkipNext", es::SkipNextRequest::default().encode_to_vec()),
        Command::Previous => ("SkipPrev", es::SkipPrevRequest { allow_seeking: true, ..Default::default() }.encode_to_vec()),
        Command::Shuffle(on) => ("SetShufflingContext", es::SetShufflingContextRequest { shuffling_context: on, ..Default::default() }.encode_to_vec()),
        Command::Repeat(mode) => (
            "SetOptions",
            es::SetOptionsRequest {
                repeating_context: Some(es::OptionalBoolean { value: mode >= 1 }),
                repeating_track: Some(es::OptionalBoolean { value: mode == 2 }),
                ..Default::default()
            }
            .encode_to_vec(),
        ),
        Command::SeekTo(ms) => ("SeekTo", es::SeekToRequest { position: ms, ..Default::default() }.encode_to_vec()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(uri: &str, title: &str, artist: &str) -> es::ProvidedTrack {
        es::ProvidedTrack {
            context_track: Some(es::ContextTrack {
                uri: uri.into(),
                metadata: [("title".to_string(), title.to_string()), ("artist_name".to_string(), artist.to_string()), ("image_url".to_string(), "spotify:image:ab67".to_string())].into(),
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    #[test]
    fn decodes_the_track_and_up_next() {
        let mut current = track("spotify:track:1", "city", "i wanna be a jack-o-lantern");
        current.context_track.as_mut().unwrap().metadata.extend(
            [("album_artist_name", "Various Artists"), ("album_release_date", "2022"),
                ("track_number", "3"), ("disc_number", "2"), ("is_explicit", "true")]
                .map(|(key, value)| (key.into(), value.into())),
        );
        let state = es::ContextPlayerState {
            track: Some(current),
            next_tracks: vec![track("spotify:track:2", "annoying", "someone"), track("spotify:delimiter", "", "")],
            is_playing: true,
            position_as_of_timestamp: Some(es::OptionalInt64 { value: 1000 }),
            duration: Some(es::OptionalInt64 { value: 85000 }),
            timestamp: 5000,
            ..Default::default()
        };
        let now = NowPlaying::decode(&state.encode_to_vec()).unwrap();
        assert_eq!(now.title, "city");
        assert_eq!(now.album_artist, "Various Artists");
        assert_eq!(now.release, "2022");
        assert_eq!(now.album_position, "Track 3 · Disc 2");
        assert!(now.explicit);
        assert_eq!(now.artist, "i wanna be a jack-o-lantern");
        assert_eq!(now.image, "https://i.scdn.co/image/ab67");
        assert!(now.playing);
        assert_eq!(now.up_next, vec![("annoying".to_string(), "someone".to_string(), "https://i.scdn.co/image/ab67".to_string())]);
        assert_eq!(now.position_at(7000), 3000);
        assert_eq!(now.position_at(999_999), 85000); // clamped to the end
    }

    #[test]
    fn paused_state_does_not_advance() {
        let state = es::ContextPlayerState { is_playing: true, is_paused: true, position_as_of_timestamp: Some(es::OptionalInt64 { value: 40 }), timestamp: 10, ..Default::default() };
        let now = NowPlaying::decode(&state.encode_to_vec()).unwrap();
        assert!(!now.playing);
        assert_eq!(now.position_at(10_000), 40);
    }

    #[test]
    fn shuffle_and_repeat_follow_the_options() {
        let state = es::ContextPlayerState {
            options: Some(es::ContextPlayerOptions { shuffling_context: true, repeating_context: true, repeating_track: true, ..Default::default() }),
            ..Default::default()
        };
        let now = NowPlaying::decode(&state.encode_to_vec()).unwrap();
        assert!(now.shuffle);
        assert_eq!(now.repeat, 2);
        let (m, bytes) = command(Command::Repeat(1));
        let r = es::SetOptionsRequest::decode(bytes.as_slice()).unwrap();
        assert_eq!((m, r.repeating_context.unwrap().value, r.repeating_track.unwrap().value), ("SetOptions", true, false));
        assert_eq!(command(Command::Shuffle(true)).0, "SetShufflingContext");
    }

    #[test]
    fn play_carries_the_context() {
        let (m, bytes) = command(Command::Play("spotify:playlist:1".into()));
        assert_eq!(m, "Play");
        let ctx = es::PlayRequest::decode(bytes.as_slice()).unwrap().prepare_play_request.unwrap().context.unwrap();
        assert_eq!((ctx.uri.as_str(), ctx.url.as_str()), ("spotify:playlist:1", "context://spotify:playlist:1"));
    }

    #[test]
    fn commands_name_spotify_methods() {
        assert_eq!(command(Command::Next).0, "SkipNext");
        let (m, bytes) = command(Command::SeekTo(1234));
        assert_eq!(m, "SeekTo");
        assert_eq!(es::SeekToRequest::decode(bytes.as_slice()).unwrap().position, 1234);
    }
}
