//! Spotify's extended-metadata service (POST extended-metadata/v0/extended-metadata), the one the core itself asks
//! for song details. Spatch asks it directly when the core answers nothing: the core fills the request's country and
//! catalogue from the account's product state, and on some accounts those are missing.
//!
//! BatchedEntityRequest: header = 1 {country = 1, catalogue = 2}, entity_request = 2 {entity_uri = 1,
//! query = 2 {extension_kind = 1}}. BatchedExtensionResponse: extended_metadata = 2 {extension_kind = 2,
//! extension_data = 3 {header = 1 {status_code = 1}, entity_uri = 2, extension_data = 3 (Any: value = 2)}}.

use super::wire::{bytes_field, fields, first, number_field, string_field};

/// ExtensionKind.TRACK_V4: a spotify.metadata.Track.
pub const TRACK_V4: u64 = 10;

/// `header`: (country, catalogue), or None to send no header at all. A 16-byte task id goes with it, as the core sends.
pub fn request(uris: &[String], kind: u64, header: Option<(&str, &str)>) -> Vec<u8> {
    let mut out = Vec::new();
    if let Some((country, catalogue)) = header {
        let mut h = Vec::new();
        if !country.is_empty() {
            string_field(&mut h, 1, country);
        }
        if !catalogue.is_empty() {
            string_field(&mut h, 2, catalogue);
        }
        bytes_field(&mut h, 3, &task_id());
        bytes_field(&mut out, 1, &h);
    }
    for uri in uris {
        let mut query = Vec::new();
        number_field(&mut query, 1, kind);
        let mut entity = Vec::new();
        string_field(&mut entity, 1, uri);
        bytes_field(&mut entity, 2, &query);
        bytes_field(&mut out, 2, &entity);
    }
    out
}

fn task_id() -> [u8; 16] {
    use std::hash::{BuildHasher, Hasher};
    let mut id = [0u8; 16];
    for half in id.chunks_mut(8) {
        let mut h = std::collections::hash_map::RandomState::new().build_hasher();
        h.write_u128(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos()));
        half.copy_from_slice(&h.finish().to_le_bytes());
    }
    id
}

/// Each entity in the reply: its link, the status the server gave it, and the payload when that status is 2xx.
pub fn decode(bytes: &[u8]) -> Vec<(String, u64, Option<Vec<u8>>)> {
    let mut out = Vec::new();
    for (n, array) in fields(bytes) {
        if n != 2 {
            continue;
        }
        for (m, data) in fields(array.bytes()) {
            if m != 3 {
                continue;
            }
            let data = fields(data.bytes());
            let status = first(&data, 1).and_then(|h| first(&fields(h.bytes()), 1)).map_or(0, |s| s.number());
            let uri = first(&data, 2).map(|u| u.text()).unwrap_or_default();
            let payload = first(&data, 3).and_then(|any| first(&fields(any.bytes()), 2)).map(|v| v.bytes().to_vec());
            out.push((uri, status, payload.filter(|_| (200..300).contains(&status))));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::super::wire::{bytes_field, fields, first, number_field, string_field};

    #[test]
    fn requests_are_built_and_replies_read() {
        let req = super::request(&["spotify:track:a".into()], super::TRACK_V4, Some(("US", "free")));
        let top = fields(&req);
        let header = fields(first(&top, 1).unwrap().bytes());
        assert_eq!(first(&header, 1).unwrap().text(), "US");
        assert_eq!(first(&header, 2).unwrap().text(), "free");
        assert_eq!(first(&header, 3).unwrap().bytes().len(), 16);
        assert!(first(&fields(&super::request(&[], super::TRACK_V4, None)), 1).is_none());
        let entity = fields(first(&top, 2).unwrap().bytes());
        assert_eq!(first(&entity, 1).unwrap().text(), "spotify:track:a");
        assert_eq!(first(&fields(first(&entity, 2).unwrap().bytes()), 1).unwrap().number(), 10);

        let item = |uri: &str, status: u64, value: &[u8]| {
            let mut h = Vec::new();
            number_field(&mut h, 1, status);
            let mut any = Vec::new();
            string_field(&mut any, 1, "type.googleapis.com/spotify.metadata.Track");
            bytes_field(&mut any, 2, value);
            let mut d = Vec::new();
            bytes_field(&mut d, 1, &h);
            string_field(&mut d, 2, uri);
            bytes_field(&mut d, 3, &any);
            d
        };
        let mut array = Vec::new();
        number_field(&mut array, 2, 10);
        bytes_field(&mut array, 3, &item("spotify:track:a", 200, b"\x12\x02hi"));
        bytes_field(&mut array, 3, &item("spotify:track:b", 403, b"x"));
        let mut reply = Vec::new();
        bytes_field(&mut reply, 2, &array);
        let got = super::decode(&reply);
        assert_eq!(got.len(), 2);
        assert_eq!(got[0], ("spotify:track:a".into(), 200, Some(b"\x12\x02hi".to_vec())));
        assert_eq!(got[1], ("spotify:track:b".into(), 403, None));
    }
}
