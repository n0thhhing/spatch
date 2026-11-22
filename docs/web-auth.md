# Web-player auth for extended metadata

Status: implemented, unverified on device
Scope: song details only. Playback is untouched and already working.

## Symptom

Every metadata batch is refused:

    I Spatch : Observe metadata: resolved 0 / 16 tracks, statuses: {0: 32}
    I Spatch : Song details from extended metadata (US, free): 0 of 16; refused by status: [(502, 16)]
    I Spatch : Observe metadata: resolved 0 / 50 tracks, statuses: {0: 100}
    I Spatch : Song details from extended metadata (US, free): 0 of 100; refused by status: [(502, 100)]

Rows, artwork and the now-playing bar stay blank. The account is `country_code=US`,
`catalogue=free`, `type=free`.

## What the app actually does

`SpotifyWeb.extendedMetadata` is a Retrofit method annotated `@p.ngr0("extended-metadata/v0/extended-metadata")`,
served by `RetrofitMaker.createWebgateService`. The webgate client stamps `Authorization` and
`client-token` through its auth interceptor, `Lp/w7m1;->a(Lp/hy01;)`, which delegates to the suspend
helper `Lp/w7m1;->b(Lp/hy01;Lp/a631;Ljava/lang/String;Lp/fh31;)`.

Both interceptors short-circuit on a `No-Webgate-Authentication` request header. `SpotifyWeb` does not
set it, so the app's own Android token is attached and the service answers 502.

## Evidence

One body, one track, six header permutations. Only the bearer varies.

| # | Bearer | `app-platform` | UA | `product`/`platform` | `client-token` | Result |
| --- | --- | --- | --- | --- | --- | --- |
| A | web-player | WebPlayer | browser | none | yes | 200, 1395 B |
| B | web-player | WebPlayer | browser | 9/39 | yes | 200, 1395 B |
| C | web-player | Android | app | none | yes | 200, 1395 B |
| D | web-player | Android | app | 9/39 | yes | 200, 1395 B |
| E | web-player | Android | app | 9/39 | no | 200, 1395 B |
| F | web-player | WebPlayer | browser | 9/39 | yes | 500, `Error serializing response payload` |

A–E are byte-identical. F only proves the response is protobuf and will not be JSON-negotiated.

## Conclusion

The bearer is the sole axis. Platform, user agent, query parameters and `client-token` are all
irrelevant to this endpoint. An app token 502s; a web-player token 200s.

This rules out a request-shape bug and rules in client identity, and it means the fix is a header
swap on one path rather than a new transport, body builder or response decoder.

## Design

Replace the `Authorization` value for requests whose path contains `extended-metadata`, and nothing
else. Every other webgate request keeps the app's token. Any failure returns the original token, so
the app degrades to today's behaviour instead of breaking.

Rejected alternatives:

- Relabelling the app token as WebPlayer — insufficient, the token itself is the gate (C, D).
- A separate HTTP client for metadata — re-implements body encoding, retries and the etag cache.
- Patching `PublicTrackMetadata` — it already works and is the fallback, not the target.

## Hook site

`Lp/w7m1;->b(Lp/hy01;Lp/a631;Ljava/lang/String;Lp/fh31;)Lp/fh31;` is static, has `.locals 2`, and
already holds the request in `p1` and the token in `p2`. The insert needs no register renumbering and
no `.registers` change:

    .method public static b(Lp/hy01;Lp/a631;Ljava/lang/String;Lp/fh31;)Lp/fh31;
        .locals 2

        invoke-static {p1, p2}, Lspatch/WebAuth;->auth(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;

        move-result-object p2

Both suspend call sites (`:395`, `:479`) route through `b`, so one anchor covers the interceptor.

## Helper

`java/dex/src/spatch/WebAuth.java`, registered in `src/helpers.rs`, compiled into `classes16.dex`.

- Matches the path by reflection on `Lp/a631;->a` (`Lp/bf70`) and its `i()` method, which rebuilds
  a real `java.net.URI` via `g()`. `bf70.toString()` only returns field `i` directly, one raw
  component, not the assembled URL, so the matcher reads `i().getPath()` instead.
- Logs the first `hit <path>` and the first 6 `miss <path>` lines, not every call: startup sends
  several unrelated webgate requests (messaging, notifications) before song details are asked for,
  so a plain first-N window fills with misses and never reaches the real request.
- Reuses `PublicTrackMetadata.anonymousToken()` (made package-visible and `synchronized`, since it
  is now called from two entry points) for the bearer, rather than minting a second session.
- Returns the caller's token on any throwable.

## Why the embed token rather than `sp_dc`

`sp_dc` plus a TOTP HMAC works, but needs a per-user cookie and a bundle constant tied to `totpVer=61`
that dies on rotation. The embed endpoint needs no credential, returns its own expiry, and is already
proven reachable from this app. It also removes the need for any user-facing configuration.

## Degradation

| Failure | Effect |
| --- | --- |
| Embed endpoint down or slow | Returns app token; batch 502s as today |
| Path matcher misses | Returns app token; batch 502s as today |
| Token expired mid-batch | One 502, refreshed on the next call |
| Anonymous token, private or unreleased track | Possible blank row; not yet measured |

The 502 is never worse than the status quo, and `PublicTrackMetadata` continues to run underneath.

## Scope

Metadata only. Playback is unchanged and was never broken: the baseline shows audio focus,
`MediaPlayerWrapper` updates, the echo-reference patch and `save_track.uri` while the metadata calls
were failing. Playback is driven by the track URI, not by titles.

Server-side free-tier limits (shuffle, ads, skip budget) are also unaffected, since they are enforced
against the account rather than the client.

## Verification

    adb logcat -c; adb logcat -s Spatch:I SpatchBridge:I SpatchBridge:W

Expect one `hit extended-metadata/v0/extended-metadata` line (after up to 6 unrelated `miss` lines), no
`SpatchBridge` warning, and the Rust side's `Song details from extended metadata` denominator
dropping to zero refused.

Falsifiers to check first:

- A `miss` logged for a metadata row means the path read is wrong, not that the token is bad.
  Distinguish the two before touching the token.
- A nonzero refusal count alongside `hit` and no `SpatchBridge` warning means the anonymous token
  is not sufficient and the flow needs a real web-player session.

## Open questions

- Whether the anonymous session returns data for every track or only public ones.
- Whether the app's etag cache behaves correctly across a token swap.
- Whether the embed endpoint's response shape is stable enough to rely on.
