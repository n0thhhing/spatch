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
mkdir -p "$out/makepad_widgets/resources" "$out/spatch_app/resources"
cp -R "$widgets_res/." "$out/makepad_widgets/resources/"
cp -R "$repo/app/resources/." "$out/spatch_app/resources/"

# Fallbacks for Chinese and emoji fonts referenced in Makepad default themes.
# We copy existing valid TTF files to prevent missing-asset unwraps on Android without bundling 45 MB of extra crates.
cp "$widgets_res/IBMPlexSans-Text.ttf" "$out/makepad_widgets/resources/LXGWWenKaiRegular.ttf"
cp "$widgets_res/IBMPlexSans-SemiBold.ttf" "$out/makepad_widgets/resources/LXGWWenKaiBold.ttf"

for crate in makepad_fonts_chinese_regular makepad_fonts_chinese_regular_2 makepad_fonts_chinese_bold makepad_fonts_chinese_bold_2 makepad_fonts_emoji; do
    mkdir -p "$out/$crate/resources"
done

cp "$widgets_res/IBMPlexSans-Text.ttf" "$out/makepad_fonts_chinese_regular/resources/LXGWWenKaiRegular.ttf"
cp "$widgets_res/IBMPlexSans-Text.ttf" "$out/makepad_fonts_chinese_regular_2/resources/LXGWWenKaiRegular.ttf.2"
cp "$widgets_res/IBMPlexSans-SemiBold.ttf" "$out/makepad_fonts_chinese_bold/resources/LXGWWenKaiBold.ttf"
cp "$widgets_res/IBMPlexSans-SemiBold.ttf" "$out/makepad_fonts_chinese_bold_2/resources/LXGWWenKaiBold.ttf.2"
cp "$widgets_res/IBMPlexSans-Text.ttf" "$out/makepad_fonts_emoji/resources/NotoColorEmoji.ttf"

