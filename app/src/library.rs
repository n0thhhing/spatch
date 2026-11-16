//! Your Library: Spotify's YourLibraryService, the same list Spotify's Library tab shows.

use crate::player::image_url;
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
    Ok(reply
        .pinned_entity
        .iter()
        .chain(&reply.entity)
        .filter_map(|e| {
            let info = e.entity_info.as_ref()?;
            let subtitle = match &e.entity {
                Some(Entity::Playlist(p)) => with("Playlist", &p.creator_name),
                Some(Entity::Album(a)) => with("Album", &a.artist_name),
                Some(Entity::Artist(_)) => "Artist".into(),
                Some(Entity::Show(_)) => "Podcast".into(),
                Some(Entity::Book(b)) => with("Audiobook", &b.author_name),
                Some(Entity::Folder(_)) => "Folder".into(),
                _ => String::new(),
            };
            Some(Item { name: info.name.clone(), uri: info.uri.clone(), subtitle, image: image_url(&info.image_uri) })
        })
        .collect())
}

fn with(kind: &str, who: &str) -> String {
    if who.is_empty() { kind.into() } else { format!("{kind} • {who}") }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protos::spotify::your_library::proto as yl;

    fn entity(name: &str, uri: &str, entity: Entity) -> yl::YourLibraryDecoratedEntity {
        yl::YourLibraryDecoratedEntity {
            entity_info: Some(yl::YourLibraryEntityInfo { name: name.into(), uri: uri.into(), image_uri: "spotify:image:ab".into(), ..Default::default() }),
            entity: Some(entity),
        }
    }

    #[test]
    fn decodes_pinned_first_with_subtitles() {
        let reply = es::YourLibraryResponse {
            pinned_entity: vec![entity("Liked Songs", "spotify:collection", Entity::Playlist(yl::YourLibraryPlaylistExtraInfo::default()))],
            entity: vec![
                entity("moron", "spotify:playlist:1", Entity::Playlist(yl::YourLibraryPlaylistExtraInfo { creator_name: "levi".into(), ..Default::default() })),
                entity("we went no contact", "spotify:album:2", Entity::Album(yl::YourLibraryAlbumExtraInfo { artist_name: "pooterdabooter".into(), ..Default::default() })),
            ],
            ..Default::default()
        };
        let items = decode(&reply.encode_to_vec()).unwrap();
        assert_eq!(items.iter().map(|i| i.subtitle.as_str()).collect::<Vec<_>>(), ["Playlist", "Playlist • levi", "Album • pooterdabooter"]);
        assert_eq!(items[1].uri, "spotify:playlist:1");
        assert_eq!(items[2].image, "https://i.scdn.co/image/ab");
    }

    #[test]
    fn spotify_errors_come_through() {
        let reply = es::YourLibraryResponse { error: "not logged in".into(), ..Default::default() };
        assert_eq!(decode(&reply.encode_to_vec()), Err("not logged in".into()));
    }
}
