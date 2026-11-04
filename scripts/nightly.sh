#!/bin/sh
# Publishes the patch bundles of a commit (HEAD by default) as the `nightly` prerelease, which
# Spatch Manager installs when Nightly patches is on. Builds from a clean copy of the commit, so
# uncommitted work never ships. Needs the supported Spotify APKM: $SPATCH_APKM, or in the repo root
# (it is ignored). .github/workflows/nightly.yml runs this on every push, split in parts:
#   scripts/nightly.sh [commit]                        build both bundles side by side, then publish
#   NIGHTLY_BUILD=normal|debug NIGHTLY_OUT=<dir> ...   only build that bundle into <dir>
#   NIGHTLY_BUNDLES=<dir> ...                          only publish the bundles already in <dir>
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
apkm=${SPATCH_APKM:-$(ls "$repo"/*"_$version-"*.apkm 2>/dev/null | head -1)}
[ -n "$apkm" ] || [ -n "${NIGHTLY_BUNDLES:-}" ] || { echo "no Spotify $version APKM in $repo"; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
out=${NIGHTLY_BUNDLES:-${NIGHTLY_OUT:-$work/out}}
mkdir -p "$out"
build_manager() {
    ks=${MANAGER_KEYSTORE_FILE:-$HOME/.spatch/device-test.p12}
    if [ -z "${MANAGER_KS_PASS:-}" ] && [ -f "$HOME/.spatch/device-test.pass" ]; then
        MANAGER_KS_PASS=$(cat "$HOME/.spatch/device-test.pass")
        export MANAGER_KS_PASS
    fi
    if [ -f "$ks" ] && [ -n "${MANAGER_KS_PASS:-}" ]; then
        mgr_ver=$(gh release list --limit 50 --json tagName -q '[.[] | select(.tagName | startswith("manager-v"))][0].tagName | sub("^manager-v"; "")' 2>/dev/null || true)
        mgr_ver=${mgr_ver:-0.9.0}
        "$work/src/manager/build.sh" "$mgr_ver-$short" "$out/spatch-manager.apk" \
            --keystore "$ks" --alias "${MANAGER_KS_ALIAS:-spatch-test}" --ks-pass-env MANAGER_KS_PASS
    fi
}

if [ -z "${NIGHTLY_BUNDLES:-}" ]; then
    mkdir "$work/src"
    git -C "$repo" archive "$sha" | tar -x -C "$work/src"
    export CARGO_TARGET_DIR="$HOME/.spatch/nightly-target" # kept between runs so dependencies build once
    cargo build -q --release --manifest-path "$work/src/Cargo.toml"
    cd "$work/src"
    spatch="$CARGO_TARGET_DIR/release/spatch"
    case "${NIGHTLY_BUILD:-both}" in
        normal)
            "$spatch" bundle "$apkm" --out "$out/spatch-bundle.zip"
            build_manager
            exit 0 ;;
        debug) "$spatch" bundle "$apkm" --debug --out "$out/spatch-bundle-debug.zip"; exit 0 ;;
        both)
            "$spatch" bundle "$apkm" --out "$out/spatch-bundle.zip" & normal=$!
            "$spatch" bundle "$apkm" --debug --out "$out/spatch-bundle-debug.zip"
            wait "$normal"
            build_manager ;;
        *) echo "NIGHTLY_BUILD is normal, debug or unset"; exit 2 ;;
    esac
fi
cd "$out"
assets="spatch-bundle.zip spatch-bundle-debug.zip"
[ -f spatch-manager.apk ] && assets="$assets spatch-manager.apk"
shasum -a 256 $assets > spatch-bundle.sha256

notes="Patches and Spatch Manager built from $short, $(git -C "$repo" log -1 --format=%s "$sha"), for Spotify $version. Not released and less tested: turn on Nightly patches in Spatch Manager 0.9.0 or later to use it."
git -C "$repo" tag -f nightly "$sha" >/dev/null
git -C "$repo" push -q -f origin refs/tags/nightly
if gh release view nightly >/dev/null 2>&1; then
    # Files first, then the commit: the manager names what it installs after the commit.
    gh release upload nightly $assets spatch-bundle.sha256 --clobber
    gh release edit nightly --target "$sha" --prerelease --notes "$notes" >/dev/null
else
    gh release create nightly --prerelease --target "$sha" --title "Spatch nightly" --notes "$notes" \
        $assets spatch-bundle.sha256 >/dev/null
fi
echo "nightly is now $short"
