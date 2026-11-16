#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}
jdk=${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}
if [[ -x "$jdk/bin/java" ]]; then
    export JAVA_HOME="$jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
avd=Spatch_Preview
port=5580
serial=emulator-$port
emulator="$sdk/emulator/emulator"
adb="$sdk/platform-tools/adb"
image='system-images;android-35;default;arm64-v8a'
manager="$sdk/cmdline-tools/latest/bin/avdmanager"
apk="$repo/dist/spatch-preview.apk"
log="$repo/dist/spatch-preview-emulator.log"

if [[ ! -x "$emulator" || ! -d "$sdk/system-images/android-35/default/arm64-v8a" ]]; then
    echo "Install the Android emulator and ARM64 system image first:"
    echo "  $sdk/cmdline-tools/latest/bin/sdkmanager --install emulator '$image'"
    exit 1
fi

mkdir -p "$repo/dist"
if ! "$emulator" -list-avds | rg -Fxq "$avd"; then
    printf 'no\n' | "$manager" create avd --name "$avd" --package "$image" --device pixel_7
fi
if [[ ! -f "$apk" ]]; then
    "$here/build.sh" "$apk"
fi

# Always target our virtual device, even if a physical phone is attached.
"$adb" start-server >/dev/null
if "$adb" devices | rg -q "^$serial[[:space:]]"; then
    name=$("$adb" -s "$serial" emu avd name | head -n 1 | tr -d '\r')
    if [[ "$name" != "$avd" ]]; then
        echo "Emulator port $port belongs to $name." >&2
        exit 1
    fi
else
    nohup "$emulator" -avd "$avd" -port "$port" -no-audio -no-boot-anim -no-snapshot         </dev/null >"$log" 2>&1 &
fi

for ((attempt = 0; attempt < 120; attempt++)); do
    if [[ $("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]]; then
        "$adb" -s "$serial" install -r "$apk"
        "$adb" -s "$serial" shell am start -n spatch.preview/spatch.PreviewActivity
        exit 0
    fi
    sleep 2
done
echo "Spatch Preview emulator did not boot. See $log." >&2
exit 1
