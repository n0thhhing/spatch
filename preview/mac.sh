#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
jdk=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
if [[ ! -x "$jdk/bin/java" ]]; then
    echo 'Spatch Preview needs JDK 21. Install it with: brew install openjdk@21' >&2
    exit 1
fi
export JAVA_HOME="$jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export CARGO_TARGET_DIR="$repo/target/mac-preview"

case "${1:-}" in
    '') task=macApp ;;
    --check) task=test ;;
    *) echo 'Usage: preview/mac.sh [--check]' >&2; exit 2 ;;
esac

# Rebuild the shared Rust preview and compile the actual Android screen on every launch.
cargo build --release --locked --features preview --lib --manifest-path "$repo/crates/core/Cargo.toml"
"$here/desktop/gradlew" -p "$here/desktop" "$task" --no-daemon --console=plain
if [[ "$task" == macApp ]]; then
    open "$repo/dist/Spatch Preview.app"
fi
