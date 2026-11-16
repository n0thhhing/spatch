//! Liked Songs membership through CollectionService: is a track saved, save it, unsave it.

use crate::protos::spotify::collection::esperanto::proto as es;
use prost::Message;

pub const SERVICE: &str = "spotify.collection_esperanto.proto.CollectionService";

pub fn contains_request(uri: &str) -> Vec<u8> {
    es::CollectionContainsRequest { uri: vec![uri.into()], ..Default::default() }.encode_to_vec()
}

/// Whether the one asked-about URI is saved, or Spotify's status text when the call failed.
pub fn decode_contains(bytes: &[u8]) -> Result<bool, String> {
    let reply = es::CollectionContainsResponse::decode(bytes).map_err(|e| e.to_string())?;
    check(reply.status)?;
    reply.found.first().copied().ok_or_else(|| "No answer for the track".into())
}

/// Spotify's own client fills `items` (uri plus item data), so this does too.
pub fn change_request(uri: &str) -> Vec<u8> {
    es::CollectionAddRemoveItemsRequest { items: vec![es::CollectionAddRemoveItem { uri: uri.into() }], ..Default::default() }.encode_to_vec()
}

pub fn decode_change(bytes: &[u8]) -> Result<(), String> {
    check(es::CollectionAddRemoveItemsResponse::decode(bytes).map_err(|e| e.to_string())?.status)
}

/// Status code 0, or no status at all, is success.
fn check(status: Option<es::Status>) -> Result<(), String> {
    match status {
        Some(s) if s.code != 0 => Err(if s.reason.is_empty() { format!("Collection error {}", s.code) } else { s.reason }),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contains_reads_the_first_answer_and_reports_errors() {
        let yes = es::CollectionContainsResponse { found: vec![true], ..Default::default() };
        assert_eq!(decode_contains(&yes.encode_to_vec()), Ok(true));
        let bad = es::CollectionContainsResponse { status: Some(es::Status { code: 3, reason: "offline".into() }), ..Default::default() };
        assert_eq!(decode_contains(&bad.encode_to_vec()), Err("offline".into()));
        assert!(decode_change(&es::CollectionAddRemoveItemsResponse::default().encode_to_vec()).is_ok());
    }

    #[test]
    fn saving_sends_items() {
        let request = es::CollectionAddRemoveItemsRequest::decode(change_request("spotify:track:1").as_slice()).unwrap();
        assert_eq!(request.items[0].uri, "spotify:track:1");
        assert_eq!(es::CollectionContainsRequest::decode(contains_request("spotify:track:1").as_slice()).unwrap().uri, ["spotify:track:1"]);
    }
}
