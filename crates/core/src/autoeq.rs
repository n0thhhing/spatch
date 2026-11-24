//! Fast AutoEq headphone catalog parser, binary index cache, and search.
//!
//! Matches `AutoEqCatalog.java`'s exact URL normalization and security rules without regex or
//! per-line `java.net.URI` allocations.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint};
use std::fs;
use std::path::Path;
use std::sync::Mutex;

const ROOT: &str = "/jaakkopasanen/AutoEq/master/results/";
const HOST: &str = "raw.githubusercontent.com";
const BIN_MAGIC: &[u8; 4] = b"AEQ1";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub lower: String,
    pub url: String,
}

static CATALOG: Mutex<Vec<Entry>> = Mutex::new(Vec::new());

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// Validates and percent-decodes a relative URI path segment from a markdown link (matching `URI.create(link).getPath()`).
fn decode_uri_path(raw: &str) -> Option<String> {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        // Java URI.create rejects whitespace, control chars, query/fragment, backslash, etc.
        if b <= 0x20 || b == 0x7f || matches!(b, b'?' | b'#' | b'\\' | b'"' | b'<' | b'>' | b'^' | b'`' | b'{' | b'|' | b'}') {
            return None;
        }
        if b == b'%' {
            if i + 2 >= bytes.len() {
                return None;
            }
            let hi = hex_val(bytes[i + 1])?;
            let lo = hex_val(bytes[i + 2])?;
            let decoded = (hi << 4) | lo;
            if decoded == b'\\' {
                return None;
            }
            out.push(decoded);
            i += 3;
        } else {
            out.push(b);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// Normalizes dot-segments (`.` and `..`) in an absolute path, rejecting traversal above root.
fn normalize_path(path: &str) -> Option<String> {
    let mut parts: Vec<&str> = Vec::new();
    let trailing_slash = path.ends_with('/');
    for seg in path.split('/') {
        match seg {
            "" | "." => {}
            ".." => {
                parts.pop()?;
            }
            other => parts.push(other),
        }
    }
    let mut normalized = String::with_capacity(path.len());
    for seg in &parts {
        normalized.push('/');
        normalized.push_str(seg);
    }
    if trailing_slash && !normalized.ends_with('/') {
        normalized.push('/');
    }
    if normalized.is_empty() {
        normalized.push('/');
    }
    Some(normalized)
}

/// Percent-encodes a path the same way `java.net.URI("https", host, path, null).toASCIIString()` does.
fn encode_uri_path(path: &str, out: &mut String) {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    for &b in path.as_bytes() {
        let safe = b.is_ascii_alphanumeric()
            || matches!(
                b,
                b'/' | b'-' | b'_' | b'.' | b'!' | b'~' | b'*' | b'\'' | b'(' | b')'
                    | b':' | b'@' | b'&' | b'=' | b'+' | b'$' | b',' | b';'
            );
        if safe {
            out.push(b as char);
        } else {
            out.push('%');
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 0x0f) as usize] as char);
        }
    }
}

/// Parses AutoEq's `results/README.md` into validated `(name, lower, url)` entries.
pub fn parse(markdown: &str) -> Vec<Entry> {
    let mut result = Vec::with_capacity(markdown.len() / 80);
    let mut full = String::with_capacity(256);
    let mut url = String::with_capacity(256);

    for raw_line in markdown.split('\n') {
        let line = raw_line.strip_suffix('\r').unwrap_or(raw_line);
        if !line.starts_with("- [") || !line.ends_with(')') {
            continue;
        }
        let Some(divider) = line.find("](") else { continue };
        if divider < 4 {
            continue;
        }
        let name = &line[3..divider];
        let link = &line[divider + 2..line.len() - 1];
        if !link.starts_with("./") {
            continue;
        }
        let Some(rel_decoded) = decode_uri_path(&link[2..]) else { continue };
        if rel_decoded.contains('\\') {
            continue;
        }
        full.clear();
        full.push_str(ROOT);
        full.push_str(&rel_decoded);
        let Some(decoded) = normalize_path(&full) else { continue };
        if !decoded.starts_with(ROOT) {
            continue;
        }
        let tail = &decoded[ROOT.len()..];
        let Some(last_slash) = tail.rfind('/') else { continue };
        let filename = &tail[last_slash + 1..];
        if filename.is_empty() {
            continue;
        }

        url.clear();
        url.push_str("https://");
        url.push_str(HOST);
        encode_uri_path(&decoded, &mut url);
        url.push('/');
        encode_uri_path(filename, &mut url);
        url.push_str("%20GraphicEQ.txt");

        result.push(Entry {
            name: name.to_string(),
            lower: name.to_lowercase(),
            url: url.clone(),
        });
    }
    result
}

