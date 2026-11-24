//! Offline JNI endpoints and account-scoped item labels.

use super::*;

/// Library items that can be downloaded (playlists, albums, podcasts), for asking the core about each.
fn downloadable_uris() -> Vec<String> {
    let mut uris: Vec<String> = LIBRARY
        .lock()
        .unwrap()
        .iter()
        .flatten()
        .filter(|i| i.kind != library::Kind::Artist && i.kind != library::Kind::Other && !i.uri.is_empty())
        .map(|i| i.uri.clone())
        .collect();
    if let Some(fields) = crate::cache::get(DOWNLOAD_ITEMS).and_then(|bytes| crate::cache::decode(&bytes)) {
        uris.extend(fields.as_chunks::<5>().0.iter().map(|e| e[2].clone()));
    }
    uris.sort(); uris.dedup();
    uris
}

/// Starts following the core's downloads and loads what's there. Blocks for the first answer; call it off the
/// main thread once the library is known.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadsStart(_: JNIEnv, _: JClass) {
    use crate::spotify::downloads;
    downloads::watch();
    if let Err(error) = downloads::refresh(&downloadable_uris()) {
        log::warn!("Downloads: {error}");
    }
}

/// Everything downloaded or on its way: uri, state (1 error, 2 requested, 3 downloaded, 4 downloading,
/// 5 waiting) and percent (0 to 100), as three strings each.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloads(env: JNIEnv, _: JClass) -> jbyteArray {
    let fields: Vec<String> = crate::spotify::downloads::snapshot()
        .into_iter()
        .flat_map(|d| [d.uri, d.state.code().to_string(), ((d.percent * 100.0).round() as i32).to_string()])
        .collect();
    packed_strings(&env, &fields)
}

/// Bumps on any change to downloads, progress included.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadsRevision(_: JNIEnv, _: JClass) -> jlong {
    crate::spotify::downloads::revision() as jlong
}

/// Bumps when a download is added, removed or changes state (not for progress alone).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadsSet(_: JNIEnv, _: JClass) -> jlong {
    crate::spotify::downloads::set_revision() as jlong
}

const DOWNLOAD_ITEMS: &str = "download-items";

fn download_metadata(uri: &str, packed: &[u8]) -> Result<Vec<String>, String> {
    let entry = crate::cache::decode(packed).ok_or("Invalid download details")?;
    if entry.len() != 5 || entry[2] != uri || !matches!(entry[4].parse::<i32>(), Ok(-1..=3)) {
        return Err("Invalid download details".into());
    }
    Ok(entry)
}

fn remember_download(uri: &str, action: jint, entry: Option<Vec<String>>) {
    if action == 0 && entry.is_none() { return; }
    let existing = crate::cache::get(DOWNLOAD_ITEMS).and_then(|bytes| crate::cache::decode(&bytes)).unwrap_or_default();
    let active: std::collections::HashSet<_> = crate::spotify::downloads::snapshot().into_iter().map(|d| d.uri).collect();
    let mut fields: Vec<String> = existing.as_chunks::<5>().0.iter()
        .filter(|e| action != 2 && e[2] != uri && active.contains(&e[2])).flatten().cloned().collect();
    if let Some(entry) = entry { fields.extend(entry); }
    crate::cache::put(DOWNLOAD_ITEMS, &crate::cache::encode(&fields));
}

/// Adds (0), removes (1), or clears (2) downloads. Optional row metadata stays in the account cache.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownload(mut env: JNIEnv, _: JClass, uri: JString, action: jint, packed: JByteArray) -> jstring {
    use crate::spotify::downloads;
    let uri = read(&mut env, &uri).unwrap_or_default();
    let entry = if packed.is_null() {
        None
    } else {
        let parsed = env.convert_byte_array(&packed).map_err(|_| "Invalid download details".to_string())
            .and_then(|bytes| download_metadata(&uri, &bytes));
        match parsed {
            Ok(entry) if action == 0 => Some(entry),
            _ => return string(&mut env, "Invalid download details"),
        }
    };
    let epoch = bridge::session();
    let result = match action {
        0 if uri.starts_with("spotify:") => downloads::add(&uri),
        1 if uri.starts_with("spotify:") => downloads::remove(&uri),
        2 => downloads::remove_all(),
        _ => Err("Nothing to download".into()),
    }.and_then(|()| bridge::in_session(epoch, || remember_download(&uri, action, entry))
        .ok_or_else(|| "Account session changed".to_string()));
    string(&mut env, &result.err().unwrap_or_default())
}

/// The whole queue: downloaded songs, total songs, downloaded bytes, total bytes, downloading ("true" or "false"),
/// percent (0 to 100), seconds left, bytes per second, queued songs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeDownloadTotals(mut env: JNIEnv, _: JClass) -> jobjectArray {
    let t = crate::spotify::downloads::total();
    strings(
        &mut env,
        vec![
            t.downloaded_tracks.to_string(),
            t.total_tracks.to_string(),
            t.downloaded_bytes.to_string(),
            t.total_bytes.to_string(),
            t.downloading.to_string(),
            ((t.percent * 100.0).round() as i32).to_string(),
            t.seconds_left.to_string(),
            t.speed.to_string(),
            t.queued_tracks.to_string(),
        ],
    )
}

/// The cache: everything in bytes, the part kept for downloads, free space on the phone, the phone's size; or
/// ["!", reason]. Blocks for the core.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeStorage(mut env: JNIEnv, _: JClass) -> jobjectArray {
    let fields = match crate::spotify::downloads::storage() {
        Ok(s) => vec![s.current.to_string(), s.locked.to_string(), s.free.to_string(), s.total.to_string()],
        Err(error) => vec!["!".into(), error],
    };
    strings(&mut env, fields)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn metadata_must_match_the_requested_item() {
        let uri = "spotify:track:one";
        let mut entry = vec!["Song".into(), "Artist".into(), uri.into(), "cover".into(), "-1".into()];
        assert_eq!(download_metadata(uri, &crate::cache::encode(&entry)), Ok(entry.clone()));
        assert!(download_metadata("spotify:track:other", &crate::cache::encode(&entry)).is_err());
        entry[4] = "999".into();
        assert!(download_metadata(uri, &crate::cache::encode(&entry)).is_err());
        assert!(download_metadata(uri, &[0xff]).is_err());
    }
}
