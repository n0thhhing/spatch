//! Search over what Spotify's core knows on this phone: your library, plus the core's own offline index
//! (OfflineSearchService.Query: songs, albums, playlists and podcast episodes it has locally: liked songs,
//! downloads, cached items). The core has no catalogue search; that goes over a web API outside it, so this finds
//! what you've saved or played, not everything on Spotify.
//!
//! OfflineSearchRequest { string query = 1; int32 limit = 2; bool include_chapters = 3 }
//! OfflineSearchResponse { repeated OfflineAlbum albums = 1; repeated OfflineAudioEpisode audio_episodes = 2;
//!   repeated OfflinePlaylist playlists = 3; repeated OfflineTrack tracks = 4 } (small messages, read here by hand).

use super::{image_url, library};
use crate::bridge;

pub const SERVICE: &str = "spotify.search_esperanto.proto.OfflineSearchService";

/// One result: the section it belongs to, what to show, and the library chip for its icon shape
/// (0 playlist, 1 album, 2 artist, 3 podcast, -1 a song).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Hit {
    pub section: &'static str,
    pub title: String,
    pub subtitle: String,
    pub uri: String,
    pub image: String,
    pub category: i32,
}

/// How many strings each hit takes in the UI's arrays.
pub const FIELDS: usize = 6;

impl Hit {
    /// section, title, subtitle, uri, image, category.
    pub fn fields(self) -> [String; FIELDS] {
        [self.section.into(), self.title, self.subtitle, self.uri, self.image, self.category.to_string()]
    }
}

