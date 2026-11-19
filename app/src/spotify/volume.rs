//! Spotify Connect volume: the volume of the device that's playing (this phone, a speaker, a computer), on the
//! core's `ConnectVolumeService`. `Volume` streams the current level, `VolumeSet` changes it.
//!
//! VolumeResponse { int32 volume = 1; Status status = 2 }; VolumeSetRequest { int32 volume = 1 }.
//! The level runs 0 to 65535. The messages are tiny, so they are read and written here by hand.

use super::wire::{self, fields, first};
use crate::bridge;
use std::sync::mpsc;
use std::time::Duration;

pub const SERVICE: &str = "spotify.connect.esperanto.proto.ConnectVolumeService";
pub const MAX: i32 = 65_535;

pub fn set_request(volume: i32) -> Vec<u8> {
    let mut out = Vec::new();
    wire::number_field(&mut out, 1, volume.clamp(0, MAX) as u64);
    out
}

/// The volume in a VolumeResponse.
pub fn decode(bytes: &[u8]) -> Option<i32> {
    first(&fields(bytes), 1).map(|v| (v.number() as i32).clamp(0, MAX))
}

/// A StatusResponse carries a Status { code, reason }; code 0 (or none) is success.
pub fn status_error(bytes: &[u8]) -> Result<(), String> {
    let reply = fields(bytes);
    let Some(status) = first(&reply, 1) else { return Ok(()) };
    let status = fields(status.bytes());
    match first(&status, 1).map_or(0, |v| v.number()) {
        0 => Ok(()),
        code => Err(first(&status, 2).map(|v| v.text()).filter(|r| !r.is_empty()).unwrap_or_else(|| format!("Spotify refused (code {code})"))),
    }
}

/// The current volume: the first reply of the `Volume` stream, then the stream is closed. Blocks.
pub fn get(wait: Duration) -> Result<i32, String> {
    let (send, receive) = mpsc::channel::<Option<i32>>();
    let id = bridge::subscribe(SERVICE, "Volume", &[], move |reply| {
        let _ = send.send(reply.ok().and_then(decode));
    })?;
    let answer = receive.recv_timeout(wait).map_err(|_| "Spotify didn’t answer".to_string());
    bridge::cancel(id);
    answer?.ok_or_else(|| "Volume unavailable".into())
}

pub fn set(volume: i32) -> Result<(), String> {
    bridge::call(SERVICE, "VolumeSet", &set_request(volume)).and_then(|bytes| status_error(&bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_request_carries_the_level_clamped_to_the_range() {
        assert_eq!(set_request(300), vec![0x08, 0xac, 0x02]);
        assert_eq!(set_request(-5), vec![0x08, 0]);
        assert_eq!(fields(&set_request(1_000_000))[0].1.number(), MAX as u64);
    }

    #[test]
    fn reads_the_volume_from_a_reply() {
        assert_eq!(decode(&[0x08, 0xac, 0x02]), Some(300));
        assert_eq!(decode(&[]), None);
    }

    #[test]
    fn a_status_with_a_code_is_a_refusal_with_its_reason() {
        let mut status = Vec::new();
        wire::number_field(&mut status, 1, 7);
        wire::string_field(&mut status, 2, "device unavailable");
        let mut reply = Vec::new();
        wire::bytes_field(&mut reply, 1, &status);
        assert_eq!(status_error(&reply), Err("device unavailable".into()));
        assert_eq!(status_error(&[]), Ok(()));
        let mut ok = Vec::new();
        wire::bytes_field(&mut ok, 1, &[0x08, 0]);
        assert_eq!(status_error(&ok), Ok(()));
    }
}
