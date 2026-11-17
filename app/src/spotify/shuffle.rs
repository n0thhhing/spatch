//! Spotify's shuffle mode per playlist (what its playlist screen's shuffle button cycles through): off, shuffle,
//! or Smart Shuffle (shuffle with recommendations mixed in). Stored by the core's PlaybackSettings service:
//! GetShuffleState(StringValue id) -> ShuffleState { bool shuffle = 1; ContextEnhancement context_enhancement = 2 }
//! SetShuffleState(SetShuffleStateRequest { string id = 1; bool shuffle_on = 2; ContextEnhancement = 3 }),
//! where ContextEnhancement is NOT_ENHANCED = 0, RECOMMENDATION = 1. The messages are small, so they're written here.

use crate::bridge;

pub const SERVICE: &str = "spotify.playback_settings.esperanto.proto.PlaybackSettings";

/// 0 off, 1 shuffle, 2 Smart Shuffle.
pub type Mode = i32;

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

fn string_field(out: &mut Vec<u8>, field: u8, text: &str) {
    out.push(field << 3 | 2);
    varint(out, text.len() as u64);
    out.extend_from_slice(text.as_bytes());
}

pub fn get_request(uri: &str) -> Vec<u8> {
    let mut out = Vec::new();
    string_field(&mut out, 1, uri);
    out
}

pub fn set_request(uri: &str, mode: Mode) -> Vec<u8> {
    let mut out = Vec::new();
    string_field(&mut out, 1, uri);
    if mode > 0 {
        out.extend_from_slice(&[2 << 3, 1]);
    }
    if mode == 2 {
        out.extend_from_slice(&[3 << 3, 1]);
    }
    out
}

/// Reads a ShuffleState into a mode; unknown fields are skipped.
pub fn decode(bytes: &[u8]) -> Result<Mode, String> {
    let (mut shuffle, mut smart, mut i) = (false, false, 0);
    let next = |i: &mut usize| -> Result<u64, String> {
        let (mut v, mut shift) = (0u64, 0);
        loop {
            let b = *bytes.get(*i).ok_or("Cut-off shuffle state")?;
            *i += 1;
            v |= u64::from(b & 0x7f) << shift;
            if b < 0x80 {
                return Ok(v);
            }
            shift += 7;
            if shift > 63 {
                return Err("Bad shuffle state".into());
            }
        }
    };
    while i < bytes.len() {
        let tag = next(&mut i)?;
        match (tag >> 3, tag & 7) {
            (1, 0) => shuffle = next(&mut i)? != 0,
            (2, 0) => smart = next(&mut i)? == 1,
            (_, 0) => {
                next(&mut i)?;
            }
            (_, 2) => {
                let len = next(&mut i)? as usize;
                i += len;
            }
            (_, 5) => i += 4,
            (_, 1) => i += 8,
            _ => return Err("Bad shuffle state".into()),
        }
    }
    Ok(if smart { 2 } else if shuffle { 1 } else { 0 })
}

pub fn get(uri: &str) -> Result<Mode, String> {
    decode(&bridge::call(SERVICE, "GetShuffleState", &get_request(uri))?)
}

pub fn set(uri: &str, mode: Mode) -> Result<(), String> {
    bridge::call(SERVICE, "SetShuffleState", &set_request(uri, mode)).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_are_well_formed() {
        assert_eq!(get_request("ab"), vec![0x0a, 2, b'a', b'b']);
        assert_eq!(set_request("ab", 0), vec![0x0a, 2, b'a', b'b']);
        assert_eq!(set_request("ab", 1), vec![0x0a, 2, b'a', b'b', 0x10, 1]);
        assert_eq!(set_request("ab", 2), vec![0x0a, 2, b'a', b'b', 0x10, 1, 0x18, 1]);
    }

    #[test]
    fn states_decode_to_modes() {
        assert_eq!(decode(&[]), Ok(0));
        assert_eq!(decode(&[0x08, 1]), Ok(1));
        assert_eq!(decode(&[0x08, 1, 0x10, 1]), Ok(2));
        assert_eq!(decode(&[0x22, 1, b'x', 0x08, 1]), Ok(1), "skips an unknown field");
        assert!(decode(&[0x08]).is_err());
    }
}
