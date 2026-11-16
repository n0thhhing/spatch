#!/bin/sh
# Copies Makepad's widget resources and app/resources into app/target/makepad-assets (or $1)
# so the patcher can ship them under assets/makepad/<crate>/resources/.
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-"$repo/app/target/makepad-assets"}
cargo_home=${CARGO_HOME:-"$HOME/.cargo"}
widgets_res=$(ls -d "$cargo_home"/registry/src/*/makepad-widgets-1.0.0/resources 2>/dev/null | head -1 || true)
[ -d "$widgets_res" ] || { echo "makepad-widgets-1.0.0/resources not found in $cargo_home/registry/src" >&2; exit 1; }
rm -rf "$out"
mkdir -p "$out/makepad_widgets" "$out/spatch_app"
cp -R "$widgets_res" "$out/makepad_widgets/resources"
cp -R "$repo/app/resources" "$out/spatch_app/resources"
