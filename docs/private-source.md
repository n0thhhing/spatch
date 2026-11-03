# Private Spatch source

A Spatch source lets Install Spatch download Spotify for people you invite, so they never choose an
APKM themselves. It is a folder on a web server you run, holding the exact APKM the patches were
made for and an `index.json` describing it, served only to people with a login you gave them.

Keep it private. Spatch never ships Spotify's own files, and a source that anyone can download from
would; every request must carry one of your logins, and nothing that unlocks it goes in this
repository or in Spatch Manager.

## How the manager uses it

An invite is the index URL with a person's name and code after a `#`:

```text
https://spatch.example.org/index.json#alex.Q3vB7kP2mXw9
```

Your friend pastes it under Spotify, Add an invite, or shares the link to Spatch Manager from a chat
(long-press, Share, Spatch Manager). It stays in the manager's private storage, which is never backed
up. When Install Spatch has no saved APKM and no unmodified Spotify of the right version to patch, it
asks the source and sends the name and code as an HTTP Basic login, only to the invite's own host.

The manager takes only the index entry whose SHA-256 and version code match what the patch bundle
pins, checks the phone's Android version and ABI against it, and downloads into a `.part` file. The
file replaces the saved APKM only when its length and hash match, so a damaged, cut-short or wrong
download never gets used and a working copy is kept until a new one checks out. Downloads follow at
most five redirects, all HTTPS, and the login is dropped when a redirect leaves the invite's host.

## Preparing the files

```bash
./target/release/spatch source <input.apkm> --out <dir>
```

This checks the APKM and the five APKs in it against the pinned hashes, reads the minimum Android
version from `base.apk`'s manifest, and writes:

- `spotify-<versionCode>-<first 16 hex of the SHA-256>.apkm`, a name that never stands for other bytes
- `index.json`, schema 1, naming that file

Both are written beside their final names and renamed into place, the APKM first, so the folder
never has an index pointing at an incomplete file. Copy them to the server in the same order:
upload the APKM, check its hash there, then replace `index.json`.

## Serving it with Caddy

Caddy gets HTTPS certificates by itself and can require a login per person. Generate a code for each
friend and store only its hash on the server:

```bash
openssl rand -base64 18 | tr -d '/+='    # the code you send your friend
caddy hash-password --plaintext '<code>'   # the hash for the Caddyfile
```

```caddy
spatch.example.org {
    root * /srv/spatch-source
    basic_auth {
        alex <hash for alex's code>
        sam  <hash for sam's code>
    }
    file_server
}
```

`file_server` doesn't list folders unless told to. Caddy older than 2.8 spells the directive
`basicauth`. The web root should hold only the APKM and `index.json`, readable but not writable by
Caddy. To revoke someone, delete their line and reload Caddy; everyone else keeps working.

On a Raspberry Pi at home, check before choosing a route: whether your connection gets a public
address or sits behind CGNAT, whether the router can forward port 443, and your upload speed, since
each install downloads about 58 MB. Keep SSH and other admin access off the internet. If inbound
connections aren't possible, check a tunnel's file-size and transfer limits before relying on it.

## Testing without a server

The manager allows plain HTTP for `localhost` only, which makes a source on your computer reachable
from a phone over USB:

```bash
adb reverse tcp:8766 tcp:8766
```

Serve the folder on `127.0.0.1:8766` behind a test login, then share the invite
`http://localhost:8766/index.json#test.<code>` to the manager. `manager/test/spatch/manager/SourcesTest.java`
covers the download checks on the desktop: wrong login, truncation, damage, wrong size, missing
Content-Length, redirects off HTTPS and the login not following a redirect to another host.
