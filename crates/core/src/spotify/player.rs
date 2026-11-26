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
    /// What's playing: "Lossless · 24-bit · 44.1 kHz", "Very high · 320 kbps"..., or "" if Spotify hasn't said.
    pub quality: String,
    /// Spotify is fetching audio and can't play yet.
    pub buffering: bool,
    /// Playback speed (1.0 normal), for podcasts; 0 when the state doesn't say.
    pub speed: f32,
    /// Whether Spotify allows skipping forward / back right now (not during an ad, for instance).
    pub can_next: bool,
    pub can_previous: bool,
    /// The playing song's album and first artist, to open their pages.
    pub album_uri: String,
    pub artist_uri: String,
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
    pub queue_revision: u64,
}

/// A queued track: what to show, plus the uri and uid SkipNext needs to jump straight to it.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NextTrack {
    pub title: String,
    pub artist: String,
    pub image: String,
    pub uri: String,
    pub uid: String,
    pub queued: bool,
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

/// What the player says is playing, from its BitrateLevel (1 Low … 4 Very high are Ogg Vorbis at 24, 96, 160
/// and 320 kbps; 5 HIFI and 6 HIFI24 are lossless).
fn quality_label(q: Option<&es::PlaybackQuality>) -> String {
    let Some(q) = q else { return String::new() };
    if !q.source_is_lossless && !matches!(q.bitrate_level, 5 | 6) {
        return match q.bitrate_level {
            1 => "Low · 24 kbps",
            2 => "Normal · 96 kbps",
            3 => "High · 160 kbps",
            4 => "Very high · 320 kbps",
            _ => "",
        }
        .into();
    }
    let mut label = "Lossless".to_string();
    if q.playing_bit_depth > 0 {
        label += &format!(" · {}-bit", q.playing_bit_depth);
    }
    if q.playing_sample_rate_hz > 0 {
        let khz = q.playing_sample_rate_hz as f64 / 1000.0;
        label += &if khz.fract() == 0.0 { format!(" · {khz:.0} kHz") } else { format!(" · {khz:.1} kHz") };
    }
    label
}

pub fn visible_track(track: &es::ProvidedTrack) -> bool {
    track.context_track.as_ref().is_some_and(|t| !t.uri.is_empty() && !t.uri.starts_with("spotify:delimiter"))
}

pub fn next_tracks(tracks: &[es::ProvidedTrack]) -> impl Iterator<Item = NextTrack> + '_ {
    tracks.iter().filter(|t| visible_track(t)).filter_map(|track| {
        let t = track.context_track.as_ref()?;
        Some(NextTrack {
            title: meta(t, &["title"]).to_string(),
            artist: meta(t, &["artist_name"]).to_string(),
            image: image_url(meta(t, &["image_url", "image_large_url", "image_small_url"])),
            uri: t.uri.clone(),
            uid: t.uid.clone(),
            queued: track.provider == "queue",
        })
    })
}

pub fn edit_queue<T>(tracks: &mut Vec<T>, index: usize, action: i32, editable: impl Fn(&T) -> bool) -> bool {
    if !tracks.get(index).is_some_and(&editable) {
        return false;
    }
    if action == 10 {
        tracks.remove(index);
        return true;
    }
    let destination = match action {
        11 => index.checked_sub(1),
        12 => Some(index + 1),
        _ => None,
    }.filter(|&i| tracks.get(i).is_some_and(editable));
    if let Some(destination) = destination {
        let track = tracks.remove(index);
        tracks.insert(destination, track);
        true
    } else {
        false
    }
}

/// Moves the track at `from` to `to` (a dragged queue row), one step at a time so every place it passes must be
/// editable too. All or nothing: a move that can't finish leaves `tracks` as it was.
pub fn move_in_queue<T: Clone>(tracks: &mut Vec<T>, from: usize, to: usize, editable: impl Fn(&T) -> bool + Copy) -> bool {
    let mut moved = tracks.clone();
    let mut at = from;
    while at != to {
        let (step, next) = if to > at { (12, at + 1) } else { (11, at - 1) };
        if !edit_queue(&mut moved, at, step, editable) {
            return false;
        }
        at = next;
    }
    *tracks = moved;
    from != to
}

