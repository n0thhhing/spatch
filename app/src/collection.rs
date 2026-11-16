//! Liked Songs membership through Spotify's CollectionService: is a track saved, save it, unsave it.

use crate::protos::spotify::collection::esperanto::proto as es;
use prost::Message;

pub const SERVICE: &str = "spotify.collection_esperanto.proto.CollectionService";

pub fn contains_request(uri: &str) -> Vec<u8> {
    es::CollectionContainsRequest { uri: vec![uri.into()], ..Default::default() }.encode_to_vec()
}

/// Whether the one asked-about URI is saved; Spotify's status reason when the call failed.
pub fn decode_contains(bytes: &[u8]) -> Result<bool, String> {
    let reply = es::CollectionContainsResponse::decode(bytes).map_err(|e| e.to_string())?;
    failed(reply.status)?;
    reply.found.first().copied().ok_or_else(|| "No answer for the track".into())
}

pub fn change_request(uri: &str) -> Vec<u8> {
    // Spotify's collection client sends items (uri + item data), so Spatch does the same.
    es::CollectionAddRemoveItemsRequest { items: vec![es::CollectionAddRemoveItem { uri: uri.into() }], ..Default::default() }.encode_to_vec()
}

pub fn decode_change(bytes: &[u8]) -> Result<(), String> {
    failed(es::CollectionAddRemoveItemsResponse::decode(bytes).map_err(|e| e.to_string())?.status)
}

/// Code 0 (or no status) is success, as in Spotify's own collection client.
fn failed(status: Option<es::Status>) -> Result<(), String> {
    match status {
        Some(s) if s.code != 0 => Err(if s.reason.is_empty() { format!("Collection error {}", s.code) } else { s.reason }),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contains_reads_the_first_answer_and_errors_come_through() {
        let yes = es::CollectionContainsResponse { found: vec![true], ..Default::default() };
        assert_eq!(decode_contains(&yes.encode_to_vec()), Ok(true));
        let bad = es::CollectionContainsResponse {
            status: Some(es::Status { code: 3, reason: "offline".into() }),
            ..Default::default()
        };
        assert_eq!(decode_contains(&bad.encode_to_vec()), Err("offline".into()));
        assert!(decode_change(&es::CollectionAddRemoveItemsResponse::default().encode_to_vec()).is_ok());
        let request = es::CollectionContainsRequest::decode(contains_request("spotify:track:1").as_slice()).unwrap();
        assert_eq!(request.uri, ["spotify:track:1"]);
    }
}
