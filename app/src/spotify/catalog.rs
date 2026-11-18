//! Catalog search results returned by Spotify's searchview/v3/search.
use crate::protos::com::spotify::searchview::proto as search;
use prost::Message;
use std::collections::HashSet;

pub fn decode(bytes: &[u8]) -> Result<Vec<String>, prost::DecodeError> {
    let response = search::MainViewResponse::decode(bytes)?;
    let mut fields = vec![response.next_page_token];
    let mut seen = HashSet::new();
    for entity in response.hits {
        append(entity, &mut fields, &mut seen);
    }
    Ok(fields)
}

fn append(entity: search::Entity, fields: &mut Vec<String>, seen: &mut HashSet<String>) {
    use search::entity::Entity;
    let (subtitle, category, duration) = match entity.entity {
        Some(Entity::Section(section)) => {
            for item in section.items {
                if let Some(search::section_item::Item::GenericItem(item)) = item.item {
                    if let Some(entity) = item.entity {
                        append(entity, fields, seen);
                    }
                }
            }
            return;
        }
        Some(Entity::Track(track)) => (
            track.track_artists.into_iter().map(|artist| artist.name).collect::<Vec<_>>().join(", "),
            -1, track.duration,
        ),
        Some(Entity::Artist(_)) => ("Artist".into(), 2, None),
        Some(Entity::Album(album)) => (album.artist_names.join(", "), 1, None),
        Some(Entity::Playlist(playlist)) => (playlist.owner_name, 0, None),
        Some(Entity::AudioShow(show)) => (show.publisher_name, 3, None),
        Some(Entity::AudioEpisode(episode)) => (episode.show_name, -1, episode.duration),
        None => return,
    };
    if entity.name.is_empty()
        || !["spotify:track:", "spotify:episode:", "spotify:artist:", "spotify:album:",
            "spotify:playlist:", "spotify:show:"].iter().any(|prefix| entity.uri.starts_with(prefix))
        || !seen.insert(entity.uri.clone())
    {
        return;
    }
    let duration = duration.map_or(0, |duration| {
        duration.seconds.saturating_mul(1000).saturating_add(i64::from(duration.nanos) / 1_000_000).max(0)
    });
    fields.extend([
        entity.name, subtitle, entity.uri, super::image_url(&entity.image_uri),
        category.to_string(), duration.to_string(),
    ]);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_sections_deduplicates_and_preserves_paging() {
        let track = search::Entity {
            uri: "spotify:track:one".into(),
            name: "Song".into(),
            image_uri: "spotify:image:cover".into(),
            entity: Some(search::entity::Entity::Track(search::Track {
                track_artists: vec![search::RelatedEntity { name: "Artist".into() }],
                duration: Some(crate::protos::com::google::protobuf::Duration { seconds: 201, nanos: 500_000_000 }),
            })),
        };
        let response = search::MainViewResponse {
            next_page_token: "page-two".into(),
            hits: vec![track.clone(), search::Entity {
                entity: Some(search::entity::Entity::Section(search::Section {
                    items: vec![search::SectionItem {
                        item: Some(search::section_item::Item::GenericItem(search::GenericItem { entity: Some(track) })),
                    }],
                })),
                ..Default::default()
            }, search::Entity { uri: "https://invalid".into(), ..Default::default() }],
        };
        assert_eq!(decode(&response.encode_to_vec()).unwrap(), [
            "page-two", "Song", "Artist", "spotify:track:one",
            "https://i.scdn.co/image/cover", "-1", "201500",
        ]);
        assert!(decode(&[255]).is_err());
    }
}
