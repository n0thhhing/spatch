//! Generates Rust types for the protos Spatch's app speaks (rebuilt from Spotify by scripts/port/protos.py).
fn main() {
    let protos = [
        "proto/com.spotify.player.esperanto.proto.proto",
        "proto/spotify.your_library.esperanto.proto.proto",
        "proto/spotify.playlist.esperanto.proto.proto",
        "proto/spotify.collection.esperanto.proto.proto",
        "proto/com.spotify.recently_played_esperanto.proto.proto",
    ];
    for p in &protos {
        println!("cargo:rerun-if-changed={p}");
    }
    let mut config = prost_build::Config::new();
    config.include_file("protos.rs");
    config.compile_protos(&protos, &["proto"]).expect("protoc failed; install protobuf (brew install protobuf / apt install protobuf-compiler)");
}
