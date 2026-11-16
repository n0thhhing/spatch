//! Your Library: YourLibraryService, the same list Spotify's Library tab shows.

use super::image_url;
use crate::protos::spotify::your_library::esperanto::proto as es;
use crate::protos::spotify::your_library::proto::your_library_decorated_entity::Entity;
use prost::Message;

pub const SERVICE: &str = "spotify.your_library_esperanto.proto.YourLibraryService";

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Item {
    pub name: String,
    pub uri: String,
    /// "Playlist • creator", "Album • artist", "Artist", ...
    pub subtitle: String,
    pub image: String,
    pub kind: Kind,
}

/// What an item is, in the order of the Library's filter chips.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub enum Kind {
    Playlist,
    Album,
    Artist,
    Podcast,
    #[default]
    Other,
}

impl Kind {
    /// The chip index an item shows under; `None` for Other.
    pub fn chip(self) -> Option<i32> {
        match self {
            Kind::Playlist => Some(0),
            Kind::Album => Some(1),
            Kind::Artist => Some(2),
            Kind::Podcast => Some(3),
            Kind::Other => None,
        }
    }
}

/// The first `length` items, in Spotify's own order (pinned first).
pub fn request(length: i32) -> Vec<u8> {
    es::YourLibraryRequest {
        header: Some(es::YourLibraryRequestHeader { length, total_count: true, ..Default::default() }),
        ..Default::default()
    }
    .encode_to_vec()
}

pub fn decode(bytes: &[u8]) -> Result<Vec<Item>, String> {
    let reply = es::YourLibraryResponse::decode(bytes).map_err(|e| e.to_string())?;
    if !reply.error.is_empty() {
        return Err(reply.error);
    }
    let with = |kind: &str, who: &str| if who.is_empty() { kind.to_string() } else { format!("{kind} • {who}") };
    Ok(reply
        .pinned_entity
        .iter()
        .chain(&reply.entity)
        .filter_map(|e| {
            let info = e.entity_info.as_ref()?;
            let (kind, subtitle) = match &e.entity {
                Some(Entity::Playlist(p)) => (Kind::Playlist, with("Playlist", &p.creator_name)),
                Some(Entity::Album(a)) => (Kind::Album, with("Album", &a.artist_name)),
                Some(Entity::Artist(_)) => (Kind::Artist, "Artist".into()),
                Some(Entity::Show(_)) => (Kind::Podcast, "Podcast".into()),
                Some(Entity::Book(b)) => (Kind::Other, with("Audiobook", &b.author_name)),
                Some(Entity::Folder(_)) => (Kind::Other, "Folder".into()),
                _ => (Kind::Other, String::new()),
            };
            let kind = if is_playlist(&info.uri) { Kind::Playlist } else { kind };
            Some(Item { name: info.name.clone(), uri: info.uri.clone(), subtitle, image: image_url(&info.image_uri), kind })
        })
        .collect())
}

/// What the Library page shows: items under chip `chip` (-1 for all) whose name or subtitle contains
/// `query` (ignoring case), in Spotify's order or alphabetical with Liked Songs kept on top.
pub fn select<'a>(items: &'a [Item], chip: i32, query: &str, alphabetical: bool, limit: usize) -> Vec<&'a Item> {
    let needle = query.trim().to_lowercase();
    let mut found: Vec<&Item> = items
        .iter()
        .filter(|i| chip == -1 || i.kind.chip() == Some(chip))
        .filter(|i| needle.is_empty() || i.name.to_lowercase().contains(&needle) || i.subtitle.to_lowercase().contains(&needle))
        .collect();
    if alphabetical {
        // Stable, so equal names keep Spotify's order; the key is false for Liked Songs, so it sorts first.
        found.sort_by_cached_key(|i| (!is_liked(&i.uri), i.name.to_lowercase()));
    }
    found.truncate(limit);
    found
}

