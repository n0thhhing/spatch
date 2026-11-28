//! The last answer of each screen (library, playlists, pages, recently played), kept in memory and on disk so a
//! screen opens instantly with what it showed last time while the core is asked again.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jbyteArray;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

static WRITE_SEQ: AtomicU64 = AtomicU64::new(0);

#[derive(Default)]
struct Answers {
    dir: Option<PathBuf>,
    account: Option<String>,
    memory: HashMap<String, Vec<u8>>,
    writes: usize,
}

static ANSWERS: Mutex<Option<Answers>> = Mutex::new(None);
const MOST: usize = 400;

fn prune_dir(dir: &Path) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    let mut files: Vec<_> = entries.flatten().filter_map(|e| {
        let meta = e.metadata().ok()?;
        if !meta.is_file() { return None; }
        Some((meta.modified().ok()?, e.path()))
    }).collect();
    files.sort();
    for (_, path) in files.iter().take(files.len().saturating_sub(MOST)) { let _ = fs::remove_file(path); }
}

struct PendingWrite {
    path: PathBuf,
    account: String,
    prune_dir: Option<PathBuf>,
}

impl PendingWrite {
    fn flush(self, key: &str, value: &[u8]) {
        // Unique temp file per write so concurrent puts after releasing ANSWERS never collide on the same .new path.
        let seq = WRITE_SEQ.fetch_add(1, Ordering::Relaxed);
        let temporary = self.path.with_extension(format!("new.{seq}"));
        let payload = [crate::packed::encode(&[format!("{}:{key}", self.account)]).as_slice(), value].concat();
        if fs::write(&temporary, payload).is_ok() {
            let _ = fs::rename(&temporary, &self.path);
        }
        if let Some(dir) = self.prune_dir.as_deref() {
            prune_dir(dir);
        }
    }
}

impl Answers {
    fn file(&self, key: &str) -> Option<PathBuf> {
        // Unverified identities can use memory, never another launch's private answers.
        let account = self.account.as_ref()?;
        let scoped = format!("{}:{account}:{key}", account.len());
        let hash = scoped.bytes().fold(0xcbf2_9ce4_8422_2325u64, |h, b| (h ^ b as u64).wrapping_mul(0x0100_0000_01b3));
        self.dir.as_ref().map(|d| d.join(format!("{hash:016x}")))
    }

    fn account(&mut self, account: Option<&str>) {
        if account.is_none() || self.account.as_deref() != account {
            self.memory.clear();
            self.account = account.map(str::to_string);
        }
    }

    fn prune(&self) {
        if let Some(dir) = self.dir.as_deref() {
            prune_dir(dir);
        }
    }

    fn stage_put(&mut self, key: &str, value: &[u8]) -> Option<PendingWrite> {
        if self.memory.len() >= MOST && !self.memory.contains_key(key) {
            if let Some(victim) = self.memory.keys().next().cloned() { self.memory.remove(&victim); }
        }
        self.memory.insert(key.to_string(), value.to_vec());
        let path = self.file(key)?;
        let account = self.account.clone()?;
        self.writes += 1;
        let prune_dir = self.writes.is_multiple_of(32).then(|| self.dir.clone()).flatten();
        Some(PendingWrite { path, account, prune_dir })
    }

    fn get(&self, key: &str) -> Option<Vec<u8>> {
        if let Some(value) = self.memory.get(key) { return Some(value.clone()); }
        let bytes = fs::read(self.file(key)?).ok()?;
        let (stored, used) = crate::packed::decode_one(&bytes)?;
        (stored == format!("{}:{key}", self.account.as_deref()?)).then(|| bytes[used..].to_vec())
    }
}

pub fn set_dir(dir: PathBuf) {
    // v2 never reads the old, unscoped account cache.
    let dir = dir.join("v2");
    let _ = fs::create_dir_all(&dir);
    let mut state = ANSWERS.lock().unwrap();
    let cache = state.get_or_insert_with(Answers::default);
    cache.dir = Some(dir);
    cache.prune();
}

/// The answers folder, for writing side files next to it.
pub fn dir() -> Option<PathBuf> {
    ANSWERS.lock().unwrap().as_ref()?.dir.clone()
}

pub fn set_account(account: Option<&str>) {
    ANSWERS.lock().unwrap().get_or_insert_with(Answers::default).account(account);
}

pub fn put(key: &str, value: &[u8]) {
    let pending = ANSWERS.lock().unwrap().get_or_insert_with(Answers::default).stage_put(key, value);
    if let Some(pending) = pending {
        pending.flush(key, value);
    }
}

pub fn get(key: &str) -> Option<Vec<u8>> {
    ANSWERS.lock().unwrap().as_ref()?.get(key)
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

    impl Answers {
        fn put(&mut self, key: &str, value: &[u8]) {
            if let Some(pending) = self.stage_put(key, value) {
                pending.flush(key, value);
            }
        }
    }

    #[test]
    fn answers_are_bounded_and_isolated_by_verified_account() {
        let dir = std::env::temp_dir().join(format!("spatch-cache-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let mut cache = Answers { dir: Some(dir.clone()), ..Default::default() };
        cache.put("library", b"unverified");
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 0);
        cache.account(Some("alice"));
        assert_eq!(cache.get("library"), None);
        cache.put("library", b"alice's playlists");
        cache.memory.clear();
        assert_eq!(cache.get("library").unwrap(), b"alice's playlists");
        cache.account(Some("bob"));
        assert_eq!(cache.get("library"), None);
        cache.put("library", b"bob's playlists");
        cache.account(Some("alice"));
        assert_eq!(cache.get("library").unwrap(), b"alice's playlists");
        for n in 0..500 { cache.put(&n.to_string(), b"answer"); }
        assert!(cache.memory.len() <= MOST);
        cache.prune();
        assert!(fs::read_dir(&dir).unwrap().count() <= MOST);
        cache.account(None);
        assert_eq!(cache.get("library"), None);
        assert_eq!(crate::packed::decode(&[1, 0, 0]), None);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn public_put_flushes_outside_lock_and_prunes_every_32_writes() {
        let dir = std::env::temp_dir().join(format!("spatch-cache-pub-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        set_dir(dir.clone());
        set_account(Some("alice"));
        for n in 0..448 {
            put(&format!("k{n}"), b"v");
        }
        assert_eq!(get("k447").as_deref(), Some(b"v".as_slice()));
        assert!(fs::read_dir(dir.join("v2")).unwrap().count() <= MOST);
        set_account(None);
        let _ = fs::remove_dir_all(&dir);
    }
}
