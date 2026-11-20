//! The core's product state: the account's settings as key/value pairs (country_code, catalogue, type...). The
//! core's song-details requests carry country_code and catalogue, and a fresh sign-in can leave them empty.

use super::wire::{fields, first};
use std::collections::HashMap;

pub const SERVICE: &str = "spotify.product_state.esperanto.proto.ProductState";

/// GetValuesResponse: repeated pairs = 1, each key = 1, value = 2.
pub fn decode(bytes: &[u8]) -> HashMap<String, String> {
    fields(bytes)
        .into_iter()
        .filter(|(n, _)| *n == 1)
        .filter_map(|(_, pair)| {
            let pair = fields(pair.bytes());
            Some((first(&pair, 1)?.text(), first(&pair, 2).map(|v| v.text()).unwrap_or_default()))
        })
        .collect()
}

/// The keys that decide song details, playlists and the login hand-off, as one line for the log.
pub fn summary(values: &HashMap<String, String>) -> String {
    ["country_code", "catalogue", "type", "product", "taste-onboarding-signin-eligible", "obfuscate-restricted-tracks"]
        .iter()
        .map(|k| format!("{k}={}", values.get(*k).map_or("(missing)", |v| if v.is_empty() { "(empty)" } else { v.as_str() })))
        .collect::<Vec<_>>()
        .join(", ")
}

/// The account's two-letter country, if the product state has a valid one.
pub fn country(values: &HashMap<String, String>) -> Option<String> {
    values.get("country_code").filter(|c| c.len() == 2).cloned()
}

#[cfg(test)]
mod tests {
    use super::super::wire::{bytes_field, string_field};

    #[test]
    fn pairs_are_read_and_summarised() {
        let mut out = Vec::new();
        for (k, v) in [("country_code", "US"), ("catalogue", "")] {
            let mut pair = Vec::new();
            string_field(&mut pair, 1, k);
            string_field(&mut pair, 2, v);
            bytes_field(&mut out, 1, &pair);
        }
        let values = super::decode(&out);
        assert_eq!(values.get("country_code").map(String::as_str), Some("US"));
        assert_eq!(super::country(&values).as_deref(), Some("US"));
        let line = super::summary(&values);
        assert!(line.starts_with("country_code=US, catalogue=(empty), type=(missing)"));
    }
}
