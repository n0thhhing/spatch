//! ConnectService: the devices that can play your music, and moving playback to one of them.

use crate::bridge;
use crate::protos::com::spotify::connect::esperanto::proto as es;
use prost::Message;
use std::sync::mpsc;
use std::time::{Duration, Instant};

pub const SERVICE: &str = "spotify.connect.esperanto.proto.ConnectService";

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Device {
    pub name: String,
    /// Spotify's own word for it: "Smartphone", "Computer", "Speaker", "CastAudio", ...
    pub kind: String,
    /// The identifier a Transfer names it by.
    pub id: String,
    pub active: bool,
    pub this_device: bool,
}

pub fn state_request() -> Vec<u8> {
    es::StateRequest { include_local_device: true, ..Default::default() }.encode_to_vec()
}

/// The devices Spotify lists, in its order, without ones that are offline or switched off.
pub fn decode_state(bytes: &[u8]) -> Result<Vec<Device>, String> {
    let state = es::StateResponse::decode(bytes).map_err(|e| e.to_string())?;
    Ok(state
        .devices
        .into_iter()
        .filter(|d| !d.is_offline && !d.is_disabled)
        .map(|d| Device {
            id: if d.connect_state_identifier.is_empty() { d.local_identifier.clone() } else { d.connect_state_identifier.clone() },
            name: d.name,
            kind: d.r#type,
            active: d.is_active,
            this_device: d.is_self || d.is_local,
        })
        .filter(|d| !d.id.is_empty())
        .collect())
}

pub fn transfer_request(device_id: &str) -> Vec<u8> {
    es::TransferRequest { connect_device_identifier: device_id.into(), ..Default::default() }.encode_to_vec()
}

/// 0 is success, anything else is Spotify's refusal.
pub fn decode_status(bytes: &[u8]) -> Result<(), String> {
    match es::StatusResponse::decode(bytes).map_err(|e| e.to_string())?.status {
        0 => Ok(()),
        n => Err(format!("Spotify answered {n}")),
    }
}

/// Opens the State stream, keeps the first list that has devices (or the last one before `wait` runs
/// out), and closes it. Blocks: never on the UI thread.
pub fn devices(wait: Duration) -> Result<Vec<Device>, String> {
    let (send, receive) = mpsc::channel::<Result<Vec<u8>, String>>();
    let id = bridge::subscribe(SERVICE, "State", &state_request(), move |reply| {
        let _ = send.send(reply.map(<[u8]>::to_vec).map_err(str::to_string));
    })?;
    let deadline = Instant::now() + wait;
    let mut latest: Result<Vec<Device>, String> = Err("Spotify didn’t answer".into());
    while let Some(left) = deadline.checked_duration_since(Instant::now()) {
        match receive.recv_timeout(left) {
            Ok(Ok(bytes)) => {
                latest = decode_state(&bytes);
                if latest.as_ref().is_ok_and(|d| !d.is_empty()) {
                    break;
                }
            }
            Ok(Err(e)) => {
                latest = latest.or(Err(e));
                break;
            }
            Err(_) => break,
        }
    }
    bridge::cancel(id);
    latest
}

/// Moves playback to `device_id`. Blocks.
pub fn transfer(device_id: &str) -> Result<(), String> {
    bridge::call(SERVICE, "Transfer", &transfer_request(device_id)).and_then(|bytes| decode_status(&bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(name: &str, connect: &str, local: &str) -> es::StateResponseDevice {
        es::StateResponseDevice { name: name.into(), connect_state_identifier: connect.into(), local_identifier: local.into(), r#type: "Speaker".into(), ..Default::default() }
    }

    #[test]
    fn lists_online_devices_by_their_connect_id_and_marks_this_one() {
        let state = es::StateResponse {
            devices: vec![
                es::StateResponseDevice { is_self: true, is_active: true, ..device("Pixel 11 Pro", "c1", "l1") },
                device("Kitchen", "", "l2"),
                es::StateResponseDevice { is_offline: true, ..device("Old TV", "c3", "l3") },
                es::StateResponseDevice { is_disabled: true, ..device("Disabled", "c4", "l4") },
                device("No id", "", ""),
            ],
            ..Default::default()
        };
        let listed = decode_state(&state.encode_to_vec()).unwrap();
        assert_eq!(listed.iter().map(|d| (d.name.as_str(), d.id.as_str())).collect::<Vec<_>>(), [("Pixel 11 Pro", "c1"), ("Kitchen", "l2")]);
        assert!(listed[0].this_device && listed[0].active && !listed[1].active);
    }

    #[test]
    fn a_transfer_names_the_device_and_a_nonzero_status_is_a_refusal() {
        assert_eq!(es::TransferRequest::decode(transfer_request("c1").as_slice()).unwrap().connect_device_identifier, "c1");
        assert_eq!(decode_status(&es::StatusResponse { status: 0 }.encode_to_vec()), Ok(()));
        assert_eq!(decode_status(&es::StatusResponse { status: 3 }.encode_to_vec()), Err("Spotify answered 3".into()));
    }
}
