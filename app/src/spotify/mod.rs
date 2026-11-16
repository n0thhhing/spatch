//! Spotify's own services, typed: what each request looks like and how its reply decodes.
//! Nothing here touches the network; `bridge` carries the bytes.

pub mod collection;
pub mod library;
pub mod metadata;
pub mod player;
pub mod playlist;
pub mod recent;

/// Spotify's image URIs (`spotify:image:<id>`) as their public CDN URL; anything else unchanged.
pub fn image_url(uri: &str) -> String {
    match uri.strip_prefix("spotify:image:") {
        Some(id) if !id.is_empty() => format!("https://i.scdn.co/image/{id}"),
        _ => uri.to_string(),
    }
}
