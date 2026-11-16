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
    /// The tracks, and the offset of the next page if there is one.
    Page { tracks: Vec<Track>, next: Option<i32> },
}

/// Asks for a page, with just the fields the UI shows. Liked Songs goes through under the URI the
/// library gave: the service rejects the generic `spotify:collection:tracks`.
pub fn request(uri: &str, offset: i32) -> Vec<u8> {
    es::PlaylistGetRequest {
        uri: uri.into(),
        query: Some(es::PlaylistQuery {
            range: Some(es::PlaylistRange { start: offset.max(0), length: PAGE }),
            ..Default::default()
        }),
        policy: Some(policy::PlaylistRequestDecorationPolicy {
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
    Ok(Reply::Page { tracks, next: more.then_some(end as i32) })
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
    fn errors_and_loading_are_told_apart() {
        let empty = es::PlaylistGetResponse::default();
        assert_eq!(decode(&empty.encode_to_vec(), 0), Err("No playlist data".into()));
        let loading = es::PlaylistGetResponse { data: Some(crate::protos::com::spotify::playlist::proto::Response { loading_contents: true, ..Default::default() }), ..Default::default() };
        assert_eq!(decode(&loading.encode_to_vec(), 0), Ok(Reply::Loading));
    }
}
