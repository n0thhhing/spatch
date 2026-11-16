//! Spatch's own Spotify UI, in Makepad. It runs inside the patched Spotify app: Java's SpatchApp hosts a
//! surface and feeds it to Makepad's Android layer (dev.makepad.android.MakepadNative), and CoreBridge
//! carries Esperanto calls to Spotify's core (player, library...) as protobuf bytes. Nothing here sees an
//! obfuscated name, so a Spotify update only touches the Java hooks.

pub mod app;
pub mod colors;
pub mod core;
pub mod images;
pub mod library;
pub mod player;

/// Types for Spotify's Esperanto protos (app/proto, rebuilt by scripts/port/protos.py).
#[allow(clippy::all, non_camel_case_types)]
pub mod protos {
    include!(concat!(env!("OUT_DIR"), "/protos.rs"));
}

pub use protos::com::spotify::player::esperanto::proto as es_player;
