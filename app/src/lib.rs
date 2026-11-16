//! Spatch's own Spotify UI. Runs inside the patched Spotify app: Java's SpatchApp hosts a surface and
//! passes frames and touches here, and CoreBridge carries Esperanto calls to Spotify's core (player,
//! library, playlists...) as protobuf bytes. Nothing here sees an obfuscated name, so a Spotify update
//! only touches the Java hooks.

pub mod core;
pub mod player;
#[cfg(target_os = "android")]
mod surface;
pub mod ui;

/// Types for Spotify's Esperanto protos (app/proto, rebuilt by scripts/port/protos.py).
#[allow(clippy::all, non_camel_case_types)]
pub mod protos {
    include!(concat!(env!("OUT_DIR"), "/protos.rs"));
}

pub use protos::com::spotify::player::esperanto::proto as es_player;
