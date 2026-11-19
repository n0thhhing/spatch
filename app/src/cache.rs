//! The last answer of each screen (library, playlists, pages, recently played), kept in memory and on disk so a
//! screen opens instantly with what it showed last time while the core is asked again.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jbyteArray;
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

static DIR: Mutex<Option<PathBuf>> = Mutex::new(None);
static MEMORY: Mutex<Option<HashMap<String, Vec<u8>>>> = Mutex::new(None);
/// Answers kept on disk; the oldest go first.
const MOST: usize = 400;

pub fn set_dir(dir: PathBuf) {
    let _ = fs::create_dir_all(&dir);
    if let Ok(entries) = fs::read_dir(&dir) {
        let mut files: Vec<_> = entries.flatten().filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path()))).collect();
        if files.len() > MOST {
            files.sort();
            files.iter().take(files.len() - MOST).for_each(|(_, path)| drop(fs::remove_file(path)));
        }
    }
    *DIR.lock().unwrap() = Some(dir);
}

fn file(key: &str) -> Option<PathBuf> {
    // FNV-1a: a stable file name for the key.
    let hash = key.bytes().fold(0xcbf2_9ce4_8422_2325u64, |h, b| (h ^ b as u64).wrapping_mul(0x0100_0000_01b3));
    DIR.lock().unwrap().as_ref().map(|d| d.join(format!("{hash:016x}")))
}

/// Stores `value` under `key`; the file starts with the key so a hash collision reads as a miss.
pub fn put(key: &str, value: &[u8]) {
    MEMORY.lock().unwrap().get_or_insert_with(HashMap::new).insert(key.to_string(), value.to_vec());
    let Some(path) = file(key) else { return };
    let temporary = path.with_extension("new");
    if fs::write(&temporary, [encode(&[key.to_string()]).as_slice(), value].concat()).is_ok() {
        let _ = fs::rename(&temporary, &path);
    }
}

pub fn get(key: &str) -> Option<Vec<u8>> {
    if let Some(value) = MEMORY.lock().unwrap().as_ref().and_then(|m| m.get(key)) {
        return Some(value.clone());
    }
    let bytes = fs::read(file(key)?).ok()?;
    let (stored, used) = decode_one(&bytes)?;
    (stored == key).then(|| bytes[used..].to_vec())
}

/// Strings as a 4-byte little-endian length and the UTF-8 bytes, one after another.
pub fn encode(fields: &[String]) -> Vec<u8> {
    let total: usize = fields.iter().map(|f| 4 + f.len()).sum();
    let mut out = Vec::with_capacity(total);
    for f in fields {
        out.extend_from_slice(&(f.len() as u32).to_le_bytes());
        out.extend_from_slice(f.as_bytes());
    }
    out
}

fn decode_one(bytes: &[u8]) -> Option<(String, usize)> {
    let len = u32::from_le_bytes(bytes.get(..4)?.try_into().ok()?) as usize;
    Some((String::from_utf8(bytes.get(4..4 + len)?.to_vec()).ok()?, 4 + len))
}

pub fn decode(mut bytes: &[u8]) -> Option<Vec<String>> {
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let (field, used) = decode_one(bytes)?;
        out.push(field);
        bytes = &bytes[used..];
    }
    Some(out)
}

/// Where the answers live (the app's cache folder).
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeCacheDir(mut env: JNIEnv, _: JClass, dir: JString) {
    if let Ok(dir) = env.get_string(&dir) {
        set_dir(PathBuf::from(String::from(dir)));
    }
}

/// The packed UTF-8 bytes last remembered under `key`, or null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeCached(mut env: JNIEnv, _: JClass, key: JString) -> jbyteArray {
    let Ok(key) = env.get_string(&key).map(String::from) else { return std::ptr::null_mut() };
    let Some(bytes) = get(&key) else { return std::ptr::null_mut() };
    env.byte_array_from_slice(&bytes).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

/// Remembers a screen's packed answer under `key`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeRemember(mut env: JNIEnv, _: JClass, key: JString, packed: JByteArray) {
    let Ok(key) = env.get_string(&key).map(String::from) else { return };
    let Ok(bytes) = env.convert_byte_array(&packed) else { return };
    put(&key, &bytes);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn answers_survive_a_restart_and_keys_dont_mix() {
        let dir = std::env::temp_dir().join(format!("spatch-cache-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        set_dir(dir.clone());
        let fields = vec!["".to_string(), "Song é".into(), "".into(), "spotify:track:1".into()];
        put("playlist:a", &encode(&fields));
        MEMORY.lock().unwrap().take(); // a restart: only the disk is left
        assert_eq!(get("playlist:a").and_then(|b| decode(&b)), Some(fields));
        assert_eq!(get("playlist:b"), None);
        assert_eq!(decode(&[1, 0, 0]), None, "a cut-off file reads as a miss");
        let _ = fs::remove_dir_all(&dir);
    }
}
