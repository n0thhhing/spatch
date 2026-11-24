//! Generates Rust types for the protos Spatch's app speaks (rebuilt from Spotify by tools/port/protos.py).
fn main() {
    let protos = [
        "proto/com.spotify.searchview.proto.proto",
        "proto/com.spotify.player.esperanto.proto.proto",
        "proto/spotify.your_library.esperanto.proto.proto",
        "proto/spotify.playlist.esperanto.proto.proto",
        "proto/spotify.collection.esperanto.proto.proto",
        "proto/com.spotify.recently_played_esperanto.proto.proto",
        "proto/com.spotify.metadata.cosmos.proto.proto",
        "proto/com.spotify.connect.esperanto.proto.proto",
        "proto/com.spotify.settings.esperanto.proto.proto",
        "proto/spotify.profile_esperanto.proto.proto",
        "proto/com.spotify.identity.proto.v3.proto",
    ];
    for p in &protos {
        println!("cargo:rerun-if-changed={p}");
    }
    // The commit this build came from, shown in Settings so it's clear which nightly is installed.
    let commit = std::env::var("GITHUB_SHA")
        .ok()
        .map(|sha| sha.chars().take(7).collect::<String>())
        .or_else(|| {
            let out = std::process::Command::new("git").args(["rev-parse", "--short", "HEAD"]).output().ok()?;
            out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
        })
        .unwrap_or_else(|| "dev".into());
    println!("cargo:rustc-env=SPATCH_COMMIT={commit}");
    println!("cargo:rerun-if-env-changed=GITHUB_SHA");
    let mut config = prost_build::Config::new();
    config.include_file("protos.rs");
    config.compile_protos(&protos, &["proto"]).expect("protoc failed; install protobuf (brew install protobuf / apt install protobuf-compiler)");
}
