# Spatch Manager

An Android app that installs Spatch on the phone itself. You give it your own Spotify APKM; it
downloads the newest patch bundle from this repo's releases, applies it, signs every APK with a
key made on the phone, and installs the set. Updates use the same saved APKM and the same key, so
they install in place. Nothing is uploaded anywhere.

## On the phone

1. Install `spatch-manager.apk` from the releases page.
2. **Choose Spotify APKM** and pick the supported build (see `src/input.rs`). Skip this if the
   supported Spotify is installed unmodified (from the Play Store, say): the manager uses that.
3. **Install Spatch**. Android asks once to let the manager install apps, then to confirm.

With an invite to a private Spatch source (**Add an invite**, or share the invite link to Spatch
Manager), step 2 goes away: Install Spatch downloads the exact APKM it needs, checks its size and
hash, and keeps it for later updates. A saved APKM and an unmodified installed Spotify still come
first. Running a source is described in `docs/private-source.md`.

Only `base.apk` is patched, so the manager keeps the original `base.apk` (or the APKM) and takes the
other APKs from the installed Spotify; updates never ask for the APKM again. A Spotify already
patched elsewhere has no original left in it, so that case needs the APKM once.

A Spotify already installed with a different key has to be uninstalled first (that removes its
downloads), unless you import the key it was signed with. **Export key** saves the phone's key as a
password-protected `.p12`; keep it, because a lost key means uninstalling to update. **Import key**
takes such a file, including one made with `keytool` for the desktop patcher.

To install over a Spotify you patched on the desktop, import the keystore you signed it with; the
manager then signs with that key and Android takes the install as an update. If the phone can't
read the file (newer `keytool` files use AES encryption), re-export it with older encryption first:

```bash
openssl pkcs12 -in <your.p12> -nodes -out key.pem
```

```bash
openssl pkcs12 -export -legacy -in key.pem -out spatch-key.p12
```

Delete `key.pem` afterwards: it holds the key unencrypted.

**Developer build** installs the variant with Developer options in Spatch settings (self tests, a
frame monitor, a touch inspector, logs). Switching it and tapping **Switch build** reinstalls in
place; settings stay.

Updates replace only `base.apk` (the other APKs never change) and, on Android 12 and newer, install
without the confirmation screen once the manager has installed Spotify. **Tell me about new patches**
checks twice a day and posts a notification; tapping it opens the manager straight into the update.

**Update manager** appears when a newer manager release is out.

The pages along the bottom are Spotify (the steps above), Clone, Key and Manager.

**Clone** installs a second Spatch beside the first, under its own package name (say
`com.spotify.music.two`), with its own app name and, optionally, a picture of yours as its icon.
It has its own login and downloads. The manager renames the package in every APK, moves the content
providers under the new name, sets the label and swaps the launcher icon's foreground; the patch
bundle's `clone-` lines say which resource strings and icon file that takes in each Spotify build.
Clones are signed with the phone's key and keep Spotify's permission names, so they install beside
Spatch but not beside Spotify's own app. Screens inside the app still say Spotify.

## Releases

Two kinds of release, both found by tag:

| Tag | Asset | Made with |
| --- | --- | --- |
| `patches-v<version>` | `spatch-bundle.zip` and `spatch-bundle-debug.zip` | `spatch bundle <input.apkm> --out <file.zip>`, adding `--debug` for the second |
| `manager-v<version>` | `spatch-manager.apk` | `manager/build.sh <version> <out.apk> --keystore ... --alias ... --ks-pass-env ...` |

Versions are `major.minor.patch`, `0.x` until 1.0. The manager's version code is derived from its
version (`major*10000 + minor*100 + patch`), and every manager release must be signed
with the same key so it can update itself. The bundle format is described in `src/bundle.rs`.

## Building

`build.sh` needs only the Android SDK (build-tools and one `platforms/android-*` jar) and a JDK. The
signing library the app uses on the phone, apksig, is taken from the SDK's own `apksigner.jar`.

## Checking

`test/spatch/manager/RoundTrip.java` runs the manager's patching and signing on the desktop and
compares every entry with a set the desktop patcher signed from the same commit:

```bash
javac -d /tmp/rt -cp <apksig classes> manager/src/spatch/manager/Bundle.java manager/src/spatch/manager/Keys.java manager/test/spatch/manager/RoundTrip.java
```

```bash
java -cp /tmp/rt:<apksig classes> spatch.manager.RoundTrip <input.apkm> <bundle.zip> <signed-set-dir> <work-dir>
```

`RewriteTest` times the APK rewrite without signing or downloading, then checks every entry's bytes
and compression method against the original. The manager uses fast compression to shorten patching;
the resulting APK can be slightly larger. Run with an original `base.apk` and a fresh work directory:

```bash
javac --release 11 -d /tmp/rewrite manager/src/spatch/manager/Bundle.java manager/src/spatch/manager/Clone.java manager/test/spatch/manager/RewriteTest.java
java -cp /tmp/rewrite spatch.manager.RewriteTest <base.apk> <work-dir>
```
