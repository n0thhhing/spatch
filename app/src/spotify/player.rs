//! ContextPlayer: playback state as a stream, and the commands that drive it.

use super::image_url;
use crate::protos::com::spotify::player::esperanto::proto as es;
use prost::Message;

pub const SERVICE: &str = "spotify.player.esperanto.proto.ContextPlayer";

/// What the UI shows of the player.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NowPlaying {
    pub uri: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    /// Name of the playlist or album playing, and its URI.
    pub source: String,
    pub context: String,
    pub explicit: bool,
    /// Whether the song is in Liked Songs, as the player state says (`collection.in_collection`); None if it doesn't.
    pub liked: Option<bool>,
    pub image: String,
    pub playing: bool,
    pub shuffle: bool,
    /// 0 off, 1 the context, 2 the track: Spotify's own cycle.
    pub repeat: u8,
    pub position_ms: i64,
    pub duration_ms: i64,
    /// When `position_ms` was true, in ms since the epoch.
    pub timestamp: u64,
    pub up_next: Vec<NextTrack>,
}

/// A queued track: what to show, plus the uri and uid SkipNext needs to jump straight to it.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NextTrack {
    pub title: String,
    pub artist: String,
    pub image: String,
    pub uri: String,
    pub uid: String,
}

fn meta<'a>(track: &'a es::ContextTrack, keys: &[&str]) -> &'a str {
    keys.iter()
        .find_map(|key| track.metadata.get(*key).filter(|value| !value.is_empty()))
        .map_or("", String::as_str)
}

/// Logs which metadata keys the core attaches to each new song (Canvas, Spotify's colours, lyrics...), once per
/// song, so what's really there can be read from the device log.
fn log_keys(track: &es::ContextTrack) {
    static LAST: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());
    let mut last = LAST.lock().unwrap();
    if track.uri.is_empty() || *last == track.uri {
        return;
    }
    last.clone_from(&track.uri);
    let mut keys: Vec<&str> = track.metadata.keys().map(String::as_str).collect();
    keys.sort_unstable();
    log::info!("Track metadata keys: {}", keys.join(" "));
}

