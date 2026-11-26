#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
out=${1:-"$repo/dist/spatch-preview.apk"}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}
jdk=${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}
if [[ -x "$jdk/bin/java" ]]; then
    export JAVA_HOME="$jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
bt=$(find "$sdk/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
android=$(find "$sdk/platforms" -maxdepth 2 -name android.jar | sort -V | tail -n 1)
if [[ ! -x "$bt/aapt2" || ! -f "$android" ]]; then
    echo "Install Android build tools and a recent Android SDK platform under ANDROID_HOME." >&2
    exit 1
fi
if [[ -z ${ANDROID_NDK_HOME:-} && -d /opt/homebrew/share/android-ndk ]]; then
    export ANDROID_NDK_HOME=/opt/homebrew/share/android-ndk
fi

# Use a separate target so the demo library can never replace the patcher's release library.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export CARGO_TARGET_DIR="$repo/target/preview"
cargo ndk -t arm64-v8a -P 29 -o "$work/lib" build --release --locked --features preview --manifest-path "$repo/crates/core/Cargo.toml"

mkdir -p "$work/classes" "$work/dex" "$(dirname "$out")"
helpers=()
while IFS= read -r file; do
    helpers+=("$file")
done < <(find "$repo/android/ui" -name '*.java' | sort | grep -vEf <(sed 's|.*|/&\\.java$|' "$here/excluded-sources.txt"))
javac --release 11 -cp "$android" -d "$work/classes" "$here/src/spatch/PreviewActivity.java" "$here/src/spatch/CoreBridge.java" "${helpers[@]}"
"$bt/d8" --release --min-api 29 --lib "$android" --output "$work/dex" "$work/classes"/spatch/*.class
"$bt/aapt2" link -I "$android" --manifest "$here/AndroidManifest.xml" --min-sdk-version 29     --target-sdk-version 35 --version-code 1 --version-name 0.1 -o "$work/app.apk"
(cd "$work/dex" && zip -q "$work/app.apk" classes.dex)
(cd "$work" && zip -q "$work/app.apk" lib/arm64-v8a/libspatch_app.so)
"$bt/zipalign" -P 16 -f 4 "$work/app.apk" "$work/aligned.apk"

keystore=${SPATCH_PREVIEW_KEYSTORE:-"$HOME/.android/debug.keystore"}
if [[ ! -f "$keystore" ]]; then
    mkdir -p "$(dirname "$keystore")"
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android         -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$bt/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey --ks-pass pass:android     --out "$work/preview.apk" "$work/aligned.apk"
"$bt/apksigner" verify "$work/preview.apk"
mv "$work/preview.apk" "$out"
echo "wrote $out"