fn varint(out: &mut Vec<u8>, mut v: u64) {
    loop {
        let byte = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

pub fn request(query: &str, limit: i32) -> Vec<u8> {
    let mut out = vec![0x0a];
    varint(&mut out, query.len() as u64);
    out.extend_from_slice(query.as_bytes());
    out.push(0x10);
    varint(&mut out, limit.max(0) as u64);
    out
}

/// A protobuf field's value: a number (not needed here), or the bytes of a nested message or string.
enum Value<'a> {
    Number,
    Bytes(&'a [u8]),
}

/// The fields of one message, in order; stops quietly at bytes that aren't valid.
fn fields(mut bytes: &[u8]) -> Vec<(u32, Value<'_>)> {
    fn read_varint(bytes: &mut &[u8]) -> Option<u64> {
        let (mut v, mut shift) = (0u64, 0);
        loop {
            let (&b, rest) = bytes.split_first()?;
            *bytes = rest;
            v |= u64::from(b & 0x7f) << shift;
            if b < 0x80 {
                return Some(v);
            }
            shift += 7;
            if shift > 63 {
                return None;
            }
        }
    }
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let Some(tag) = read_varint(&mut bytes) else { break };
        let value = match tag & 7 {
            0 => read_varint(&mut bytes).map(|_| Value::Number),
            2 => read_varint(&mut bytes).and_then(|n| {
                let n = n as usize;
                (n <= bytes.len()).then(|| {
                    let (head, rest) = bytes.split_at(n);
                    bytes = rest;
                    Value::Bytes(head)
                })
            }),
            1 if bytes.len() >= 8 => {
                bytes = &bytes[8..];
                Some(Value::Number)
            }
            5 if bytes.len() >= 4 => {
                bytes = &bytes[4..];
                Some(Value::Number)
            }
            _ => None,
        };
        let Some(value) = value else { break };
        out.push(((tag >> 3) as u32, value));
    }
    out
}

fn text(value: &Value) -> String {
    match value {
        Value::Bytes(b) => String::from_utf8_lossy(b).into_owned(),
        Value::Number => String::new(),
    }
}

/// name = 1, uri = 2, image = 3, then the artist or show names (`names` is the field repeating them, or 0).
fn read_item(bytes: &[u8], names: u32) -> (String, String, String, Vec<String>) {
    let (mut name, mut uri, mut image, mut by) = (String::new(), String::new(), String::new(), Vec::new());
    for (field, value) in fields(bytes) {
        match field {
            1 => name = text(&value),
            2 => uri = text(&value),
            3 => image = image_url(&text(&value)),
            f if f == names && names != 0 => by.push(text(&value)),
            _ => {}
        }
    }
    (name, uri, image, by)
}

pub fn decode(bytes: &[u8]) -> Vec<Hit> {
    let mut tracks = Vec::new();
    let mut albums = Vec::new();
    let mut playlists = Vec::new();
    let mut episodes = Vec::new();
    for (field, value) in fields(bytes) {
        let Value::Bytes(item) = value else { continue };
        match field {
            1 => {
                let (title, uri, image, artists) = read_item(item, 4);
                albums.push(Hit { section: "Albums", title, subtitle: format!("Album • {}", artists.join(", ")), uri, image, category: 1 });
            }
            2 => {
                let (title, uri, image, _) = read_item(item, 0);
                let show = fields(item).iter().find(|(f, _)| *f == 4).map(|(_, v)| text(v)).unwrap_or_default();
                episodes.push(Hit { section: "Podcast episodes", title, subtitle: show, uri, image, category: 3 });
            }
            3 => {
                let (title, uri, image, _) = read_item(item, 0);
                playlists.push(Hit { section: "Playlists", title, subtitle: "Playlist".into(), uri, image, category: 0 });
            }
            4 => {
                let (title, uri, image, artists) = read_item(item, 5);
                let album = fields(item).iter().find(|(f, _)| *f == 4).map(|(_, v)| text(v)).unwrap_or_default();
                let subtitle = match (artists.is_empty(), album.is_empty()) {
                    (false, false) => format!("{} • {album}", artists.join(", ")),
                    (false, true) => artists.join(", "),
                    _ => album,
                };
                tracks.push(Hit { section: "Songs", title, subtitle, uri, image, category: -1 });
            }
            _ => {}
        }
    }
    [tracks, albums, playlists, episodes].concat().into_iter().filter(|h| !h.uri.is_empty() && !h.title.is_empty()).collect()
}

/// 0 when `title` starts with the query, 1 when a word in it does, 2 otherwise: results the user most likely
/// meant come first.
fn rank(title: &str, needle: &str) -> u8 {
    let t = title.to_lowercase();
    if t.starts_with(needle) {
        0
    } else if t.split(|c: char| !c.is_alphanumeric()).any(|w| w.starts_with(needle)) {
        1
    } else {
        2
    }
}

/// Your library's matches first, then the core's offline index, without repeating anything the library
/// already showed, each section ordered by how well the title fits the query.
pub fn merge(query: &str, library_items: &[library::Item], offline: Vec<Hit>, per_section: usize) -> Vec<Hit> {
    let needle = query.trim().to_lowercase();
    if needle.is_empty() {
        return Vec::new();
    }
    let mut mine: Vec<Hit> = library::select(library_items, -1, &needle, false, usize::MAX)
        .into_iter()
        .map(|i| Hit {
            section: "Your library",
            title: i.name.clone(),
            subtitle: i.subtitle.clone(),
            uri: i.uri.clone(),
            image: i.image.clone(),
            category: i.kind.chip().unwrap_or(0),
        })
        .collect();
    mine.sort_by_key(|h| rank(&h.title, &needle));
    mine.truncate(per_section);
    let seen: Vec<String> = mine.iter().map(|h| h.uri.clone()).collect();
    let mut out = mine;
    for section in ["Songs", "Albums", "Playlists", "Podcast episodes"] {
        let mut hits: Vec<Hit> = offline.iter().filter(|h| h.section == section && !seen.contains(&h.uri)).cloned().collect();
        hits.sort_by_key(|h| rank(&h.title, &needle));
        hits.truncate(per_section);
        out.extend(hits);
    }
    out
}

/// Everything the core's offline index knows about `query`.
pub fn offline(query: &str) -> Result<Vec<Hit>, String> {
    bridge::call(SERVICE, "Query", &request(query, 30)).map(|bytes| decode(&bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn field(out: &mut Vec<u8>, number: u8, bytes: &[u8]) {
        out.push(number << 3 | 2);
        out.push(bytes.len() as u8);
        out.extend_from_slice(bytes);
    }

    fn track(name: &str, uri: &str, album: &str, artists: &[&str]) -> Vec<u8> {
        let mut t = Vec::new();
        field(&mut t, 1, name.as_bytes());
        field(&mut t, 2, uri.as_bytes());
        field(&mut t, 3, b"spotify:image:ab67");
        field(&mut t, 4, album.as_bytes());
        for a in artists {
            field(&mut t, 5, a.as_bytes());
        }
        t
    }

    #[test]
    fn the_request_carries_the_query_and_limit() {
        assert_eq!(request("ab", 30), vec![0x0a, 2, b'a', b'b', 0x10, 30]);
    }

    #[test]
    fn decodes_songs_albums_and_playlists() {
        let mut reply = Vec::new();
        field(&mut reply, 4, &track("Glasshouse", "spotify:track:1", "First Light", &["Northbound", "Hana Vale"]));
        let mut album = Vec::new();
        field(&mut album, 1, b"First Light");
        field(&mut album, 2, b"spotify:album:9");
        field(&mut album, 4, b"Northbound");
        field(&mut reply, 1, &album);
        let mut list = Vec::new();
        field(&mut list, 1, b"Late night");
        field(&mut list, 2, b"spotify:playlist:5");
        field(&mut reply, 3, &list);
        field(&mut reply, 4, &track("", "spotify:track:2", "x", &[])); // nameless: dropped
        let hits = decode(&reply);
        assert_eq!(hits.len(), 3);
        assert_eq!((hits[0].section, hits[0].title.as_str(), hits[0].subtitle.as_str()), ("Songs", "Glasshouse", "Northbound, Hana Vale • First Light"));
        assert_eq!(hits[0].image, "https://i.scdn.co/image/ab67");
        assert_eq!((hits[1].section, hits[1].subtitle.as_str(), hits[1].category), ("Albums", "Album • Northbound", 1));
        assert_eq!((hits[2].section, hits[2].uri.as_str()), ("Playlists", "spotify:playlist:5"));
    }

    #[test]
    fn garbage_decodes_to_nothing_instead_of_failing() {
        assert!(decode(&[0x22, 0xff]).is_empty());
        assert!(decode(&[]).is_empty());
    }

    #[test]
    fn merge_puts_the_library_first_and_never_repeats_it() {
        let items = vec![
            library::Item { name: "Blue Hour".into(), uri: "spotify:album:1".into(), subtitle: "Album • Low Tide".into(), image: String::new(), kind: library::Kind::Album },
            library::Item { name: "Deep Blue".into(), uri: "spotify:playlist:2".into(), subtitle: "Playlist".into(), image: String::new(), kind: library::Kind::Playlist },
        ];
        let song = |title: &str, uri: &str| Hit { section: "Songs", title: title.into(), uri: uri.into(), category: -1, ..Default::default() };
        let offline = vec![song("Feeling Blue", "spotify:track:1"), song("Blue Monday", "spotify:track:2"), Hit { section: "Albums", title: "Blue Hour".into(), uri: "spotify:album:1".into(), category: 1, ..Default::default() }];
        let merged = merge(" BLUE ", &items, offline, 10);
        let titles: Vec<_> = merged.iter().map(|h| (h.section, h.title.as_str())).collect();
        assert_eq!(titles, vec![("Your library", "Blue Hour"), ("Your library", "Deep Blue"), ("Songs", "Blue Monday"), ("Songs", "Feeling Blue")]);
        assert!(merge("   ", &items, Vec::new(), 10).is_empty());
    }
}
