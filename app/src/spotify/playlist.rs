//! PlaylistDataService: the tracks of a playlist, Liked Songs included.

use super::image_url;
use crate::protos::{
    com::spotify::cosmos::util::policy::proto as metadata, com::spotify::playlist::policy::proto as policy,
    com::spotify::playlist::proto as pl, spotify::playlist::esperanto::proto as es,
};
use prost::Message;

pub const SERVICE: &str = "spotify.playlist_esperanto.proto.PlaylistDataService";
pub const ROOTLIST_SERVICE: &str = "spotify.playlist_esperanto.proto.RootlistModificationService";
pub const PLAYLIST_SERVICE: &str = "spotify.playlist_esperanto.proto.PlaylistService";
/// Tracks per request.
pub const PAGE: i32 = 100;
pub const MAX_NAME_CHARS: usize = 100;
pub const MAX_DESCRIPTION_CHARS: usize = 300;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Track {
    pub title: String,
    pub artist: String,
    pub uri: String,
    pub image: String,
    pub album: String,
    pub album_uri: String,
    pub artist_uri: String,
    pub duration_ms: u32,
    /// When it was added to the playlist (seconds since 1970), 0 if unknown.
    pub added: u32,
    pub liked: bool,
    /// True when Spotify attached this track as a recommendation rather than a playlist entry.
    pub recommended: bool,
    pub explicit: bool,
}

/// How many strings each song takes in the UI's arrays (`fields`).
pub const FIELDS: usize = 12;

impl Track {
    /// title, artist, uri, image, album, album uri, artist uri, length ms, added, liked ("1" or ""), recommended ("1" or ""),
    /// explicit ("1" or "").
    pub fn fields(self) -> [String; FIELDS] {
        [self.title, self.artist, self.uri, self.image, self.album, self.album_uri, self.artist_uri,
         self.duration_ms.to_string(), self.added.to_string(),
         if self.liked { "1".into() } else { String::new() },
         if self.recommended { "1".into() } else { String::new() },
         if self.explicit { "1".into() } else { String::new() }]
    }
}

/// The order of songs to show, as indexes into `fields` (FIELDS strings per song): only those whose title,
/// artist or album hold every word of `query`, sorted by `sort` (0 playlist order, 1 title, 2 artist, 3 album,
/// 4 most recently added first).
pub fn arrange(fields: &[String], query: &str, sort: i32) -> Vec<usize> {
    let words: Vec<String> = query.split_whitespace().map(str::to_lowercase).collect();
    let song = |i: usize| &fields[i * FIELDS..i * FIELDS + FIELDS];
    let mut order: Vec<usize> = (0..fields.len() / FIELDS)
        .filter(|&i| {
            let s = song(i);
            let text = format!("{} {} {}", s[0], s[1], s[4]).to_lowercase();
            words.iter().all(|w| text.contains(w.as_str()))
        })
        .collect();
    let key = |i: usize, column: usize| song(i)[column].to_lowercase();
    match sort {
        1 => order.sort_by_cached_key(|&i| key(i, 0)),
        2 => order.sort_by_cached_key(|&i| (key(i, 1), key(i, 4))),
        3 => order.sort_by_cached_key(|&i| (key(i, 4), key(i, 0))),
        4 => order.sort_by_cached_key(|&i| std::cmp::Reverse(song(i)[8].parse::<u32>().unwrap_or(0))),
        _ => {}
    }
    order
}

#[derive(Debug, PartialEq)]
pub enum Reply {
    /// Spotify is still fetching the playlist; ask again shortly.
    Loading,
    /// The tracks, the offset of the next page if there is one, and your username when this playlist is
    /// yours (which is how Liked Songs' link is spelled: `spotify:user:<username>:collection`).
    Page { tracks: Vec<Track>, next: Option<i32>, owner: Option<String> },
}

/// Asks for a page, with just the fields the UI shows. Liked Songs goes through under the URI the
/// library gave: the service rejects the generic `spotify:collection:tracks`.
pub fn request(uri: &str, offset: i32) -> Vec<u8> {
    request_range(uri, offset, PAGE)
}