/// Liked Songs, under whichever of Spotify's URI spellings the library uses.
pub fn is_liked(uri: &str) -> bool {
    matches!(uri, "spotify:collection" | "spotify:collection:tracks" | "spotify:internal:collection:tracks")
        || uri.strip_prefix("spotify:user:").is_some_and(|tail| {
            tail.split_once(':').is_some_and(|(_, kind)| matches!(kind, "collection" | "collection:tracks"))
        })
}

pub fn is_playlist(uri: &str) -> bool {
    is_liked(uri)
        || uri.strip_prefix("spotify:playlist:").is_some_and(|id| !id.is_empty())
        || uri.strip_prefix("spotify:user:").is_some_and(|tail| tail.split_once(":playlist:").is_some_and(|(_, id)| !id.is_empty()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protos::spotify::your_library::proto as yl;

    fn entity(name: &str, uri: &str, entity: Option<Entity>) -> yl::YourLibraryDecoratedEntity {
        yl::YourLibraryDecoratedEntity {
            entity_info: Some(yl::YourLibraryEntityInfo { name: name.into(), uri: uri.into(), image_uri: "spotify:image:ab".into(), ..Default::default() }),
            entity,
        }
    }

    #[test]
    fn decodes_pinned_first_with_subtitles_and_kinds() {
        let reply = es::YourLibraryResponse {
            pinned_entity: vec![entity("Liked Songs", "spotify:collection", Some(Entity::Playlist(Default::default())))],
            entity: vec![
                entity("moron", "spotify:playlist:1", Some(Entity::Playlist(yl::YourLibraryPlaylistExtraInfo { creator_name: "levi".into(), ..Default::default() }))),
                entity("we went no contact", "spotify:album:2", Some(Entity::Album(yl::YourLibraryAlbumExtraInfo { artist_name: "pooterdabooter".into(), ..Default::default() }))),
            ],
            ..Default::default()
        };
        let items = decode(&reply.encode_to_vec()).unwrap();
        assert_eq!(items.iter().map(|i| i.subtitle.as_str()).collect::<Vec<_>>(), ["Playlist", "Playlist • levi", "Album • pooterdabooter"]);
        assert_eq!(items[2].image, "https://i.scdn.co/image/ab");
        assert_eq!(items.iter().map(|i| i.kind.chip()).collect::<Vec<_>>(), [Some(0), Some(0), Some(1)]);
    }

    #[test]
    fn undecorated_playlists_are_still_playlists() {
        let reply = es::YourLibraryResponse { entity: vec![entity("P", "spotify:user:test:playlist:123", None)], ..Default::default() };
        assert_eq!(decode(&reply.encode_to_vec()).unwrap()[0].kind, Kind::Playlist);
        assert!(is_liked("spotify:user:test:collection:tracks"));
        assert!(!is_playlist("spotify:track:123") && !is_playlist("spotify:collection:your-episodes"));
    }

    #[test]
    fn select_filters_searches_and_sorts() {
        let item = |name: &str, uri: &str, kind| Item { name: name.into(), uri: uri.into(), subtitle: "x".into(), image: String::new(), kind };
        let items = vec![
            item("zebra", "spotify:playlist:1", Kind::Playlist),
            item("Liked Songs", "spotify:user:me:collection", Kind::Playlist),
            item("Apple", "spotify:album:2", Kind::Album),
            item("mango", "spotify:playlist:3", Kind::Playlist),
        ];
        assert_eq!(select(&items, 0, "", false, 10).len(), 3);
        assert_eq!(select(&items, -1, "", false, 2).len(), 2);
        assert_eq!(select(&items, -1, "  APP", false, 10)[0].name, "Apple");
        let names: Vec<_> = select(&items, -1, "", true, 10).iter().map(|i| i.name.as_str()).collect();
        assert_eq!(names, ["Liked Songs", "Apple", "mango", "zebra"]);
    }

    #[test]
    fn spotify_errors_come_through() {
        let reply = es::YourLibraryResponse { error: "not logged in".into(), ..Default::default() };
        assert_eq!(decode(&reply.encode_to_vec()), Err("not logged in".into()));
    }
}
