#!/bin/sh
# Publishes the patch bundles of a commit (HEAD by default) as the `nightly` prerelease, which
# Spatch Manager installs when Nightly patches is on. Builds from a clean copy of the commit, so
# uncommitted work never ships. Needs the supported Spotify APKM: $SPATCH_APKM, or in the repo root
# (it is ignored). .github/workflows/nightly.yml runs this on every push, split in parts:
#   scripts/nightly.sh [commit]                        build patches and Manager, then publish
#   NIGHTLY_BUILD=normal|manager NIGHTLY_OUT=<dir> ...   only build that artifact into <dir>
#   NIGHTLY_BUNDLES=<dir> ...                          only publish the bundles already in <dir>
#   NIGHTLY_TAG=branch-<name> ...                     publish to that branch's prerelease instead of `nightly`
set -eu
tag=${NIGHTLY_TAG:-nightly}
repo=$(cd "$(dirname "$0")/.." && pwd)
export GH_REPO=n0thhhing/spatch # gh runs outside the checkout below
sha=$(git -C "$repo" rev-parse "${1:-HEAD}")
short=$(echo "$sha" | cut -c1-7)
# Source identities are independent of the overall release commit and of each other.
patch_id=$(git -C "$repo" ls-tree -r "$sha" -- src java app Cargo.toml Cargo.lock | git hash-object --stdin)
manager_id=$(git -C "$repo" rev-parse "$sha:manager")
labels=$(gh release view "$tag" --json assets -q '.assets[] | [.name, (.label // "")] | @tsv' 2>/dev/null || true)
changed() { ! printf '%s\n' "$labels" | awk -F '\t' -v name="$1" -v label="nightly-$2" '$1==name && $2==label {found=1} END {exit !found}'; }
patches=false manager=false
if changed spatch-bundle.zip "$patch_id"; then patches=true; fi
if changed spatch-manager.apk "$manager_id"; then manager=true; fi
if [ "${NIGHTLY_CHECK:-}" = 1 ]; then
    printf 'patches=%s\nmanager=%s\n' "$patches" "$manager"
    exit 0
