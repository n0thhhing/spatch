//! Spotify's native Observe metadata service (spotify.mdata_esperanto.proto.MetadataService / Observe).
//!
//! Requests track metadata using Spotify's normal streaming Observe contract (TRACK_V4 = 10),
//! receiving initial cached/pending responses followed by final emissions (pending_response = false).

use super::metadata::track_of;
use super::playlist::Track;
use super::wire::{bytes_field, fields, first, number_field, string_field};
use crate::bridge;
use crate::protos::com::spotify::metadata::proto as md;
use prost::Message;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::time::{Duration, Instant};

pub const SERVICE: &str = "spotify.mdata_esperanto.proto.MetadataService";
pub const METHOD: &str = "Observe";
pub const TRACK_V4: u64 = 10;
pub const STATUS_OK: u64 = 1;

/// One extension result from an ObserveResponse.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ObserveItem {
    pub uri: String,
    pub kind: u64,
    pub status: u64,
    pub payload: Option<Vec<u8>>,
}

/// An ObserveResponse batch with pending flag.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ObserveBatch {
    pub items: Vec<ObserveItem>,
    pub pending: bool,
}

/// Builds an ObserveRequest protobuf:
/// repeated ExtensionQuery extension_query = 1 (entity_uri=1, extension_kind=2, notify_on_local_cache_changes=3)
/// string feature_id = 2
/// bool notify_on_changes = 3
pub fn request(uris: &[String], feature_id: &str) -> Vec<u8> {
    let mut out = Vec::new();
    for uri in uris {
        let mut query = Vec::new();
        string_field(&mut query, 1, uri);
        number_field(&mut query, 2, TRACK_V4);
        number_field(&mut query, 3, 0); // notify_on_local_cache_changes = false
        bytes_field(&mut out, 1, &query);
    }
    string_field(&mut out, 2, feature_id);
    number_field(&mut out, 3, 0); // notify_on_changes = false
    out
}

/// Decodes an ObserveResponse protobuf:
/// repeated ExtensionResult extension_result = 1
/// bool pending_response = 2
pub fn decode(bytes: &[u8]) -> ObserveBatch {
    let top = fields(bytes);
    let pending = first(&top, 2).is_some_and(|v| v.number() != 0);
    let mut items = Vec::new();

    for (num, val) in top {
        if num != 1 {
            continue;
        }
        let item_fields = fields(val.bytes());
        let uri = first(&item_fields, 1).map(|v| v.text()).unwrap_or_default();
        let kind = first(&item_fields, 2).map_or(0, |v| v.number());
        let status = first(&item_fields, 4).map_or(0, |v| v.number());

        // extension_data is google.protobuf.Any (field 2 is value bytes)
        let payload = if status == STATUS_OK {
            first(&item_fields, 3)
                .and_then(|any| first(&fields(any.bytes()), 2))
                .map(|v| v.bytes().to_vec())
        } else {
            None
        };

        items.push(ObserveItem { uri, kind, status, payload });
    }

    ObserveBatch { items, pending }
}

struct CancelGuard(i64);

impl Drop for CancelGuard {
    fn drop(&mut self) {
        bridge::cancel(self.0);
    }
}

/// Filters and decodes items from an Observe batch into `by_uri`.
/// Strict resolution criteria:
/// - Item URI must be one of the requested URIs.
/// - Item kind must be TRACK_V4.
/// - Item status must be STATUS_OK.
/// - Decoded track name must be non-empty.
///
/// Unrelated entities are recorded for status tracking but ignored for resolution.
/// Returns true only when every requested URI has been resolved.
pub fn process_items(
    requested: &HashSet<&str>,
    items: &[ObserveItem],
    by_uri: &mut HashMap<String, Track>,
    status_counts: &mut BTreeMap<u64, usize>,
) -> bool {
    for item in items {
        *status_counts.entry(item.status).or_insert(0) += 1;
        if !requested.contains(item.uri.as_str()) {
            continue;
        }
        if item.status == STATUS_OK && item.kind == TRACK_V4 {
            if let Some(ref p) = item.payload {
                if let Ok(t) = md::Track::decode(p.as_slice()) {
                    if !t.name.trim().is_empty() {
                        by_uri.insert(item.uri.clone(), track_of(&t));
                    }
                }
            }
        }
    }
    !requested.is_empty() && requested.iter().all(|u| by_uri.contains_key(*u))
}

