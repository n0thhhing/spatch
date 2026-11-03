#!/bin/sh
# Publishes the patch bundles of a commit (HEAD by default) as the `nightly` prerelease, which
# Spatch Manager installs when Nightly patches is on. Builds from a clean copy of the commit, so
# uncommitted work never ships. Needs the supported Spotify APKM in the repo root (it is ignored).
#   scripts/nightly.sh [commit]
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
export GH_REPO=n0thhhing/spatch # gh runs outside the checkout below
sha=$(git -C "$repo" rev-parse "${1:-HEAD}")
short=$(echo "$sha" | cut -c1-7)
if [ "$(gh release view nightly --json targetCommitish -q .targetCommitish 2>/dev/null || true)" = "$sha" ]; then
    echo "nightly is already $short"
    exit 0
fi
version=$(git -C "$repo" show "$sha:src/input.rs" | sed -n 's/^pub const VERSION_NAME: &str = "\(.*\)";/\1/p')
apkm=$(ls "$repo"/*"_$version-"*.apkm 2>/dev/null | head -1)
[ -n "$apkm" ] || { echo "no Spotify $version APKM in $repo"; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/src" "$work/out"
git -C "$repo" archive "$sha" | tar -x -C "$work/src"
export CARGO_TARGET_DIR="$HOME/.spatch/nightly-target" # kept between runs so dependencies build once
cargo build -q --release --manifest-path "$work/src/Cargo.toml"
cd "$work/src"
"$CARGO_TARGET_DIR/release/spatch" bundle "$apkm" --out "$work/out/spatch-bundle.zip"
"$CARGO_TARGET_DIR/release/spatch" bundle "$apkm" --debug --out "$work/out/spatch-bundle-debug.zip"
cd "$work/out"
shasum -a 256 spatch-bundle.zip spatch-bundle-debug.zip > spatch-bundle.sha256

notes="Patches built from $short, $(git -C "$repo" log -1 --format=%s "$sha"), for Spotify $version. Not released and less tested: turn on Nightly patches in Spatch Manager 0.9.0 or later to use it."
git -C "$repo" tag -f nightly "$sha" >/dev/null
git -C "$repo" push -q -f origin refs/tags/nightly
if gh release view nightly >/dev/null 2>&1; then
    # Files first, then the commit: the manager names what it installs after the commit.
    gh release upload nightly spatch-bundle.zip spatch-bundle-debug.zip spatch-bundle.sha256 --clobber
    gh release edit nightly --target "$sha" --prerelease --notes "$notes" >/dev/null
else
    gh release create nightly --prerelease --target "$sha" --title "Spatch nightly" --notes "$notes" \
        spatch-bundle.zip spatch-bundle-debug.zip spatch-bundle.sha256 >/dev/null
fi
echo "nightly is now $short"
