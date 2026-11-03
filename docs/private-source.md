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

## Free: a Cloudflare Worker in front of a private GitHub release

No server needed. `source/cloudflare/` holds a Worker that checks each login, serves `index.json`,
and answers the APKM's path with GitHub's own download link for an asset in a private release. That
link expires within minutes, and the file never passes through the Worker. Cloudflare's free plan
covers it (100,000 requests a day, no card), and an install costs two requests.

1. Make a free Cloudflare account at dash.cloudflare.com.
2. Put each APKM in its own release of a private repository: tag `apkm-<versionName>`, asset
   `spotify-<versionName>.apkm`, as in `apkm-9.1.98.521` / `spotify-9.1.98.521.apkm`.
   `wrangler.toml` names the repository (`n0thhhing/spatch-builds`). The Worker refuses an asset
   whose size doesn't match `index.json`.
3. Log in and deploy; wrangler prints the Worker's address, `https://spatch-source.<you>.workers.dev`:

   ```bash
   cd source/cloudflare
   npx wrangler login
   npx wrangler deploy
   ```

4. On GitHub, Settings, Developer settings, Fine-grained tokens: a token with access to only the
   private repository and only *Contents: Read-only*. Give it to the Worker, pasting it when asked:

   ```bash
   npx wrangler secret put GITHUB_TOKEN
   ```

5. Invite each person. This makes a random code, stores only its SHA-256 as the secret
   `CODE_<name>`, and prints their invite:

   ```bash
   ./add-friend.sh alex https://spatch-source.<you>.workers.dev
   ```

   `npx wrangler secret delete CODE_alex` takes alex's access away.

`index.json` there comes from `spatch source <input.apkm> --out source/cloudflare --index-only`,
which keeps one file per version in `source/cloudflare/entries/` and lists them all, newest first, so
older versions stay downloadable after a new one ships. `node --test
source/cloudflare/worker.test.mjs` checks the Worker against a fake GitHub.

## When Spotify updates

Patches are made per Spotify build, never for "any version":

1. Get the new APKM. Port the patches: the pinned hashes in `src/input.rs`, then every edit in
   `src/edits.rs` and target in `src/patch.rs` whose anchor moved. Each one is guarded, so the build
   names whatever no longer matches instead of producing a broken Spotify.
2. Build the debug variant, install it on a phone, and run Spatch settings, Developer options, Run
   all tests, then look over the screens the patches change.
3. Add the APKM to the source **before** releasing patches for it, so no phone gets a bundle it can't
   download for: upload release `apkm-<versionName>` to the private repository, run `spatch source
   ... --index-only` into `source/cloudflare/`, and `npx wrangler deploy`.
4. Release `patches-v<version>` from the new APKM. Phones get the update notification, download the
   new Spotify from the source, and install it as an update with the same key, keeping their data.

Older APKMs and their releases stay where they are; the manager only takes the entry matching its
bundle, so anyone pointed at an older bundle can still install it.

## Serving it yourself with Caddy

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