/// Every item's link and time added, without song details: Spotify's core otherwise waits for each song's details
/// before listing it, and on accounts where those requests fail it reports the playlist as loaded with no songs.
pub fn items_request(uri: &str) -> Vec<u8> {
    es::PlaylistGetRequest {
        uri: uri.into(),
        query: Some(es::PlaylistQuery { range: Some(es::PlaylistRange { start: 0, length: 100_000 }), dont_load_item_metadata: true, ..Default::default() }),
        policy: Some(policy::PlaylistRequestDecorationPolicy {
            item: Some(policy::PlaylistItemDecorationPolicy { uri: true, add_time: true, ..Default::default() }),
            ..Default::default()
        }),
    }
    .encode_to_vec()
}

/// The items (link, time added) of an items-only reply.
pub fn items(bytes: &[u8]) -> Result<Vec<(String, u32)>, String> {
    let reply = es::PlaylistGetResponse::decode(bytes).map_err(|e| e.to_string())?;
    let data = reply.data.ok_or_else(|| reply.status.map_or_else(|| "No playlist data".to_string(), |s| s.reason))?;
    Ok(data.item.into_iter().filter(|i| !i.uri.is_empty()).map(|i| (i.uri, i.add_time)).collect())
}

/// The items of a playlist as Spotify's playlist service sends them (playlist4 SelectedListContent: contents = 5,
/// its items = 3, each item's uri = 1 and attributes = 2 with the time added = 2, in milliseconds).
pub fn server_items(bytes: &[u8]) -> Vec<(String, u32)> {
    use super::wire::{fields, first};
    let top = fields(bytes);
    let Some(contents) = first(&top, 5) else { return Vec::new() };
    fields(contents.bytes())
        .into_iter()
        .filter(|(n, _)| *n == 3)
        .filter_map(|(_, item)| {
            let item = fields(item.bytes());
            let uri = first(&item, 1)?.text();
            let added = first(&item, 2).and_then(|a| first(&fields(a.bytes()), 2)).map_or(0, |t| (t.number() / 1000) as u32);
            (!uri.is_empty()).then_some((uri, added))
        })
        .collect()
}

/// Like [`request`] with the page length chosen; a length of 1 is enough to learn who owns a playlist.
pub fn request_range(uri: &str, offset: i32, length: i32) -> Vec<u8> {
    es::PlaylistGetRequest {
        uri: uri.into(),
        query: Some(es::PlaylistQuery {
            range: Some(es::PlaylistRange { start: offset.max(0), length }),
            ..Default::default()
        }),
        policy: Some(policy::PlaylistRequestDecorationPolicy {
            playlist: Some(policy::PlaylistDecorationPolicy {
                owned_by_self: true,
                owner: Some(policy::UserDecorationPolicy { username: true, link: true, ..Default::default() }),
                ..Default::default()
            }),
            track: Some(policy::PlaylistTrackDecorationPolicy {
                track: Some(metadata::TrackDecorationPolicy { name: true, link: true, length: true, is_explicit: true, ..Default::default() }),
                artist: Some(metadata::ArtistDecorationPolicy { name: true, link: true, ..Default::default() }),
                album: Some(policy::PlaylistAlbumDecorationPolicy {
                    album: Some(metadata::AlbumDecorationPolicy { name: true, link: true, ..Default::default() }),
                    ..Default::default()
                }),
                add_time: true,
                in_collection: true,
                is_recommendation: true,
                ..Default::default()
            }),
            episode: Some(policy::PlaylistEpisodeDecorationPolicy {
                is_recommendation: true,
                ..Default::default()
            }),
            item: Some(policy::PlaylistItemDecorationPolicy {
                uri: true,
                add_time: true,
                collection_state: true,
                metadata: Some(policy::ItemMetadataPolicy { name: true, image: true, is_explicit: true }),
                ..Default::default()
            }),
        }),
    }
    .encode_to_vec()
}