/// Merges resolved tracks into the slice, preserving original URI, added, liked,
/// recommended, and explicit flags.
pub fn merge_resolved(tracks: &mut [Track], chunk: &[usize], by_uri: &HashMap<String, Track>) -> usize {
    let mut chunk_filled = 0;
    for &i in chunk {
        if let Some(found) = by_uri.get(&tracks[i].uri) {
            let orig = &tracks[i];
            let uri = orig.uri.clone();
            let added = orig.added;
            let liked = orig.liked;
            let recommended = orig.recommended;
            let explicit = orig.explicit || found.explicit;
            let image = if found.image.is_empty() { orig.image.clone() } else { found.image.clone() };
            let artist = if found.artist.is_empty() { orig.artist.clone() } else { found.artist.clone() };
            tracks[i] = Track {
                uri,
                added,
                liked,
                recommended,
                explicit,
                image,
                artist,
                ..found.clone()
            };
            chunk_filled += 1;
        }
    }
    chunk_filled
}

/// Waits on the subscription channel for an Observe batch stream until all requested
/// tracks are resolved, stream ends (pending=false), an error callback arrives, or the deadline is reached.
pub fn wait_observe_chunk(
    rx: &std::sync::mpsc::Receiver<Result<Vec<u8>, String>>,
    requested: &HashSet<&str>,
    deadline: Instant,
    by_uri: &mut HashMap<String, Track>,
    status_counts: &mut BTreeMap<u64, usize>,
) {
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        let Ok(msg_result) = rx.recv_timeout(remaining) else {
            break;
        };
        let msg_bytes = match msg_result {
            Ok(bytes) => bytes,
            Err(e) => {
                log::debug!("Observe stream error callback: {e}");
                break;
            }
        };

        let batch = decode(&msg_bytes);
        let all_resolved = process_items(requested, &batch.items, by_uri, status_counts);
        if !batch.pending || all_resolved {
            break;
        }
    }
}

