# External Control Intents

Spatch exports a `BroadcastReceiver` (`spatch.SpatchWidget`) that external automation apps (Tasker, MacroDroid, Automate, KWGT, Termux, ADB, etc.) can send broadcast intents to.

If Spatch is closed when a broadcast arrives, the receiver wakes Spotify's playback service in the background, loads Spatch's core without opening the main UI, and executes the command (resuming the last-played track at its saved position for `PLAY` / `TOGGLE`).

## Target

- **Package:** `com.spotify.music` (or your cloned package name, shown in **Settings → Developer → Automation & intents**)
- **Component:** `com.spotify.music/spatch.SpatchWidget`
- **Send as:** Broadcast (include the package or component name so Android 8+ delivers it to the manifest receiver)

## Actions

| Action | Description | Extras |
| --- | --- | --- |
| `spatch.action.PLAY` | Resume playback (or last played at saved position if closed) | — |
| `spatch.action.PAUSE` | Pause playback | — |
| `spatch.action.TOGGLE` | Toggle play / pause | — |
| `spatch.action.NEXT` | Skip to next track | — |
| `spatch.action.PREVIOUS` | Skip to previous track | — |
| `spatch.action.SEEK` | Seek within the current track | `position_ms` (or `position`): `long`, `int`, or numeric `String` in milliseconds |
| `spatch.action.PLAY_URI` | Play a Spotify URI | `uri` (`String`, e.g. `spotify:track:...`), optional `context` (`String`), optional `shuffle` (`boolean`), optional `position_ms` (`long`/`int`) |
| `spatch.action.SHUFFLE` | Set shuffle state | `enabled` or `shuffle` (`boolean`, default `true`), or `mode` (`int`: `0` off, `1` on) |
| `spatch.action.REPEAT` | Set repeat mode | `mode` (`int`: `0` off, `1` context/all, `2` track/one) |
| `spatch.action.QUEUE` | Add a track to the queue | `uri` (`String`, e.g. `spotify:track:...`) |

## Examples

### ADB

```sh
# Resume last played in the background
adb shell am broadcast -a spatch.action.PLAY -n com.spotify.music/spatch.SpatchWidget

# Play a specific track at 30 seconds
adb shell am broadcast -a spatch.action.PLAY_URI -n com.spotify.music/spatch.SpatchWidget \
  --es uri "spotify:track:4cOdK2wGLETKBW3PvgPWqT" \
  --el position_ms 30000

# Seek to 1:00
adb shell am broadcast -a spatch.action.SEEK -n com.spotify.music/spatch.SpatchWidget \
  --el position_ms 60000
```

### Tasker / MacroDroid

- **Action:** Send Intent
- **Action field:** e.g. `spatch.action.TOGGLE`
- **Package:** `com.spotify.music` (or your clone package)
- **Class:** `spatch.SpatchWidget`
- **Target:** `Broadcast Receiver`
- **Extra (for `PLAY_URI`):** `uri:spotify:playlist:...`
