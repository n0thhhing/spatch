//! Spatch's Rust side: typed access to Spotify's core and the data the Android views draw.
//!
//! `bridge` reaches the core through Java (spatch.CoreBridge), `spotify` speaks its services,
//! `ui` is what SpatchUi.java calls, `shapes` supplies icon and button geometry.

pub mod bridge;
pub mod cache;
pub mod palette;
pub mod shapes;
pub mod spectrum;
pub mod spotify;
pub mod ui;

#[cfg(feature = "preview")]
mod demo;

/// Types for Spotify's Esperanto protos (app/proto, rebuilt from Spotify's own classes).
#[allow(clippy::all, non_camel_case_types)]
pub mod protos {
    include!(concat!(env!("OUT_DIR"), "/protos.rs"));
}
