# Spotify hooks on fresh

[The edit table](../src/edits.rs) applies these five hooks to Spotify 9.2.0.26. Each edit requires
exactly one anchor match.

| Spotify entry point | Spatch call | Purpose |
| --- | --- | --- |
| `ClientBase` transport assignment | `CoreBridge.wrap` | Makes Spotify's Esperanto transport available to the Rust app |
| `SpotifyMainActivity.onResume` | `SpatchApp.resumed` | Mounts Spatch and hides the original activity content |
| `AudioEffectsListener` session creation | `Audio.created` | Tracks the playback audio session |
| `AudioEffectsListener.onPaused` | `Audio.paused` | Updates capture state on playback pause and resume |
| `AudioEffectsListener` session destruction | `Audio.destroyed` | Releases capture when the session ends |

`ClientBase` moves out of `classes.dex` and `AudioEffectsListener` moves out of `classes2.dex` to
make room for their new calls. They keep their original class names in `classes16.dex`, where the
four Java helpers also live.

- [CoreBridge.java](../java/dex/src/spatch/core/CoreBridge.java) bridges calls and subscriptions between Rust and Spotify's transport.
- [SpatchApp.java](../java/dex/src/spatch/core/SpatchApp.java) loads the Rust library and mounts the UI on the activity.
- [SpatchUi.java](../java/dex/src/spatch/core/SpatchUi.java) hosts Android views and renders the UI.
- [Audio.java](../java/dex/src/spatch/core/Audio.java) captures FFT samples from the active playback session.

For input hashes and version porting, see [Patch targets](targets.md). The earlier redesign lives on
the `main` branch.
