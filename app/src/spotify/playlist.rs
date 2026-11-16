//! PlaylistDataService: the tracks of a playlist, Liked Songs included.

use super::image_url;
use crate::protos::{
    com::spotify::cosmos::util::policy::proto as metadata, com::spotify::playlist::policy::proto as policy,
    spotify::playlist::esperanto::proto as es,
};
use prost::Message;

pub const SERVICE: &str = "spotify.playlist_esperanto.proto.PlaylistDataService";
/// Tracks per request.
pub const PAGE: i32 = 100;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Track {
    pub title: String,
    pub artist: String,
    pub uri: String,
    pub image: String,
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
                track: Some(metadata::TrackDecorationPolicy { name: true, link: true, ..Default::default() }),
                artist: Some(metadata::ArtistDecorationPolicy { name: true, ..Default::default() }),
                ..Default::default()
            }),
            item: Some(policy::PlaylistItemDecorationPolicy {
                uri: true,
                metadata: Some(policy::ItemMetadataPolicy { name: true, image: true, ..Default::default() }),
                ..Default::default()
            }),
            ..Default::default()
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
    let tracks = data
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
            Track { title, artist, uri: item.uri, image: item.metadata.as_ref().map_or_else(String::new, |m| image_url(&m.image)) }
        })
        .collect();
    Ok(Reply::Page { tracks, next: more.then_some(end as i32), owner })
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