#[cfg(not(feature = "preview"))]
pub fn queue_edit_request(queue: &es::Queue, index: usize, action: i32, to: usize) -> Option<es::SetQueueRequest> {
    let mut next_tracks = queue.next_tracks.clone();
    let raw = |visible: usize, list: &[es::ProvidedTrack]| {
        list.iter().enumerate().filter(|(_, track)| visible_track(track)).nth(visible).map(|(i, _)| i)
    };
    let raw_index = raw(index, &next_tracks)?;
    if action == 10 {
        next_tracks.remove(raw_index);
    } else {
        let dest = match action {
            11 => index.checked_sub(1)?,
            12 => index + 1,
            15 => to,
            _ => return None,
        };
        let raw_to = raw(dest, &next_tracks)?;
        if raw_index == raw_to {
            return None;
        }
        let mut item = next_tracks.remove(raw_index);
        if let Some(delim) = next_tracks.iter().position(|t| !visible_track(t)) {
            if raw_to <= delim {
                item.provider = "queue".into();
            }
        }
        next_tracks.insert(raw_to, item);
    }
    Some(es::SetQueueRequest {
        next_tracks,
        prev_tracks: queue.prev_tracks.clone(),
        queue_revision: queue.queue_revision,
        ..Default::default()
    })
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
            quality: quality_label(state.playback_quality.as_ref()),
            buffering: state.is_buffering,
            speed: state.playback_speed.as_ref().map(|v| v.value as f32).filter(|v| *v > 0.0).or_else(|| options.map(|o| o.playback_speed).filter(|v| *v > 0.0)).unwrap_or(0.0),
            can_next: state.restrictions.as_ref().is_none_or(|r| r.disallow_skipping_next_reasons.is_empty()),
            album_uri: meta(&track, &["album_uri"]).into(),
            artist_uri: meta(&track, &["artist_uri"]).into(),
            can_previous: state.restrictions.as_ref().is_none_or(|r| r.disallow_skipping_prev_reasons.is_empty()),
            liked: match meta(&track, &["collection.in_collection"]) { "true" => Some(true), "false" => Some(false), _ => None },
            image: image_url(meta(&track, &["image_large_url", "image_url", "image_xlarge_url", "image_small_url"])),
            playing: state.is_playing && !state.is_paused,
            shuffle: options.is_some_and(|o| o.shuffling_context),
            repeat: options.map_or(0, |o| if o.repeating_track { 2 } else if o.repeating_context { 1 } else { 0 }),
            position_ms: state.position_as_of_timestamp.map_or(0, |p| p.value),
            duration_ms: state.duration.map_or(0, |d| d.value),
            timestamp: state.timestamp,
            up_next: next_tracks(&state.next_tracks).collect(),
            queue_revision: state.queue_revision,
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
    /// A playlist, album, artist or show by URI, from its start, optionally overriding shuffle.
    Play { uri: String, shuffle: Option<bool> },
    /// A playlist from one of its songs: the song's uri and its position in the playlist.
    PlayFrom { context: String, track: String, index: i64 },
    Pause,
    Resume,
    Next,
    /// `true` allows Spotify to restart the current track when past the beginning; `false` forces skipping back.
    Previous(bool),
    SeekTo(i64),
    Shuffle(bool),
    /// 0 off, 1 the context, 2 the track.
    Repeat(u8),
    /// Partial repeat option update used when Spotify restricts toggling one of the two repeat flags.
    RepeatFlags { context: Option<bool>, track: Option<bool> },
    /// Skips forward to a track in Up Next, as Spotify's queue does.
    SkipTo { uri: String, uid: String },
    AddToQueue(String),
    /// Puts a song at the front of Up Next (ahead of what's already queued), as Spotify's "Play next".
    PlayNext(String),
    /// Playback speed, 0.25 to 4 (podcasts and audiobooks).
    Speed(f32),
}

fn logging_params() -> Option<es::LoggingParams> {
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64);
    Some(es::LoggingParams {
        command_initiated_time: Some(es::OptionalInt64 { value: now_ms }),
        ..Default::default()
    })
}

/// Checks an Esperanto `ContextPlayer` `ResponseWithReasons` payload; `error != 0` means Spotify rejected the command.
pub fn check_response(bytes: &[u8]) -> Result<(), String> {
    if bytes.is_empty() {
        return Ok(());
    }
    let response = es::ResponseWithReasons::decode(bytes).map_err(|e| format!("decode ResponseWithReasons: {e}"))?;
    if response.error == 0 {
        Ok(())
    } else if response.reasons.is_empty() {
        Err(format!("error {}", response.error))
    } else {
        Err(format!("error {} ({})", response.error, response.reasons))
    }
}