/// Resolves missing tracks via native streaming Observe over authenticated bridge.
/// Bounded to a 4-second total budget across all chunks. Cancels subscriptions on all paths.
pub fn resolve_observe(tracks: &mut [Track]) {
    let wanted: Vec<usize> = (0..tracks.len())
        .filter(|&i| tracks[i].title.is_empty() && tracks[i].uri.starts_with("spotify:track:"))
        .collect();
    if wanted.is_empty() {
        return;
    }

    let deadline = Instant::now() + Duration::from_secs(4);

    for chunk in wanted.chunks(50) {
        if Instant::now() >= deadline {
            break;
        }

        let uris: Vec<String> = chunk.iter().map(|&i| tracks[i].uri.clone()).collect();
        let req_bytes = request(&uris, "spatch_observe");

        let (tx, rx) = std::sync::mpsc::sync_channel::<Result<Vec<u8>, String>>(16);
        let sub_id = match bridge::subscribe(SERVICE, METHOD, &req_bytes, move |reply| match reply {
            Ok(bytes) => {
                let _ = tx.try_send(Ok(bytes.to_vec()));
            }
            Err(err) => {
                let _ = tx.try_send(Err(err.to_string()));
            }
        }) {
            Ok(id) => id,
            Err(e) => {
                log::debug!("Observe subscription failed: {e}");
                return;
            }
        };

        let _guard = CancelGuard(sub_id);
        let requested: HashSet<&str> = uris.iter().map(String::as_str).collect();
        let mut by_uri: HashMap<String, Track> = HashMap::new();
        let mut status_counts: BTreeMap<u64, usize> = BTreeMap::new();

        wait_observe_chunk(&rx, &requested, deadline, &mut by_uri, &mut status_counts);

        let chunk_filled = merge_resolved(tracks, chunk, &by_uri);

        log::info!(
            "Observe metadata: resolved {chunk_filled} / {} tracks, statuses: {:?}",
            uris.len(),
            status_counts
        );

    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_encoding_matches_expected_schema() {
        let uris = vec!["spotify:track:abc1".to_string(), "spotify:track:abc2".to_string()];
        let req = request(&uris, "test_feature");
        let top = fields(&req);

        let queries: Vec<_> = top.iter().filter(|(n, _)| *n == 1).map(|(_, v)| fields(v.bytes())).collect();
        assert_eq!(queries.len(), 2);
        assert_eq!(first(&queries[0], 1).map(|v| v.text()), Some("spotify:track:abc1".into()));
        assert_eq!(first(&queries[0], 2).map(|v| v.number()), Some(10)); // TRACK_V4
        assert_eq!(first(&queries[0], 3).map(|v| v.number()), Some(0));

        assert_eq!(first(&queries[1], 1).map(|v| v.text()), Some("spotify:track:abc2".into()));
        assert_eq!(first(&queries[1], 2).map(|v| v.number()), Some(10));

        assert_eq!(first(&top, 2).map(|v| v.text()), Some("test_feature".into()));
        assert_eq!(first(&top, 3).map(|v| v.number()), Some(0));
    }

    #[test]
    fn response_decoding_extracts_items_and_pending_flag() {
        let make_item = |uri: &str, kind: u64, status: u64, payload: Option<&[u8]>| {
            let mut item = Vec::new();
            string_field(&mut item, 1, uri);
            number_field(&mut item, 2, kind);
            if let Some(bytes) = payload {
                let mut any = Vec::new();
                string_field(&mut any, 1, "type.googleapis.com/spotify.metadata.Track");
                bytes_field(&mut any, 2, bytes);
                bytes_field(&mut item, 3, &any);
            }
            number_field(&mut item, 4, status);
            item
        };

        let mut resp = Vec::new();
        let item1 = make_item("spotify:track:1", 10, 1, Some(b"\x12\x04test"));
        let item2 = make_item("spotify:track:2", 10, 0, None);
        bytes_field(&mut resp, 1, &item1);
        bytes_field(&mut resp, 1, &item2);
        number_field(&mut resp, 2, 1); // pending = true

        let decoded = decode(&resp);
        assert!(decoded.pending);
        assert_eq!(decoded.items.len(), 2);
        assert_eq!(decoded.items[0].uri, "spotify:track:1");
        assert_eq!(decoded.items[0].kind, 10);
        assert_eq!(decoded.items[0].status, 1);
        assert_eq!(decoded.items[0].payload, Some(b"\x12\x04test".to_vec()));

        assert_eq!(decoded.items[1].uri, "spotify:track:2");
        assert_eq!(decoded.items[1].kind, 10);
        assert_eq!(decoded.items[1].status, 0);
        assert_eq!(decoded.items[1].payload, None);
    }

    #[test]
    fn process_items_strict_filtering_and_unrelated_entity_isolation() {
        let track_proto = md::Track { name: "Real Song".into(), gid: vec![1; 16], ..Default::default() };
        let valid_payload = track_proto.encode_to_vec();

        let empty_name_proto = md::Track { name: "   ".into(), gid: vec![2; 16], ..Default::default() };
        let empty_name_payload = empty_name_proto.encode_to_vec();

        let items = vec![
            // 1. Requested, valid status OK, kind 10, non-empty name -> resolved
            ObserveItem {
                uri: "spotify:track:target1".into(),
                kind: TRACK_V4,
                status: STATUS_OK,
                payload: Some(valid_payload.clone()),
            },
            // 2. Unrelated entity with status OK, kind 10 -> MUST NOT resolve or trigger early finish
            ObserveItem {
                uri: "spotify:track:unrelated".into(),
                kind: TRACK_V4,
                status: STATUS_OK,
                payload: Some(valid_payload.clone()),
            },
            // 3. Requested, but empty name -> NOT resolved
            ObserveItem {
                uri: "spotify:track:empty_name".into(),
                kind: TRACK_V4,
                status: STATUS_OK,
                payload: Some(empty_name_payload),
            },
            // 4. Requested, but status MISSING (0) -> NOT resolved
            ObserveItem {
                uri: "spotify:track:missing".into(),
                kind: TRACK_V4,
                status: 0,
                payload: None,
            },
            // 5. Requested, but wrong kind (e.g. 5) -> NOT resolved
            ObserveItem {
                uri: "spotify:track:wrong_kind".into(),
                kind: 5,
                status: STATUS_OK,
                payload: Some(valid_payload),
            },
        ];

        let requested: HashSet<&str> = ["spotify:track:target1", "spotify:track:empty_name", "spotify:track:missing"]
            .into_iter()
            .collect();
        let mut by_uri = HashMap::new();
        let mut status_counts = BTreeMap::new();

        let all_done = process_items(&requested, &items, &mut by_uri, &mut status_counts);

        assert!(!all_done);
        assert_eq!(by_uri.len(), 1);
        assert!(by_uri.contains_key("spotify:track:target1"));
        assert_eq!(by_uri["spotify:track:target1"].title, "Real Song");
        assert!(!by_uri.contains_key("spotify:track:unrelated"));
        assert!(!by_uri.contains_key("spotify:track:empty_name"));
        assert_eq!(status_counts.get(&STATUS_OK), Some(&4));
        assert_eq!(status_counts.get(&0), Some(&1));

        // When all requested are actually resolved:
        let requested_single: HashSet<&str> = ["spotify:track:target1"].into_iter().collect();
        assert!(requested_single.iter().all(|u| by_uri.contains_key(*u)));
    }

    #[test]
    fn merge_resolved_preserves_original_flags_and_uri() {
        let mut tracks = vec![
            Track {
                title: String::new(),
                artist: "Original Artist".into(),
                uri: "spotify:track:orig1".into(),
                image: "orig_cover.jpg".into(),
                album: String::new(),
                album_uri: String::new(),
                artist_uri: String::new(),
                duration_ms: 0,
                added: 123456,
                liked: true,
                recommended: true,
                explicit: false,
            },
            Track {
                title: String::new(),
                artist: String::new(),
                uri: "spotify:track:orig2".into(),
                image: String::new(),
                album: String::new(),
                album_uri: String::new(),
                artist_uri: String::new(),
                duration_ms: 0,
                added: 789,
                liked: false,
                recommended: false,
                explicit: true,
            },
        ];

        let mut by_uri = HashMap::new();
        by_uri.insert(
            "spotify:track:orig1".into(),
            Track {
                title: "Resolved Song 1".into(),
                artist: "Resolved Artist".into(),
                uri: "spotify:track:foreign".into(),
                image: "resolved_cover.jpg".into(),
                album: "Resolved Album".into(),
                album_uri: "spotify:album:123".into(),
                artist_uri: "spotify:artist:456".into(),
                duration_ms: 210000,
                added: 0,
                liked: false,
                recommended: false,
                explicit: true,
            },
        );

        let chunk = vec![0, 1];
        let filled = merge_resolved(&mut tracks, &chunk, &by_uri);
        assert_eq!(filled, 1);

        // Track 0 was resolved: original URI, added, liked, recommended preserved. Explicit is or'ed.
        assert_eq!(tracks[0].uri, "spotify:track:orig1");
        assert_eq!(tracks[0].title, "Resolved Song 1");
        assert_eq!(tracks[0].artist, "Resolved Artist");
        assert_eq!(tracks[0].album, "Resolved Album");
        assert_eq!(tracks[0].image, "resolved_cover.jpg");
        assert_eq!(tracks[0].duration_ms, 210000);
        assert_eq!(tracks[0].added, 123456);
        assert!(tracks[0].liked);
        assert!(tracks[0].recommended);
        assert!(tracks[0].explicit);

        // Track 1 was not resolved: unchanged
        assert_eq!(tracks[1].uri, "spotify:track:orig2");
        assert!(tracks[1].title.is_empty());
        assert_eq!(tracks[1].added, 789);
        assert!(!tracks[1].liked);
        assert!(tracks[1].explicit);
    }

    #[test]
    fn wait_observe_chunk_terminates_promptly_on_error() {
        let (tx, rx) = std::sync::mpsc::sync_channel::<Result<Vec<u8>, String>>(4);
        let requested: HashSet<&str> = ["spotify:track:target"].into_iter().collect();
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut by_uri = HashMap::new();
        let mut status_counts = BTreeMap::new();

        // Send an error immediately
        tx.send(Err("Stream error 403".into())).unwrap();

        let start = Instant::now();
        wait_observe_chunk(&rx, &requested, deadline, &mut by_uri, &mut status_counts);
        let elapsed = start.elapsed();

        // Should return promptly (well under 500ms, definitely not 5s deadline)
        assert!(elapsed < Duration::from_millis(500));
        assert!(by_uri.is_empty());
    }
}
