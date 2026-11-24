//! RecentlyPlayedService: what you listened to lately, as Spotify's own Home shows it.

use super::image_url;
use super::library::{Item, Kind};
use crate::bridge;
use crate::protos::com::spotify::cosmos::util::policy::proto as cp;
use crate::protos::com::spotify::cosmos::util::proto::ImageGroup;
use crate::protos::com::spotify::playlist::policy::proto::PlaylistDecorationPolicy;
use crate::protos::com::spotify::recently_played_esperanto::proto as es;
use es::recently_played_context::Context;
use prost::Message;
use std::time::Duration;

pub const SERVICE: &str = "spotify.recently_played_esperanto.proto.RecentlyPlayedService";

/// Asks for the last `limit` contexts with just the names, links and covers the UI shows.
/// Single tracks are left out: Home wants playlists, albums, artists and shows.
pub fn request(limit: u32) -> Vec<u8> {
    let artist = cp::ArtistDecorationPolicy { link: true, name: true, portraits: true, ..Default::default() };
    let album = cp::AlbumDecorationPolicy { link: true, name: true, covers: true, ..Default::default() };
    let show = cp::ShowDecorationPolicy { link: true, name: true, covers: true, ..Default::default() };
    es::GetRecentlyPlayedRequest {
        limit,
        exclude_track_contexts: true,
        include_collection_songs: true,
        album_policy: Some(es::RecentlyPlayedAlbumDecorationPolicy { album_policy: Some(album), artists_policy: Some(artist), ..Default::default() }),
        artist_policy: Some(es::RecentlyPlayedArtistDecorationPolicy { artist_policy: Some(artist), ..Default::default() }),
        show_policy: Some(es::RecentlyPlayedShowDecorationPolicy { show_policy: Some(show), ..Default::default() }),
        playlist_policy: Some(es::RecentlyPlayedPlaylistDecorationPolicy {
            playlist_policy: Some(PlaylistDecorationPolicy { link: true, name: true, picture: true, ..Default::default() }),
            ..Default::default()
        }),
        collection_tracks_policy: Some(es::RecentlyPlayedCollectionTracksDecorationPolicy { link: true, ..Default::default() }),
        ..Default::default()
    }
    .encode_to_vec()
}

fn cover(group: &Option<ImageGroup>) -> String {
    group
        .as_ref()
        .map(|g| [&g.large_link, &g.standard_link, &g.xlarge_link, &g.small_link].into_iter().find(|l| !l.is_empty()).cloned().unwrap_or_default())
        .map_or_else(String::new, |link| image_url(&link))
}

/// The contexts in the reply, newest first, and whether Spotify says the list is complete.
pub fn decode(bytes: &[u8]) -> Result<(Vec<Item>, bool), String> {
    let reply = es::GetRecentlyPlayedResponse::decode(bytes).map_err(|e| e.to_string())?;
    if !reply.error.is_empty() {
        return Err(reply.error);
    }
    let items = reply
        .context
        .into_iter()
        .filter_map(|c| match c.context? {
            Context::Playlist(p) => {
                let m = p.playlist_metadata?;
                Some(Item { name: m.name, uri: m.link, subtitle: "Playlist".into(), image: cover(&m.pictures), kind: Kind::Playlist, ..Default::default() })
            }
            Context::Album(a) => {
                let m = a.album_metadata?;
                let by = m.artists.first().map(|x| x.name.clone()).unwrap_or_default();
                Some(Item { name: m.name, uri: m.link, subtitle: if by.is_empty() { "Album".into() } else { format!("Album • {by}") }, image: cover(&m.covers), kind: Kind::Album, ..Default::default() })
            }
            Context::Artist(a) => {
                let m = a.artist_metadata?;
                Some(Item { name: m.name, uri: m.link, subtitle: "Artist".into(), image: cover(&m.portraits), kind: Kind::Artist, ..Default::default() })
            }
            Context::Show(s) => {
                let m = s.show_metadata?;
                Some(Item { name: m.name, uri: m.link, subtitle: "Podcast".into(), image: cover(&m.covers), kind: Kind::Podcast, ..Default::default() })
            }
            Context::CollectionTracks(t) => Some(Item { name: "Liked Songs".into(), uri: t.link, subtitle: "Playlist".into(), image: String::new(), kind: Kind::Playlist, ..Default::default() }),
            _ => None,
        })
        .filter(|item| !item.uri.is_empty() && !item.name.is_empty())
        .collect();
    Ok((items, reply.loaded))
}

/// Opens the Stream, waits for Spotify to finish loading (at most `wait`), keeps the latest list, and closes it.
/// Blocks: never on the UI thread.
pub fn fetch(limit: u32, wait: Duration) -> Result<Vec<Item>, String> {
    bridge::subscribe_until(SERVICE, "Stream", &request(limit), wait, "Spotify didn’t answer", |b| match decode(b) {
        Ok((items, loaded)) => (Ok(items), loaded),
        Err(e) => (Err(e), true),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protos::com::spotify::cosmos::util::proto::{AlbumArtistMetadata, AlbumMetadata};
    use crate::protos::com::spotify::playlist::proto::PlaylistMetadata;

    fn context(c: Context) -> es::RecentlyPlayedContext {
        es::RecentlyPlayedContext { context: Some(c) }
    }

    #[test]
    fn decodes_each_kind_with_names_covers_and_links() {
        let reply = es::GetRecentlyPlayedResponse {
            loaded: true,
            context: vec![
                context(Context::Playlist(es::RecentlyPlayedPlaylist {
                    playlist_metadata: Some(PlaylistMetadata {
                        name: "walkies".into(),
                        link: "spotify:playlist:1".into(),
                        pictures: Some(ImageGroup { large_link: "spotify:image:ab".into(), ..Default::default() }),
                        ..Default::default()
                    }),
                    ..Default::default()
                })),
                context(Context::Album(es::RecentlyPlayedAlbum {
                    album_metadata: Some(AlbumMetadata {
                        name: "Between Stations".into(),
                        link: "spotify:album:2".into(),
                        artists: vec![AlbumArtistMetadata { name: "Northbound".into(), ..Default::default() }],
                        ..Default::default()
                    }),
                    ..Default::default()
                })),
                context(Context::CollectionTracks(es::RecentlyPlayedCollectionTracks { link: "spotify:user:x:collection".into(), ..Default::default() })),
                context(Context::Album(es::RecentlyPlayedAlbum::default())),
            ],
            ..Default::default()
        };
        let (items, loaded) = decode(&reply.encode_to_vec()).unwrap();
        assert!(loaded);
        assert_eq!(items.len(), 3, "the empty album is dropped");
        assert_eq!((items[0].name.as_str(), items[0].image.as_str(), items[0].kind), ("walkies", "https://i.scdn.co/image/ab", Kind::Playlist));
        assert_eq!((items[1].subtitle.as_str(), items[1].kind), ("Album • Northbound", Kind::Album));
        assert_eq!(items[2].name, "Liked Songs");
    }

    #[test]
    fn errors_come_through_and_the_request_asks_for_what_it_shows() {
        let bad = es::GetRecentlyPlayedResponse { error: "offline".into(), ..Default::default() };
        assert_eq!(decode(&bad.encode_to_vec()), Err("offline".into()));
        let sent = es::GetRecentlyPlayedRequest::decode(request(12).as_slice()).unwrap();
        assert_eq!(sent.limit, 12);
        assert!(sent.exclude_track_contexts && sent.include_collection_songs);
        assert!(sent.album_policy.unwrap().album_policy.unwrap().covers);
        assert!(sent.playlist_policy.unwrap().playlist_policy.unwrap().picture);
    }
}