impl NowPlaying {
    pub fn decode(bytes: &[u8]) -> Result<NowPlaying, prost::DecodeError> {
        let state = es::ContextPlayerState::decode(bytes)?;
        let track = state.track.as_ref().and_then(|t| t.context_track.clone()).unwrap_or_default();
        log_keys(&track);
        let options = state.options.as_ref();
        Ok(NowPlaying {
            uri: track.uri.clone(),
            title: meta(&track, &["title"]).into(),
            artist: meta(&track, &["artist_name", "album_artist_name"]).into(),
            album: meta(&track, &["album_title"]).into(),
            source: ["context_title", "title", "name"]
                .iter()
                .find_map(|key| state.context_metadata.get(*key).filter(|v| !v.is_empty()))
                .cloned()
                .unwrap_or_default(),
            context: state.context_uri.clone(),
            explicit: matches!(meta(&track, &["is_explicit", "explicit"]), "true" | "1"),
            liked: match meta(&track, &["collection.in_collection"]) { "true" => Some(true), "false" => Some(false), _ => None },
            image: image_url(meta(&track, &["image_large_url", "image_url", "image_xlarge_url", "image_small_url"])),
            playing: state.is_playing && !state.is_paused,
            shuffle: options.is_some_and(|o| o.shuffling_context),
            repeat: options.map_or(0, |o| if o.repeating_track { 2 } else if o.repeating_context { 1 } else { 0 }),
            position_ms: state.position_as_of_timestamp.map_or(0, |p| p.value),
            duration_ms: state.duration.map_or(0, |d| d.value),
            timestamp: state.timestamp,
            up_next: state
                .next_tracks
                .iter()
                .filter_map(|t| t.context_track.as_ref())
                .filter(|t| !t.uri.starts_with("spotify:delimiter"))
                .take(10)
                .map(|t| NextTrack {
                    title: meta(t, &["title"]).into(),
                    artist: meta(t, &["artist_name"]).into(),
                    image: image_url(meta(t, &["image_url", "image_large_url", "image_small_url"])),
                    uri: t.uri.clone(),
                    uid: t.uid.clone(),
                })
                .collect(),
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

/// The request that opens the state stream, with the queue capped for Up Next.
pub fn state_request() -> Vec<u8> {
    es::GetStateRequest {
        next_tracks_cap: Some(es::OptionalInt64 { value: 10 }),
        prev_tracks_cap: Some(es::OptionalInt64 { value: 2 }),
        ..Default::default()
    }
    .encode_to_vec()
}

#[derive(Clone, Debug, PartialEq)]
pub enum Command {
    /// A playlist, album, artist or show by URI, from its start.
    Play(String),
    Pause,
    Resume,
    Next,
    Previous,
    SeekTo(i64),
    Shuffle(bool),
    /// 0 off, 1 the context, 2 the track.
    Repeat(u8),
    /// Skips forward to a track in Up Next, as Spotify's queue does.
    SkipTo { uri: String, uid: String },
    AddToQueue(String),
}

/// The RPC method and request bytes for a command.
pub fn command(c: Command) -> (&'static str, Vec<u8>) {
    let track = |uri, uid| Some(es::ContextTrack { uri, uid, ..Default::default() });
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
        Command::SeekTo(ms) => ("SeekTo", es::SeekToRequest { position: ms, ..Default::default() }.encode_to_vec()),
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
        Command::SkipTo { uri, uid } => ("SkipNext", es::SkipNextRequest { track: track(uri, uid), ..Default::default() }.encode_to_vec()),
        Command::AddToQueue(uri) => ("AddToQueue", es::AddToQueueRequest { track: track(uri, String::new()), ..Default::default() }.encode_to_vec()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn provided(uri: &str, title: &str, artist: &str) -> es::ProvidedTrack {
        es::ProvidedTrack {
            context_track: Some(es::ContextTrack {
                uri: uri.into(),
                uid: format!("uid-{title}"),
                metadata: [("title", title), ("artist_name", artist), ("image_url", "spotify:image:ab67")]
                    .map(|(k, v)| (k.to_string(), v.to_string()))
                    .into(),
            }),
            ..Default::default()
        }
    }

    #[test]
    fn decodes_the_track_and_the_queue() {
        let mut current = provided("spotify:track:1", "city", "jack");
        current.context_track.as_mut().unwrap().metadata.insert("is_explicit".into(), "true".into());
        current.context_track.as_mut().unwrap().metadata.insert("collection.in_collection".into(), "true".into());
        let state = es::ContextPlayerState {
            track: Some(current),
            next_tracks: vec![provided("spotify:track:2", "annoying", "someone"), provided("spotify:delimiter", "", "")],
            is_playing: true,
            context_uri: "spotify:playlist:9".into(),
            position_as_of_timestamp: Some(es::OptionalInt64 { value: 1000 }),
            duration: Some(es::OptionalInt64 { value: 85000 }),
            timestamp: 5000,
            ..Default::default()
        };
        let now = NowPlaying::decode(&state.encode_to_vec()).unwrap();
        assert_eq!((now.title.as_str(), now.artist.as_str(), now.context.as_str()), ("city", "jack", "spotify:playlist:9"));
        assert!(now.explicit && now.playing);
        assert_eq!(now.liked, Some(true));
        assert_eq!(now.up_next.len(), 1);
        assert_eq!(now.image, "https://i.scdn.co/image/ab67");
        assert_eq!(now.up_next.len(), 1);
        assert_eq!((now.up_next[0].uri.as_str(), now.up_next[0].uid.as_str()), ("spotify:track:2", "uid-annoying"));
        assert_eq!(now.position_at(7000), 3000);
        assert_eq!(now.position_at(999_999), 85000);
    }

    #[test]
    fn a_paused_state_does_not_advance() {
        let state = es::ContextPlayerState { is_playing: true, is_paused: true, position_as_of_timestamp: Some(es::OptionalInt64 { value: 40 }), timestamp: 10, ..Default::default() };
        let now = NowPlaying::decode(&state.encode_to_vec()).unwrap();
        assert!(!now.playing);
        assert_eq!(now.position_at(10_000), 40);
    }

    #[test]
    fn shuffle_and_repeat_follow_the_options() {
        let options = es::ContextPlayerOptions { shuffling_context: true, repeating_context: true, repeating_track: true, ..Default::default() };
        let now = NowPlaying::decode(&es::ContextPlayerState { options: Some(options), ..Default::default() }.encode_to_vec()).unwrap();
        assert!(now.shuffle);
        assert_eq!(now.repeat, 2);
    }

    #[test]
    fn commands_name_spotify_methods_and_carry_their_data() {
        let (method, bytes) = command(Command::Play("spotify:playlist:1".into()));
        let context = es::PlayRequest::decode(bytes.as_slice()).unwrap().prepare_play_request.unwrap().context.unwrap();
        assert_eq!((method, context.url.as_str()), ("Play", "context://spotify:playlist:1"));
        let (method, bytes) = command(Command::SkipTo { uri: "spotify:track:2".into(), uid: "u2".into() });
        let skip = es::SkipNextRequest::decode(bytes.as_slice()).unwrap().track.unwrap();
        assert_eq!((method, skip.uri.as_str(), skip.uid.as_str()), ("SkipNext", "spotify:track:2", "u2"));
        let (method, bytes) = command(Command::AddToQueue("spotify:track:9".into()));
        assert_eq!((method, es::AddToQueueRequest::decode(bytes.as_slice()).unwrap().track.unwrap().uri.as_str()), ("AddToQueue", "spotify:track:9"));
        let (method, bytes) = command(Command::Repeat(1));
        let options = es::SetOptionsRequest::decode(bytes.as_slice()).unwrap();
        assert_eq!((method, options.repeating_context.unwrap().value, options.repeating_track.unwrap().value), ("SetOptions", true, false));
        assert_eq!(command(Command::Shuffle(true)).0, "SetShufflingContext");
        assert_eq!(es::SeekToRequest::decode(command(Command::SeekTo(1234)).1.as_slice()).unwrap().position, 1234);
    }
}