fi
version=$(git -C "$repo" show "$sha:src/input.rs" | sed -n 's/^pub const VERSION_NAME: &str = "\(.*\)";/\1/p')
apkm=${SPATCH_APKM:-$(ls "$repo"/*"_$version-"*.apkm 2>/dev/null | head -1)}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
out=${NIGHTLY_BUNDLES:-${NIGHTLY_OUT:-$work/out}}
mkdir -p "$out"
out=$(cd "$out" && pwd)
build_manager() {
    ks=${MANAGER_KEYSTORE_FILE:-$HOME/.spatch/device-test.p12}
    if [ -z "${MANAGER_KS_PASS:-}" ] && [ -f "$HOME/.spatch/device-test.pass" ]; then
        MANAGER_KS_PASS=$(cat "$HOME/.spatch/device-test.pass")
        export MANAGER_KS_PASS
    fi
    if [ -f "$ks" ] && [ -n "${MANAGER_KS_PASS:-}" ]; then
        # The version being worked towards (manager/VERSION), so a nightly is never older than the last release.
        mgr_ver=$(cat "$work/src/manager/VERSION" 2>/dev/null || gh release list --limit 50 --json tagName -q '[.[] | select(.tagName | startswith("manager-v"))][0].tagName | sub("^manager-v"; "")' 2>/dev/null || true)
        mgr_ver=${mgr_ver:-0.9.0}
        "$work/src/manager/build.sh" "$mgr_ver-$(echo "$manager_id" | cut -c1-7)" "$out/spatch-manager.apk" \
            --keystore "$ks" --alias "${MANAGER_KS_ALIAS:-spatch-test}" --ks-pass-env MANAGER_KS_PASS
    fi
}

if [ -z "${NIGHTLY_BUNDLES:-}" ]; then
    mkdir "$work/src"
    git -C "$repo" archive "$sha" | tar -x -C "$work/src"
    export CARGO_TARGET_DIR="$HOME/.spatch/nightly-target" # kept between runs so dependencies build once
    if [ "${NIGHTLY_BUILD:-both}" != manager ] && [ "$patches" = true ]; then
        [ -n "$apkm" ] || { echo "no Spotify $version APKM in $repo"; exit 1; }
        case "$apkm" in /*) ;; *) apkm="$PWD/$apkm" ;; esac
        if [ -z "${SPATCH_APP_SO:-}" ]; then
            (cd "$work/src/app" && GITHUB_SHA="$sha" cargo ndk -t arm64-v8a -P 29 -o "$work/lib" build --release --locked)
            export SPATCH_APP_SO="$work/lib/arm64-v8a/libspatch_app.so"
        else
            case "$SPATCH_APP_SO" in /*) ;; *) export SPATCH_APP_SO="$PWD/$SPATCH_APP_SO" ;; esac
        fi
        cargo build -q --release --locked --manifest-path "$work/src/Cargo.toml"
    fi
    cd "$work/src"
    spatch="$CARGO_TARGET_DIR/release/spatch"
    case "${NIGHTLY_BUILD:-both}" in
        manager) [ "$manager" = false ] || build_manager; exit 0 ;;
        normal)
            [ "$patches" = false ] || "$spatch" bundle "$apkm" --out "$out/spatch-bundle.zip"
            exit 0 ;;
        both)
            [ "$patches" = false ] || "$spatch" bundle "$apkm" --out "$out/spatch-bundle.zip"
            [ "$manager" = false ] || build_manager ;;
        *) echo "NIGHTLY_BUILD is normal, manager or unset"; exit 2 ;;
    esac
fi
cd "$out"
assets=""
for asset in spatch-bundle.zip spatch-manager.apk; do
    [ ! -f "$asset" ] || assets="$assets $asset"
done
[ -n "$assets" ] || { echo "manager and patches are unchanged"; exit 0; }
mkdir "$work/previous"
gh release download "$tag" --pattern spatch-bundle.sha256 --dir "$work/previous" 2>/dev/null || true
: > spatch-bundle.sha256
if [ -f "$work/previous/spatch-bundle.sha256" ]; then
    while read -r digest asset; do
        case "$asset" in spatch-bundle.zip|spatch-manager.apk)
            [ -f "$asset" ] || printf '%s  %s\n' "$digest" "$asset" >> spatch-bundle.sha256 ;;
        esac
    done < "$work/previous/spatch-bundle.sha256"
fi
shasum -a 256 $assets >> spatch-bundle.sha256
# Labels carry immutable component identities; unchanged assets retain their IDs and URLs.
set --
for asset in $assets; do
    case "$asset" in spatch-manager.apk) id=$manager_id ;; *) id=$patch_id ;; esac
    set -- "$@" "$asset#nightly-$id"
done

notes="Spatch nightly through $short, $(git -C "$repo" log -1 --format=%s "$sha"), for Spotify $version. Not released and less tested: turn on Nightly patches in Spatch Manager 0.9.0 or later to use it."
git -C "$repo" tag -f "$tag" "$sha" >/dev/null
git -C "$repo" push -q -f origin "refs/tags/$tag"
if gh release view "$tag" >/dev/null 2>&1; then
    # Files first, then the commit: the manager names what it installs after the commit.
    gh release upload "$tag" "$@" spatch-bundle.sha256 --clobber
    gh release edit "$tag" --target "$sha" --prerelease --notes "$notes" >/dev/null
else
    title="Spatch nightly"; [ "$tag" = nightly ] || title="Spatch nightly (${tag#branch-})"
    gh release create "$tag" --prerelease --target "$sha" --title "$title" --notes "$notes" \
        "$@" spatch-bundle.sha256 >/dev/null
fi
echo "$tag is now $short"