/// Encodes parsed entries into a compact binary cache (`AEQ1` + count + length-prefixed name/url pairs).
fn encode_bin(entries: &[Entry]) -> Vec<u8> {
    let cap = 8 + entries.iter().map(|e| 8 + e.name.len() + e.url.len()).sum::<usize>();
    let mut out = Vec::with_capacity(cap);
    out.extend_from_slice(BIN_MAGIC);
    out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for e in entries {
        out.extend_from_slice(&(e.name.len() as u32).to_le_bytes());
        out.extend_from_slice(e.name.as_bytes());
        out.extend_from_slice(&(e.url.len() as u32).to_le_bytes());
        out.extend_from_slice(e.url.as_bytes());
    }
    out
}

fn decode_bin(bytes: &[u8]) -> Option<Vec<Entry>> {
    if bytes.len() < 8 || &bytes[..4] != BIN_MAGIC {
        return None;
    }
    let count = u32::from_le_bytes(bytes[4..8].try_into().ok()?) as usize;
    let mut entries = Vec::with_capacity(count.min(65_536));
    let mut pos = 8;
    for _ in 0..count {
        let nlen = u32::from_le_bytes(bytes.get(pos..pos + 4)?.try_into().ok()?) as usize;
        pos += 4;
        let name = std::str::from_utf8(bytes.get(pos..pos + nlen)?).ok()?.to_string();
        pos += nlen;
        let ulen = u32::from_le_bytes(bytes.get(pos..pos + 4)?.try_into().ok()?) as usize;
        pos += 4;
        let url = std::str::from_utf8(bytes.get(pos..pos + ulen)?).ok()?.to_string();
        pos += ulen;
        let lower = name.to_lowercase();
        entries.push(Entry { name, lower, url });
    }
    if pos != bytes.len() || entries.is_empty() {
        return None;
    }
    Some(entries)
}

fn write_atomic(path: &Path, bytes: &[u8]) {
    let tmp = path.with_extension("tmp");
    if fs::write(&tmp, bytes).is_ok() && fs::rename(&tmp, path).is_err() {
        let _ = fs::remove_file(&tmp);
    }
}

/// Loads from binary cache, falls back to cached markdown, or parses fresh markdown and writes both caches.
pub fn load_or_store(dir: &Path, fresh_markdown: Option<&str>) -> usize {
    let bin_path = dir.join("autoeq-headphones.bin");
    let md_path = dir.join("autoeq-headphones.md");

    let entries = if let Some(md) = fresh_markdown {
        let parsed = parse(md);
        if !parsed.is_empty() {
            write_atomic(&md_path, md.as_bytes());
            write_atomic(&bin_path, &encode_bin(&parsed));
        }
        parsed
    } else if let Ok(bin) = fs::read(&bin_path) {
        if let Some(parsed) = decode_bin(&bin) {
            parsed
        } else if let Ok(md) = fs::read_to_string(&md_path) {
            let parsed = parse(&md);
            if !parsed.is_empty() {
                write_atomic(&bin_path, &encode_bin(&parsed));
            }
            parsed
        } else {
            Vec::new()
        }
    } else if let Ok(md) = fs::read_to_string(&md_path) {
        let parsed = parse(&md);
        if !parsed.is_empty() {
            write_atomic(&bin_path, &encode_bin(&parsed));
        }
        parsed
    } else {
        Vec::new()
    };

    let count = entries.len();
    if count > 0 {
        *CATALOG.lock().unwrap() = entries;
    }
    count
}