pub fn decode(bytes: &[u8], offset: i32) -> Result<Reply, String> {
    let reply = es::PlaylistGetResponse::decode(bytes).map_err(|e| e.to_string())?;
    let data = reply
        .data
        .ok_or_else(|| reply.status.map_or_else(|| "No playlist data".to_string(), |s| s.reason))?;
    if data.loading_contents && data.item.is_empty() {
        return Ok(Reply::Loading);
    }
    let owner = data
        .playlist
        .as_ref()
        .and_then(|p| p.playlist_metadata.as_ref())
        .filter(|m| m.owned_by_self)
        .and_then(|m| m.owner.as_ref())
        .map(|u| if u.username.is_empty() { u.link.rsplit(':').next().unwrap_or("").to_string() } else { u.username.clone() })
        .filter(|name| !name.is_empty());
    let end = offset.max(0) as usize + data.item.len();
    let more = !data.item.is_empty() && end < data.unranged_length.max(data.unfiltered_length) as usize;
    let tracks: Vec<Track> = data
        .item
        .into_iter()
        .map(|item| {
            let name = item.metadata.as_ref().map_or("", |m| m.name.as_str()).to_string();
            let artist = item.track_metadata.as_ref().and_then(|t| t.artist.first()).map_or_else(String::new, |a| a.name.clone());
            let title = if name.is_empty() {
                item.track_metadata.as_ref().map_or_else(|| "Track unavailable".to_string(), |t| t.name.clone())
            } else {
                name
            };
            let track = item.track_metadata.as_ref();
            let album = track.and_then(|t| t.album.as_ref());
            Track {
                title,
                artist,
                uri: item.uri,
                image: item.metadata.as_ref().map_or_else(String::new, |m| image_url(&m.image)),
                album: album.map_or_else(String::new, |a| a.name.clone()),
                album_uri: album.map_or_else(String::new, |a| a.link.clone()),
                artist_uri: track.and_then(|t| t.artist.first()).map_or_else(String::new, |a| a.link.clone()),
                // TrackMetadata.length is whole seconds (Spotify's own reader converts its Duration to SECONDS for the
                // same slot); read as milliseconds every song showed 0:00.
                duration_ms: track.map_or(0, |t| t.length.saturating_mul(1000)),
                added: item.add_time,
                liked: item.track_collection_state.as_ref().is_some_and(|c| c.is_in_collection),
                recommended: item.is_recommendation,
                explicit: item.metadata.as_ref().is_some_and(|m| m.is_explicit) || track.is_some_and(|t| t.is_explicit),
            }
        })
        .collect();
    if !tracks.is_empty() && tracks.iter().all(|t| t.duration_ms == 0) {
        log::warn!("Playlist page has no song lengths ({} songs); first item: {:?}", tracks.len(), tracks.first().map(|t| (&t.title, &t.album)));
    }
    Ok(Reply::Page { tracks, next: more.then_some(end as i32), owner })
}

/// Validates a new playlist's name and description without silent truncation.
pub fn validate_create(name: &str, description: &str) -> Result<(String, String), String> {
    if name.trim().is_empty() {
        return Err("Playlist name cannot be empty".into());
    }
    if name.chars().count() > MAX_NAME_CHARS {
        return Err(format!("Playlist name is too long (max {MAX_NAME_CHARS} characters)"));
    }
    if description.chars().count() > MAX_DESCRIPTION_CHARS {
        return Err(format!("Playlist description is too long (max {MAX_DESCRIPTION_CHARS} characters)"));
    }
    Ok((name.trim().to_string(), description.trim().to_string()))
}

pub fn create_request(name: &str, uris: &[String]) -> Vec<u8> {
    es::RootlistModificationRequest {
        folder_uri: String::new(),
        request: Some(pl::ModificationRequest {
            operation: "create".into(),
            before: "start".into(),
            name: name.into(),
            playlist: true,
            uris: uris.to_vec(),
            ..Default::default()
        }),
    }
    .encode_to_vec()
}

fn response_error(status: Option<es::ResponseStatus>, fallback: &str) -> Result<(), String> {
    let Some(status) = status else {
        return Ok(());
    };
    if (200..300).contains(&status.status_code) {
        return Ok(());
    }
    if !status.reason.is_empty() {
        Err(status.reason)
    } else if !status.reasons.is_empty() {
        Err(status.reasons.join("; "))
    } else {
        Err(format!("{fallback} (status {})", status.status_code))
    }
}

pub fn decode_create_response(bytes: &[u8]) -> Result<String, String> {
    let reply = es::RootlistModificationResponse::decode(bytes).map_err(|e| e.to_string())?;
    response_error(reply.status, "Playlist creation failed")?;
    let resp = reply.response.ok_or_else(|| "Missing playlist creation response".to_string())?;
    if !resp.success || resp.uri.is_empty() {
        return Err("Spotify did not create the playlist".into());
    }
    Ok(resp.uri)
}

pub fn set_description_request(uri: &str, description: &str) -> Vec<u8> {
    es::PlaylistModificationRequest {
        uri: uri.into(),
        request: Some(pl::ModificationRequest {
            operation: "set".into(),
            attributes: Some(pl::Attributes {
                description: description.into(),
                ..Default::default()
            }),
            ..Default::default()
        }),
    }
    .encode_to_vec()
}