/// The RPC method and request bytes for a command.
pub fn command(c: Command) -> (&'static str, Vec<u8>) {
    let track = |uri, uid| Some(es::ContextTrack { uri, uid, ..Default::default() });
    let log = logging_params();
    match c {
        Command::Play { uri, shuffle } => (
            "Play",
            es::PlayRequest {
                prepare_play_request: Some(es::PreparePlayRequest {
                    context: Some(es::Context { url: format!("context://{uri}"), uri, ..Default::default() }),
                    options: shuffle.map(|on| es::PreparePlayOptions {
                        player_options_override: Some(es::ContextPlayerOptionOverrides {
                            shuffling_context: Some(es::OptionalBoolean { value: on }),
                            ..Default::default()
                        }),
                        ..Default::default()
                    }),
                    play_origin: Some(es::PlayOrigin { feature_identifier: "spatch".into(), ..Default::default() }),
                    ..Default::default()
                }),
                ..Default::default()
            }
            .encode_to_vec(),
        ),
        Command::PlayFrom { context, track, index } => (
            "Play",
            es::PlayRequest {
                prepare_play_request: Some(es::PreparePlayRequest {
                    context: Some(es::Context { url: format!("context://{context}"), uri: context, ..Default::default() }),
                    options: Some(es::PreparePlayOptions {
                        skip_to: Some(es::SkipToTrack { track_uri: track, track_index: Some(es::OptionalInt64 { value: index }), ..Default::default() }),
                        ..Default::default()
                    }),
                    play_origin: Some(es::PlayOrigin { feature_identifier: "spatch".into(), ..Default::default() }),
                    ..Default::default()
                }),
                ..Default::default()
            }
            .encode_to_vec(),
        ),
        Command::Pause => ("Pause", es::PauseRequest { logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::Resume => ("Resume", es::ResumeRequest { logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::Next => ("SkipNext", es::SkipNextRequest { logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::Previous(allow_seeking) => ("SkipPrev", es::SkipPrevRequest { allow_seeking, logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::SeekTo(ms) => ("SeekTo", es::SeekToRequest { position: ms, logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::Shuffle(on) => ("SetShufflingContext", es::SetShufflingContextRequest { shuffling_context: on, logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::Repeat(mode) => (
            "SetOptions",
            es::SetOptionsRequest {
                repeating_context: Some(es::OptionalBoolean { value: mode >= 1 }),
                repeating_track: Some(es::OptionalBoolean { value: mode == 2 }),
                logging_params: log,
                ..Default::default()
            }
            .encode_to_vec(),
        ),
        Command::RepeatFlags { context, track } => (
            "SetOptions",
            es::SetOptionsRequest {
                repeating_context: context.map(|value| es::OptionalBoolean { value }),
                repeating_track: track.map(|value| es::OptionalBoolean { value }),
                logging_params: log,
                ..Default::default()
            }
            .encode_to_vec(),
        ),
        Command::SkipTo { uri, uid } => ("SkipNext", es::SkipNextRequest { track: track(uri, uid), logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::Speed(speed) => ("SetOptions", es::SetOptionsRequest { playback_speed: speed, logging_params: log, ..Default::default() }.encode_to_vec()),
        Command::PlayNext(uri) => (
            "PlayAsNextInQueue",
            es::PlayAsNextInQueueRequest { tracks: vec![es::ContextTrack { uri, ..Default::default() }], logging_params: log, ..Default::default() }.encode_to_vec(),
        ),
        Command::AddToQueue(uri) => ("AddToQueue", es::AddToQueueRequest { track: track(uri, String::new()), logging_params: log, ..Default::default() }.encode_to_vec()),
    }
}

/// Fallback commands when Spotify rejects `c` (for example, `SkipPrev` with `allow_seeking = false` when no previous track exists,
/// or `Repeat` when the current context restricts toggling `repeating_context` or `repeating_track`).
pub fn command_fallbacks(c: &Command) -> Vec<Command> {
    match c {
        Command::Previous(false) => vec![Command::Previous(true), Command::SeekTo(0)],
        Command::Previous(true) => vec![Command::SeekTo(0)],
        Command::Repeat(0) => vec![
            Command::RepeatFlags { context: None, track: Some(false) },
            Command::RepeatFlags { context: Some(false), track: None },
        ],
        Command::Repeat(1) => vec![
            Command::RepeatFlags { context: Some(true), track: None },
            Command::RepeatFlags { context: None, track: Some(true) },
        ],
        Command::Repeat(2) => vec![
            Command::RepeatFlags { context: None, track: Some(true) },
            Command::RepeatFlags { context: Some(false), track: Some(true) },
        ],
        _ => Vec::new(),
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
    fn edits_only_manually_queued_tracks() {
        let mut queue = vec!["first", "second", "context"];
        let queued = |track: &&str| *track != "context";
        assert!(edit_queue(&mut queue, 0, 12, queued));
        assert_eq!(queue, ["second", "first", "context"]);
        assert!(!edit_queue(&mut queue, 1, 12, queued));
        assert!(!edit_queue(&mut queue, 2, 10, queued));
        assert!(edit_queue(&mut queue, 0, 10, queued));
        assert_eq!(queue, ["first", "context"]);
    }

    #[test]
    fn a_dragged_track_moves_across_queued_tracks_only() {
        let queued = |track: &&str| *track != "context";
        let mut queue = vec!["a", "b", "c", "context"];
        assert!(move_in_queue(&mut queue, 0, 2, queued));
        assert_eq!(queue, ["b", "c", "a", "context"]);
        assert!(move_in_queue(&mut queue, 2, 0, queued));
        assert_eq!(queue, ["a", "b", "c", "context"]);
        assert!(!move_in_queue(&mut queue, 1, 3, queued), "past the queued tracks");
        assert_eq!(queue, ["a", "b", "c", "context"], "a refused move changes nothing");
        assert!(!move_in_queue(&mut queue, 1, 1, queued));
    }

    #[cfg(not(feature = "preview"))]
    #[test]
    fn queue_edit_preserves_revision_history_and_delimiters() {
        let delimiter = provided("spotify:delimiter", "", "");
        let manual = es::ProvidedTrack { provider: "queue".into(), ..provided("spotify:track:2", "Next", "") };
        let context = provided("spotify:track:3", "Context", "");
        let queue = es::Queue {
            queue_revision: 42,
            next_tracks: vec![manual, delimiter.clone(), context.clone()],
            prev_tracks: vec![provided("spotify:track:1", "Previous", "")],
            ..Default::default()
        };
        let request = queue_edit_request(&queue, 0, 10, 0).unwrap();
        assert_eq!(request.queue_revision, 42);
        assert_eq!(request.prev_tracks, queue.prev_tracks);
        assert_eq!(request.next_tracks, [delimiter.clone(), context.clone()]);
        let removed_context = queue_edit_request(&queue, 1, 10, 0).unwrap();
        assert_eq!(removed_context.next_tracks, [queue.next_tracks[0].clone(), delimiter.clone()]);
        let promoted = queue_edit_request(&queue, 1, 15, 0).unwrap();
        assert_eq!(promoted.next_tracks[0].provider, "queue");
        assert_eq!(promoted.next_tracks[2], delimiter);
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
        assert_eq!(now.quality, "");
        let very_high = es::ContextPlayerState { playback_quality: Some(es::PlaybackQuality { bitrate_level: 4, ..Default::default() }), ..Default::default() };
        assert_eq!(NowPlaying::decode(&very_high.encode_to_vec()).unwrap().quality, "Very high · 320 kbps");
        let lossless = es::ContextPlayerState {
            playback_quality: Some(es::PlaybackQuality { bitrate_level: 6, playing_bit_depth: 24, playing_sample_rate_hz: 44_100, ..Default::default() }),
            ..Default::default()
        };
        assert_eq!(NowPlaying::decode(&lossless.encode_to_vec()).unwrap().quality, "Lossless · 24-bit · 44.1 kHz");
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
        let (method, from) = command(Command::PlayFrom { context: "spotify:playlist:1".into(), track: "spotify:track:7".into(), index: 6 });
        let sent = es::PlayRequest::decode(from.as_slice()).unwrap().prepare_play_request.unwrap();
        let skip = sent.options.unwrap().skip_to.unwrap();
        assert_eq!((method, sent.context.unwrap().uri.as_str()), ("Play", "spotify:playlist:1"));
        assert_eq!((skip.track_uri.as_str(), skip.track_index.unwrap().value), ("spotify:track:7", 6));
        let (method, bytes) = command(Command::Play { uri: "spotify:playlist:1".into(), shuffle: Some(true) });
        let prep = es::PlayRequest::decode(bytes.as_slice()).unwrap().prepare_play_request.unwrap();
        assert_eq!((method, prep.context.unwrap().url.as_str()), ("Play", "context://spotify:playlist:1"));
        assert!(prep.options.unwrap().player_options_override.unwrap().shuffling_context.unwrap().value);
        let (method, bytes) = command(Command::SkipTo { uri: "spotify:track:2".into(), uid: "u2".into() });
        let skip = es::SkipNextRequest::decode(bytes.as_slice()).unwrap().track.unwrap();
        assert_eq!((method, skip.uri.as_str(), skip.uid.as_str()), ("SkipNext", "spotify:track:2", "u2"));
        let (method, speed) = command(Command::Speed(1.5));
        assert_eq!((method, es::SetOptionsRequest::decode(speed.as_slice()).unwrap().playback_speed), ("SetOptions", 1.5));
        let (method, next) = command(Command::PlayNext("spotify:track:4".into()));
        let sent = es::PlayAsNextInQueueRequest::decode(next.as_slice()).unwrap();
        assert_eq!((method, sent.tracks.len(), sent.tracks[0].uri.as_str()), ("PlayAsNextInQueue", 1, "spotify:track:4"));
        let (method, bytes) = command(Command::AddToQueue("spotify:track:9".into()));
        assert_eq!((method, es::AddToQueueRequest::decode(bytes.as_slice()).unwrap().track.unwrap().uri.as_str()), ("AddToQueue", "spotify:track:9"));
        let (method, bytes) = command(Command::Repeat(1));
        let options = es::SetOptionsRequest::decode(bytes.as_slice()).unwrap();
        assert_eq!((method, options.repeating_context.unwrap().value, options.repeating_track.unwrap().value), ("SetOptions", true, false));
        assert!(options.logging_params.and_then(|l| l.command_initiated_time).is_some());
        let (method, bytes) = command(Command::Shuffle(true));
        let shuf = es::SetShufflingContextRequest::decode(bytes.as_slice()).unwrap();
        assert_eq!((method, shuf.shuffling_context), ("SetShufflingContext", true));
        assert!(shuf.logging_params.and_then(|l| l.command_initiated_time).is_some());
        assert_eq!(es::SeekToRequest::decode(command(Command::SeekTo(1234)).1.as_slice()).unwrap().position, 1234);
        assert!(es::SkipPrevRequest::decode(command(Command::Previous(true)).1.as_slice()).unwrap().allow_seeking);
        assert!(!es::SkipPrevRequest::decode(command(Command::Previous(false)).1.as_slice()).unwrap().allow_seeking);
    }

    #[test]
    fn response_with_reasons_surfaces_rejections() {
        assert!(check_response(&[]).is_ok());
        assert!(check_response(&es::ResponseWithReasons { error: 0, reasons: String::new() }.encode_to_vec()).is_ok());
        let err = check_response(&es::ResponseWithReasons { error: 1, reasons: "mft_disallow".into() }.encode_to_vec()).unwrap_err();
        assert!(err.contains("error 1") && err.contains("mft_disallow"));
    }

    #[test]
    fn previous_command_fallbacks_restart_track_when_skip_rejected() {
        assert_eq!(command_fallbacks(&Command::Previous(false)), vec![Command::Previous(true), Command::SeekTo(0)]);
        assert_eq!(command_fallbacks(&Command::Previous(true)), vec![Command::SeekTo(0)]);
        assert!(command_fallbacks(&Command::Next).is_empty());
    }

    #[test]
    fn repeat_command_fallbacks_handle_restricted_context_or_track_flags() {
        assert_eq!(
            command_fallbacks(&Command::Repeat(1)),
            vec![
                Command::RepeatFlags { context: Some(true), track: None },
                Command::RepeatFlags { context: None, track: Some(true) },
            ]
        );
        assert_eq!(
            command_fallbacks(&Command::Repeat(2)),
            vec![
                Command::RepeatFlags { context: None, track: Some(true) },
                Command::RepeatFlags { context: Some(false), track: Some(true) },
            ]
        );
        assert_eq!(
            command_fallbacks(&Command::Repeat(0)),
            vec![
                Command::RepeatFlags { context: None, track: Some(false) },
                Command::RepeatFlags { context: Some(false), track: None },
            ]
        );
        let (method, bytes) = command(Command::RepeatFlags { context: None, track: Some(true) });
        let options = es::SetOptionsRequest::decode(bytes.as_slice()).unwrap();
        assert_eq!((method, options.repeating_context, options.repeating_track.unwrap().value), ("SetOptions", None, true));
    }
}