fn pack_pairs<'a>(iter: impl Iterator<Item = (&'a str, &'a str)>) -> Vec<u8> {
    let mut out = Vec::new();
    for (name, url) in iter {
        out.extend_from_slice(&(name.len() as u32).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
        out.extend_from_slice(&(url.len() as u32).to_le_bytes());
        out.extend_from_slice(url.as_bytes());
    }
    out
}

/// Searches the loaded catalog for `query` (case-insensitive substring), returning up to `limit` `(name, url)` pairs.
pub fn search_packed(query: &str, limit: usize) -> Vec<u8> {
    let q = query.trim().to_lowercase();
    if q.is_empty() || limit == 0 {
        return Vec::new();
    }
    let guard = CATALOG.lock().unwrap();
    pack_pairs(
        guard
            .iter()
            .filter(|e| e.lower.contains(&q))
            .take(limit)
            .map(|e| (e.name.as_str(), e.url.as_str())),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_AutoEqCatalog_nativeLoad(
    mut env: JNIEnv,
    _: JClass,
    dir: JString,
    markdown: JByteArray,
) -> jint {
    let Ok(dir_str) = env.get_string(&dir).map(String::from) else { return 0 };
    let fresh = if markdown.is_null() {
        None
    } else {
        match env.convert_byte_array(&markdown) {
            Ok(bytes) => String::from_utf8(bytes).ok(),
            Err(_) => None,
        }
    };
    load_or_store(Path::new(&dir_str), fresh.as_deref()) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_AutoEqCatalog_nativeSearch(
    mut env: JNIEnv,
    _: JClass,
    query: JString,
    limit: jint,
) -> jbyteArray {
    let Ok(q) = env.get_string(&query).map(String::from) else { return std::ptr::null_mut() };
    let packed = search_packed(&q, limit.max(0) as usize);
    env.byte_array_from_slice(&packed).map_or(std::ptr::null_mut(), |a| a.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_valid_models_and_rejects_unsafe_links() {
        let md = "- [A (ANC Off)](./Source/GRAS/A%20(ANC%20Off))\n\
                  - [Unicode + é](./Source/GRAS/Unicode%20+%20%C3%A9)\n\
                  - [Bad](./../../../outside)\n\
                  - [Bad](./Source/A?query=1)\n\
                  - [Bad](https://evil.test/file)\n\
                  - [Bad](./Source/A%5Cevil)\n\
                  - [Bad](./TopLevelOnly)\n";
        let entries = parse(md);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].name, "A (ANC Off)");
        assert!(entries[0].url.ends_with("A%20(ANC%20Off)/A%20(ANC%20Off)%20GraphicEQ.txt"), "got {}", entries[0].url);
        assert_eq!(entries[1].name, "Unicode + é");
        assert!(entries[1].url.contains("Unicode%20+%20%C3%A9"), "got {}", entries[1].url);
    }

    #[test]
    fn binary_cache_roundtrip_and_search() {
        let dir = std::env::temp_dir().join(format!("spatch-autoeq-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let md = "- [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650)\n\
                  - [Sony WH-1000XM4](./oratory1990/over-ear/Sony%20WH-1000XM4)\n";
        assert_eq!(load_or_store(&dir, Some(md)), 2);
        // Clear in-memory cache and reload from .bin
        CATALOG.lock().unwrap().clear();
        assert_eq!(load_or_store(&dir, None), 2);
        let hits = crate::cache::decode(&search_packed("hd 650", 10)).unwrap();
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0], "Sennheiser HD 650");
        assert!(hits[1].ends_with("Sennheiser%20HD%20650/Sennheiser%20HD%20650%20GraphicEQ.txt"));
        let _ = fs::remove_dir_all(&dir);
    }
}
