//! The settings Spotify's own Settings screen shows (streaming quality, crossfade, normalisation, explicit
//! content...), read from and written to its core. The setters take Google's plain wrapper messages.

use crate::bridge;
use crate::protos::com::spotify::settings::esperanto::proto as es;
use prost::Message;
use std::sync::mpsc;
use std::time::Duration;

pub const SERVICE: &str = "spotify.settings.esperanto.proto.Settings";

/// A setting Spatch offers, how Spotify names its setter, and whether it takes a bool or a number.
pub struct Setting {
    pub key: &'static str,
    pub setter: &'static str,
    pub number: bool,
}

/// Offline mode is left out on purpose: switching it on would cut Spatch off from Spotify's data.
pub const SETTINGS: &[Setting] = &[
    Setting { key: "play_explicit_content", setter: "SetPlayExplicitContext", number: false },
    Setting { key: "private_session", setter: "SetPrivateSession", number: false },
    Setting { key: "stream_quality", setter: "SetStreamQuality", number: true },
    Setting { key: "allow_audio_quality_downgrade", setter: "SetAllowAudioQualityDowngrade", number: false },
    Setting { key: "download_over_3g", setter: "SetDownloadOver3G", number: false },
    Setting { key: "normalize", setter: "SetNormalize", number: false },
    Setting { key: "gapless", setter: "SetGapless", number: false },
    Setting { key: "automix", setter: "SetAutomix", number: false },
    Setting { key: "crossfade", setter: "SetCrossfade", number: false },
    Setting { key: "crossfade_time_seconds", setter: "SetCrossfadeTimeSeconds", number: true },
    Setting { key: "show_unavailable_tracks", setter: "SetShowUnavailableTracks", number: false },
];

/// The current value of each setting in [`SETTINGS`], in that order (bools as 0 or 1).
pub fn values(state: &es::SettingsState) -> Vec<i32> {
    vec![
        state.play_explicit_content as i32,
        state.private_session as i32,
        state.stream_quality,
        state.allow_audio_quality_downgrade as i32,
        state.download_over_3g as i32,
        state.normalize as i32,
        state.gapless as i32,
        state.automix as i32,
        state.crossfade as i32,
        state.crossfade_time_seconds,
        state.show_unavailable_tracks as i32,
    ]
}

/// `google.protobuf.BoolValue` / `Int32Value`: field 1, omitted when it is the default.
pub fn wrapper(value: i32) -> Vec<u8> {
    if value == 0 {
        return Vec::new();
    }
    let mut out = vec![0x08];
    let mut v = value as u32;
    loop {
        let byte = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            return out;
        }
        out.push(byte | 0x80);
    }
}

/// Reads the settings from the State stream: the first reply, then closes it. Blocks.
pub fn read(wait: Duration) -> Result<Vec<i32>, String> {
    let (send, receive) = mpsc::channel::<Result<Vec<u8>, String>>();
    let id = bridge::subscribe(SERVICE, "GetState", &[], move |reply| {
        let _ = send.send(reply.map(<[u8]>::to_vec).map_err(str::to_string));
    })?;
    let answer = receive.recv_timeout(wait).map_err(|_| "Spotify didn’t answer".to_string());
    bridge::cancel(id);
    let bytes = answer??;
    es::SettingsState::decode(bytes.as_slice()).map(|s| values(&s)).map_err(|e| e.to_string())
}

/// Changes one setting through Spotify's own setter. Blocks.
pub fn write(key: &str, value: i32) -> Result<(), String> {
    let setting = SETTINGS.iter().find(|s| s.key == key).ok_or("Unknown setting")?;
    let value = if setting.number { value } else { (value != 0) as i32 };
    bridge::call(SERVICE, setting.setter, &wrapper(value)).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wrappers_match_protobuf_encoding() {
        assert_eq!(wrapper(0), Vec::<u8>::new());
        assert_eq!(wrapper(1), vec![0x08, 0x01]);
        assert_eq!(wrapper(12), vec![0x08, 0x0c]);
        assert_eq!(wrapper(300), vec![0x08, 0xac, 0x02]);
    }

    #[test]
    fn values_follow_the_settings_order() {
        let state = es::SettingsState { crossfade: true, crossfade_time_seconds: 6, stream_quality: 3, normalize: true, ..Default::default() };
        let v = values(&state);
        assert_eq!(v.len(), SETTINGS.len());
        let at = |key: &str| v[SETTINGS.iter().position(|s| s.key == key).unwrap()];
        assert_eq!((at("crossfade"), at("crossfade_time_seconds"), at("stream_quality"), at("normalize"), at("gapless")), (1, 6, 3, 1, 0));
    }
}
