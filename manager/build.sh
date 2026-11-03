#!/bin/sh
# Builds Spatch Manager with the Android SDK alone (aapt2, javac, d8, zipalign, apksigner).
#   manager/build.sh <version> <out.apk> --keystore <file> --alias <name> --ks-pass-env <VAR>
# <version> is major.minor.patch (0.x until 1.0); the version code is major*10000 + minor*100 + patch.
# The keystore password stays in the named environment variable; apksigner reads it from there.
# apksig, which signs on the phone, comes from the SDK's own apksigner.jar.
set -eu
[ $# -eq 8 ] || { sed -n '3p' "$0"; exit 2; }
version=$1 out=$2 ks=$4 alias=$6 pass=$8
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "version must be major.minor.patch"; exit 2; }
code=$(echo "$version" | awk -F. '{print $1*10000 + $2*100 + $3}')
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
[ -e "$out" ] && { echo "$out already exists; refusing to overwrite"; exit 1; }

"$bt/aapt2" compile --dir "$here/res" -o "$work/res.zip"
"$bt/aapt2" link -I "$jar" --manifest "$here/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 35 \
    --version-code "$code" --version-name "$version" -o "$work/app.apk" "$work/res.zip"

mkdir "$work/apksig" "$work/classes" "$work/dex"
(cd "$work/apksig" && unzip -q "$bt/lib/apksigner.jar" 'com/android/apksig/*' && zip -qr ../apksig.jar com)
"$javac" --release 11 -nowarn -cp "$jar:$work/apksig.jar" -d "$work/classes" $(find "$here/src" -name '*.java')
"$bt/d8" --release --min-api 29 --lib "$jar" --output "$work/dex" $(find "$work/classes" -name '*.class') "$work/apksig.jar"
(cd "$work/dex" && zip -q "$work/app.apk" classes.dex)

"$bt/zipalign" -p -f 4 "$work/app.apk" "$work/aligned.apk"
"$bt/apksigner" sign --ks "$ks" --ks-key-alias "$alias" --ks-pass "env:$pass" --out "$out" "$work/aligned.apk"
"$bt/apksigner" verify "$out"
echo "wrote $out"
