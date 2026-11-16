//! Generates Rust types for the protos Spatch's app speaks (rebuilt from Spotify by scripts/port/protos.py).
fn main() {
    let protos = [
        "proto/com.spotify.player.esperanto.proto.proto",
        "proto/spotify.your_library.esperanto.proto.proto",
    ];
    for p in &protos {
        println!("cargo:rerun-if-changed={p}");
    }
    let mut config = prost_build::Config::new();
    config.include_file("protos.rs");
    config.compile_protos(&protos, &["proto"]).expect("protoc failed; install protobuf (brew install protobuf / apt install protobuf-compiler)");
    slint_build::compile("ui/app.slint").expect("Slint UI failed to compile");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=android");
    }
}
