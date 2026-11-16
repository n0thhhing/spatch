//! Spotify data and commands for the Spatch UI.

pub mod core;
mod ui_bridge;
mod ui_geometry;
pub mod library;
pub mod player;

/// Types for Spotify's Esperanto protos (app/proto, rebuilt by scripts/port/protos.py).
#[allow(clippy::all, non_camel_case_types)]
pub mod protos {
    include!(concat!(env!("OUT_DIR"), "/protos.rs"));
}

pub use protos::com::spotify::player::esperanto::proto as es_player;