pub fn decode_modify_response(bytes: &[u8]) -> Result<(), String> {
    let reply = es::PlaylistModificationResponse::decode(bytes).map_err(|e| e.to_string())?;
    response_error(reply.status, "Playlist update failed")
}

/// Creates a playlist in the user's rootlist, optionally seeded with `uris` and a `description`.
/// Returns `(created_uri, optional_warning)` so a description update failure does not hide the created URI.
/// Blocks: never call on the UI thread.
#[cfg(not(feature = "preview"))]
pub fn create(name: &str, description: &str, uris: &[String]) -> Result<(String, Option<String>), String> {
    let (name, description) = validate_create(name, description)?;
    let bytes = crate::bridge::call(ROOTLIST_SERVICE, "Modify", &create_request(&name, uris))?;
    let uri = decode_create_response(&bytes)?;
    let warning = if !description.is_empty() {
        match crate::bridge::call(PLAYLIST_SERVICE, "Modify", &set_description_request(&uri, &description))
            .and_then(|b| decode_modify_response(&b))
        {
            Ok(()) => None,
            Err(e) => {
                log::warn!("Created playlist {uri} but failed to set description: {e}");
                Some(format!("Playlist created, but couldn't set description: {e}"))
            }
        }
    } else {
        None
    };
    Ok((uri, warning))
}

pub fn validate_track_uri(uri: &str) -> bool {
    let Some(tail) = uri.strip_prefix("spotify:") else { return false };
    let Some((kind, id)) = tail.split_once(':') else { return false };
    matches!(kind, "track" | "episode" | "local") && !id.is_empty() && !id.chars().any(char::is_whitespace)
        || cfg!(feature = "preview") && kind == "preview" && id.parse::<usize>().is_ok()
}

pub fn validate_add_items(playlist_uri: &str, uris: &[String]) -> Result<(), String> {
    if !super::library::is_playlist(playlist_uri) || super::library::is_liked(playlist_uri) {
        return Err("Target must be a playlist URI".into());
    }
    if uris.is_empty() {
        return Err("No tracks selected to add".into());
    }
    for uri in uris {
        if !validate_track_uri(uri) {
            return Err(format!("Invalid track URI: {uri}"));
        }
    }
    Ok(())
}

pub fn add_items_request(playlist_uri: &str, uris: &[String]) -> Vec<u8> {
    es::PlaylistModificationRequest {
        uri: playlist_uri.into(),
        request: Some(pl::ModificationRequest {
            operation: "add".into(),
            uris: uris.to_vec(),
            ..Default::default()
        }),
    }
    .encode_to_vec()
}

/// Appends `uris` to `playlist_uri` via `PlaylistService.Modify`.
/// Blocks: never call on the UI thread.
#[cfg(not(feature = "preview"))]
pub fn add_items(playlist_uri: &str, uris: &[String]) -> Result<(), String> {
    validate_add_items(playlist_uri, uris)?;
    let bytes = crate::bridge::call(PLAYLIST_SERVICE, "Modify", &add_items_request(playlist_uri, uris))?;
    decode_modify_response(&bytes)
}

#[cfg(test)]
mod tests {
    #[test]
    fn server_items_are_read_in_order() {
        use super::super::wire::{bytes_field, number_field, string_field};
        let mut item = Vec::new();
        string_field(&mut item, 1, "spotify:track:a");
        let mut attrs = Vec::new();
        number_field(&mut attrs, 2, 1_700_000_000_000);
        bytes_field(&mut item, 2, &attrs);
        let mut contents = Vec::new();
        bytes_field(&mut contents, 3, &item);
        let mut second = Vec::new();
        string_field(&mut second, 1, "spotify:episode:b");
        bytes_field(&mut contents, 3, &second);
        let mut top = Vec::new();
        number_field(&mut top, 2, 2);
        bytes_field(&mut top, 5, &contents);
        assert_eq!(super::server_items(&top), vec![("spotify:track:a".to_string(), 1_700_000_000), ("spotify:episode:b".to_string(), 0)]);
        assert!(super::server_items(&[]).is_empty());
    }

    use super::*;

    fn songs(rows: &[(&str, &str, &str, u32)]) -> Vec<String> {
        rows.iter()
            .flat_map(|(title, artist, album, added)| {
                Track { title: (*title).into(), artist: (*artist).into(), album: (*album).into(), added: *added, ..Default::default() }.fields()
            })
            .collect()
    }

    #[test]
    fn arranges_by_search_and_sort() {
        let f = songs(&[("Zelda", "KevinKempt", "Quest", 30), ("annoying", "Pi'erre", "Bourne", 10), ("City", "jack", "Alpha", 20)]);
        assert_eq!(arrange(&f, "", 0), vec![0, 1, 2]);
        assert_eq!(arrange(&f, "", 1), vec![1, 2, 0], "title, ignoring case");
        assert_eq!(arrange(&f, "", 3), vec![2, 1, 0], "album");
        assert_eq!(arrange(&f, "", 4), vec![0, 2, 1], "newest first");
        assert_eq!(arrange(&f, "kevin quest", 0), vec![0], "every word, across title, artist and album");
        assert!(arrange(&f, "nothing", 0).is_empty());
    }

    #[test]
    fn asks_for_a_range_and_keeps_the_uri() {
        let sent = es::PlaylistGetRequest::decode(request("spotify:user:x:collection", 100).as_slice()).unwrap();
        assert_eq!(sent.uri, "spotify:user:x:collection");
        let range = sent.query.unwrap().range.unwrap();
        assert_eq!((range.start, range.length), (100, PAGE));
    }

    #[test]
    fn an_owned_playlist_reveals_the_username() {
        use crate::protos::com::spotify::playlist::proto as pl;
        let owned = |by_self| es::PlaylistGetResponse {
            data: Some(pl::Response {
                playlist: Some(pl::Playlist {
                    playlist_metadata: Some(pl::PlaylistMetadata { owned_by_self: by_self, owner: Some(pl::User { username: "levi".into(), ..Default::default() }), ..Default::default() }),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            ..Default::default()
        };
        let Ok(Reply::Page { owner, .. }) = decode(&owned(true).encode_to_vec(), 0) else { panic!("not a page") };
        assert_eq!(owner.as_deref(), Some("levi"));
        let Ok(Reply::Page { owner, .. }) = decode(&owned(false).encode_to_vec(), 0) else { panic!("not a page") };
        assert_eq!(owner, None, "someone else's playlist says nothing about you");
    }

    #[test]
    fn errors_and_loading_are_told_apart() {
        let empty = es::PlaylistGetResponse::default();
        assert_eq!(decode(&empty.encode_to_vec(), 0), Err("No playlist data".into()));
        let loading = es::PlaylistGetResponse { data: Some(crate::protos::com::spotify::playlist::proto::Response { loading_contents: true, ..Default::default() }), ..Default::default() };
        assert_eq!(decode(&loading.encode_to_vec(), 0), Ok(Reply::Loading));
    }

    #[test]
    fn marks_recommended_items_separately() {
        use crate::protos::com::spotify::playlist::proto as pl;
        let item = |uri: &str, is_recommendation| pl::Item {
            uri: uri.into(),
            is_recommendation,
            ..Default::default()
        };
        let reply = es::PlaylistGetResponse {
            data: Some(pl::Response {
                item: vec![item("spotify:track:1", false), item("spotify:track:2", true)],
                ..Default::default()
            }),
            ..Default::default()
        };
        let Ok(Reply::Page { tracks, .. }) = decode(&reply.encode_to_vec(), 0) else { panic!("not a page") };
        assert!(!tracks[0].recommended && tracks[1].recommended);
        assert_eq!(tracks[1].clone().fields()[10], "1");
    }

    #[test]
    fn song_lengths_arrive_in_seconds() {
        use crate::protos::com::spotify::cosmos::util::proto::TrackMetadata;
        use crate::protos::com::spotify::playlist::proto as pl;
        let reply = es::PlaylistGetResponse {
            data: Some(pl::Response {
                item: vec![pl::Item { uri: "spotify:track:1".into(), track_metadata: Some(TrackMetadata { length: 201, ..Default::default() }), ..Default::default() }],
                ..Default::default()
            }),
            ..Default::default()
        };
        let Ok(Reply::Page { tracks, .. }) = decode(&reply.encode_to_vec(), 0) else { panic!("not a page") };
        assert_eq!(tracks[0].duration_ms, 201_000);
    }

    #[test]
    fn rejects_non_playable_items() {
        for uri in ["spotify:album:a", "spotify:playlist:b", "spotify:track:", "spotify:track:bad id", "spotify:delimiter", "https://example.com"] {
            assert!(!validate_track_uri(uri), "accepted {uri}");
        }
        assert!(validate_track_uri("spotify:episode:abc"));
        assert!(validate_track_uri("spotify:track:abc"));
    }

    #[test]
    fn validates_playlist_name_and_description() {
        assert!(validate_create("", "").is_err());
        assert!(validate_create("   ", "").is_err());
        let too_long_name = "a".repeat(MAX_NAME_CHARS + 1);
        assert!(validate_create(&too_long_name, "").is_err());
        let exact_name = "a".repeat(MAX_NAME_CHARS);
        assert_eq!(validate_create(&exact_name, "").unwrap().0.len(), MAX_NAME_CHARS);
        let too_long_desc = "d".repeat(MAX_DESCRIPTION_CHARS + 1);
        assert!(validate_create("Mix", &too_long_desc).is_err());
        assert_eq!(validate_create("  My Mix  ", "  Late night  "), Ok(("My Mix".into(), "Late night".into())));
    }

    #[test]
    fn encodes_rootlist_create_and_description_requests() {
        let uris = vec!["spotify:track:1".into(), "spotify:track:2".into()];
        let sent = es::RootlistModificationRequest::decode(create_request("Queue Save", &uris).as_slice()).unwrap();
        assert_eq!(sent.folder_uri, "");
        let req = sent.request.unwrap();
        assert_eq!(req.operation, "create");
        assert_eq!(req.before, "start");
        assert_eq!(req.name, "Queue Save");
        assert!(req.playlist);
        assert_eq!(req.uris, uris);

        let desc = es::PlaylistModificationRequest::decode(set_description_request("spotify:playlist:abc", "From queue").as_slice()).unwrap();
        assert_eq!(desc.uri, "spotify:playlist:abc");
        let dreq = desc.request.unwrap();
        assert_eq!(dreq.operation, "set");
        assert_eq!(dreq.attributes.unwrap().description, "From queue");
    }

    #[test]
    fn decodes_rootlist_create_response_and_errors() {
        let ok = es::RootlistModificationResponse {
            status: Some(es::ResponseStatus { status_code: 200, ..Default::default() }),
            response: Some(pl::ModificationResponse { success: true, uri: "spotify:playlist:new123".into() }),
        };
        assert_eq!(decode_create_response(&ok.encode_to_vec()), Ok("spotify:playlist:new123".into()));

        let bad_status = es::RootlistModificationResponse {
            status: Some(es::ResponseStatus { status_code: 403, reason: "Forbidden".into(), ..Default::default() }),
            response: None,
        };
        assert_eq!(decode_create_response(&bad_status.encode_to_vec()), Err("Forbidden".into()));

        let unsuccessful = es::RootlistModificationResponse {
            status: Some(es::ResponseStatus { status_code: 200, ..Default::default() }),
            response: Some(pl::ModificationResponse { success: false, uri: String::new() }),
        };
        assert!(decode_create_response(&unsuccessful.encode_to_vec()).is_err());
    }

    #[test]
    fn validates_and_encodes_add_items_request() {
        let uris = vec!["spotify:track:1".into(), "spotify:episode:2".into()];
        assert!(validate_add_items("spotify:playlist:abc", &uris).is_ok());
        assert!(validate_add_items("spotify:album:abc", &uris).is_err());
        assert!(validate_add_items("spotify:user:levi:collection", &uris).is_err());
        assert!(validate_add_items("spotify:playlist:abc", &[]).is_err());
        assert!(validate_add_items("spotify:playlist:abc", &["spotify:delimiter".into()]).is_err());
        assert!(validate_add_items("spotify:playlist:abc", &["spotify:track:".into()]).is_err());

        let sent = es::PlaylistModificationRequest::decode(add_items_request("spotify:playlist:abc", &uris).as_slice()).unwrap();
        assert_eq!(sent.uri, "spotify:playlist:abc");
        let req = sent.request.unwrap();
        assert_eq!(req.operation, "add");
        assert_eq!(req.uris, uris);
        assert!(req.before.is_empty() && req.after.is_empty());

        let ok = es::PlaylistModificationResponse {
            status: Some(es::ResponseStatus { status_code: 200, ..Default::default() }),
            added_row_ids: vec!["row1".into()],
        };
        assert_eq!(decode_modify_response(&ok.encode_to_vec()), Ok(()));

        let err = es::PlaylistModificationResponse {
            status: Some(es::ResponseStatus { status_code: 400, reason: "Bad request".into(), ..Default::default() }),
            added_row_ids: vec![],
        };
        assert_eq!(decode_modify_response(&err.encode_to_vec()), Err("Bad request".into()));
    }
}
