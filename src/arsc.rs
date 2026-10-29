//! In-place colour swaps in base `resources.arsc`, mirroring the Compose palette edits in `dex`.
//!
//! A colour is an 8-byte `Res_value` {size=8, res0=0, dataType, data}; only literal ARGB8 (0x1c) and
//! RGB8 (0x1d) values are touched. Same size in, same size out, so no offsets or chunks move.

use crate::Result;
use crate::theme::Theme;

/// (original ARGB, role token ARGB mapped through the theme, expected occurrences across both data types).
pub const COLOR_SWAPS: [(u32, u32, usize); 7] = [
    (0xff121212, 0xff14121c, 12), // gray_7 / dark_base_background_base: page backgrounds
    (0xff1f1f1f, 0xff1d1a2a, 6),  // elevated base
    (0xff2a2a2a, 0xff2b2640, 6),  // elevated highlight
    (0xffb3b3b3, 0xffbcb3dc, 3),  // subdued text
    (0xff1ed760, 0xffd0bcff, 28), // Spotify green accent (also positive/"saved" states)
    (0xff1db954, 0xffd0bcff, 1),  // older brand green
    (0xff3be477, 0xffe0d2ff, 4),  // accent highlight
];

fn value(data_type: u8, argb: u32) -> [u8; 8] {
    let mut v = [8, 0, 0, data_type, 0, 0, 0, 0];
    v[4..].copy_from_slice(&argb.to_le_bytes());
    v
}

/// Returns a copy of `arsc` with every swap applied, failing if any count differs from the pinned one.
pub fn swap_colors(arsc: &[u8], theme: &Theme) -> Result<Vec<u8>> {
    let mut out = arsc.to_vec();
    for (from, to, expected) in COLOR_SWAPS {
        let mut found = 0;
        for data_type in [0x1c, 0x1d] {
            let (old, new) = (value(data_type, from), value(data_type, theme.map(to)));
            let mut i = 0;
            while let Some(pos) = out[i..].windows(8).position(|w| w == old) {
                out[i + pos..i + pos + 8].copy_from_slice(&new);
                i += pos + 8;
                found += 1;
            }
        }
        if found != expected {
            return Err(format!(
                "resources.arsc: colour {from:#010x} found {found} times, expected {expected}"
            )
            .into());
        }
    }
    Ok(out)
}

/// A new file-based resource (layout, drawable) appended to the table: its compiled XML ships as
/// `path` and gets the next free entry index of its type, in the given order.
pub struct NewFile {
    pub type_name: &'static str,
    pub name: &'static str,
    pub path: &'static str,
}

fn u16_at(d: &[u8], o: usize) -> usize {
    u16::from_le_bytes([d[o], d[o + 1]]) as usize
}

fn u32_at(d: &[u8], o: usize) -> usize {
    u32::from_le_bytes(d[o..o + 4].try_into().unwrap()) as usize
}

fn put_u32(d: &mut [u8], o: usize, v: usize) {
    d[o..o + 4].copy_from_slice(&(v as u32).to_le_bytes());
}

const POOL_UTF8: usize = 0x100;
const POOL_SORTED: usize = 0x1;

/// UTF-8 length prefix: one byte below 0x80, else two (high bit set).
fn utf8_len(out: &mut Vec<u8>, n: usize) {
    if n < 0x80 {
        out.push(n as u8);
    } else {
        out.push(0x80 | (n >> 8) as u8);
        out.push(n as u8);
    }
}

/// Appends strings to a ResStringPool chunk; existing strings, styles and indices are unchanged.
fn pool_append(pool: &[u8], strings: &[&str]) -> Result<(Vec<u8>, usize)> {
    let (hs, size, count, styles, flags) = (
        u16_at(pool, 2),
        u32_at(pool, 4),
        u32_at(pool, 8),
        u32_at(pool, 12),
        u32_at(pool, 16),
    );
    let (strings_start, styles_start) = (u32_at(pool, 20), u32_at(pool, 24));
    if flags & POOL_UTF8 == 0 || flags & POOL_SORTED != 0 || hs != 28 {
        return Err("string pool is not an unsorted UTF-8 pool; cannot append".into());
    }
    let strings_end = if styles > 0 { styles_start } else { size };
    let old_data = &pool[strings_start..strings_end];
    let mut data = old_data.to_vec();
    let mut offsets: Vec<u8> = pool[hs..hs + 4 * count].to_vec();
    for s in strings {
        offsets.extend_from_slice(&(data.len() as u32).to_le_bytes());
        utf8_len(&mut data, s.chars().count());
        utf8_len(&mut data, s.len());
        data.extend_from_slice(s.as_bytes());
        data.push(0);
    }
    while !data.len().is_multiple_of(4) {
        data.push(0);
    }
    let style_offsets = &pool[hs + 4 * count..hs + 4 * count + 4 * styles];
    let new_strings_start = hs + offsets.len() + style_offsets.len();
    let new_styles_start = if styles > 0 {
        new_strings_start + data.len()
    } else {
        0
    };
    let mut out = pool[..hs].to_vec();
    out.extend_from_slice(&offsets);
    out.extend_from_slice(style_offsets);
    out.extend_from_slice(&data);
    if styles > 0 {
        out.extend_from_slice(&pool[styles_start..size]);
    }
    let len = out.len();
    put_u32(&mut out, 4, len);
    put_u32(&mut out, 8, count + strings.len());
    put_u32(&mut out, 20, new_strings_start);
    put_u32(&mut out, 24, new_styles_start);
    Ok((out, count))
}

/// Reads a UTF-16 pool's strings (the package type-name pool).
fn pool_utf16(pool: &[u8]) -> Vec<String> {
    let (hs, count, start) = (u16_at(pool, 2), u32_at(pool, 8), u32_at(pool, 20));
    (0..count)
        .map(|i| {
            let p = start + u32_at(pool, hs + 4 * i);
            let n = u16_at(pool, p);
            let units: Vec<u16> = (0..n).map(|k| u16_at(pool, p + 2 + 2 * k) as u16).collect();
            String::from_utf16_lossy(&units)
        })
        .collect()
}

/// Appends `files` to the package: global strings for their paths, key names, one more typeSpec
/// flag each and one entry each in the default-configuration type chunk. Every existing ID, value
/// and string index is unchanged; returns the new table and the IDs assigned (in `files` order).
pub fn append_files(arsc: &[u8], files: &[NewFile]) -> Result<(Vec<u8>, Vec<u32>)> {
    if u16_at(arsc, 0) != 0x0002 || u32_at(arsc, 8) != 1 {
        return Err("resources.arsc: expected a table with one package".into());
    }
    let pool_at = u16_at(arsc, 2);
    let pool_size = u32_at(arsc, pool_at + 4);
    let paths: Vec<&str> = files.iter().map(|f| f.path).collect();
    let (pool, first_path) = pool_append(&arsc[pool_at..pool_at + pool_size], &paths)?;

    let pkg = pool_at + pool_size;
    let (pkg_hs, pkg_size, pkg_id) = (
        u16_at(arsc, pkg + 2),
        u32_at(arsc, pkg + 4),
        u32_at(arsc, pkg + 8),
    );
    let (type_at, key_at) = (u32_at(arsc, pkg + 268), u32_at(arsc, pkg + 276));
    if pkg_id != 0x7f || type_at != pkg_hs || key_at <= type_at {
        return Err("resources.arsc: unexpected package layout".into());
    }
    let type_pool = &arsc[pkg + type_at..pkg + key_at];
    let types = pool_utf16(type_pool);
    let key_size = u32_at(arsc, pkg + key_at + 4);
    let names: Vec<&str> = files.iter().map(|f| f.name).collect();
    let (keys, first_key) = pool_append(&arsc[pkg + key_at..pkg + key_at + key_size], &names)?;

    let type_id = |name: &str| -> Result<u8> {
        let i = types
            .iter()
            .position(|t| t == name)
            .ok_or(format!("no resource type {name}"))?;
        Ok((i + 1) as u8)
    };
    let mut ids = vec![0u32; files.len()];
    let mut chunks = Vec::new();
    let mut o = pkg + key_at + key_size;
    let (mut specs_done, mut types_done) = (0, 0);
    while o < pkg + pkg_size {
        let (kind, hs, size) = (u16_at(arsc, o), u16_at(arsc, o + 2), u32_at(arsc, o + 4));
        let mut chunk = arsc[o..o + size].to_vec();
        let tid = arsc[o + 8];
        let mine: Vec<usize> = (0..files.len())
            .filter(|&i| type_id(files[i].type_name).ok() == Some(tid))
            .collect();
        if kind == 0x0202 && !mine.is_empty() {
            let count = u32_at(&chunk, 12);
            for (k, &i) in mine.iter().enumerate() {
                ids[i] = (0x7f << 24) | ((tid as u32) << 16) | (count + k) as u32;
            }
            chunk.extend(std::iter::repeat_n(0u8, 4 * mine.len()));
            put_u32(&mut chunk, 12, count + mine.len());
            let len = chunk.len();
            put_u32(&mut chunk, 4, len); // chunk size grew with the flags
            specs_done += mine.len();
        } else if kind == 0x0201 && !mine.is_empty() {
            let cfg = u32_at(&chunk, 20);
            let default = chunk[24..20 + cfg].iter().all(|&b| b == 0);
            if default {
                if chunk[9] != 0 {
                    return Err("resources.arsc: sparse or 16-bit type chunk; cannot append".into());
                }
                let (count, start) = (u32_at(&chunk, 12), u32_at(&chunk, 16));
                if start != hs + 4 * count {
                    return Err("resources.arsc: unexpected type chunk layout".into());
                }
                let mut out = chunk[..start].to_vec();
                let mut entries = chunk[start..].to_vec();
                for &i in &mine {
                    out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
                    entries.extend_from_slice(&8u16.to_le_bytes()); // ResTable_entry.size
                    entries.extend_from_slice(&0u16.to_le_bytes()); // flags
                    entries.extend_from_slice(&((first_key + i) as u32).to_le_bytes());
                    entries.extend_from_slice(&[8, 0, 0, 0x03]); // Res_value: string
                    entries.extend_from_slice(&((first_path + i) as u32).to_le_bytes());
                }
                let new_start = out.len();
                out.extend_from_slice(&entries);
                let len = out.len();
                put_u32(&mut out, 4, len);
                put_u32(&mut out, 12, count + mine.len());
                put_u32(&mut out, 16, new_start);
                chunk = out;
                types_done += mine.len();
            }
        }
        chunks.extend_from_slice(&chunk);
        o += size;
    }
    if specs_done != files.len() || types_done != files.len() {
        return Err("resources.arsc: could not place every new resource".into());
    }
    let mut package = arsc[pkg..pkg + key_at].to_vec();
    package.extend_from_slice(&keys);
    package.extend_from_slice(&chunks);
    let plen = package.len();
    put_u32(&mut package, 4, plen);
    let mut out = arsc[..pool_at].to_vec();
    out.extend_from_slice(&pool);
    out.extend_from_slice(&package);
    let len = out.len();
    put_u32(&mut out, 4, len);
    Ok((out, ids))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Reads string i of a UTF-8 pool.
    fn pool_string(pool: &[u8], i: usize) -> String {
        let (hs, start) = (u16_at(pool, 2), u32_at(pool, 20));
        let mut p = start + u32_at(pool, hs + 4 * i);
        p += if pool[p] & 0x80 != 0 { 2 } else { 1 };
        let n = if pool[p] & 0x80 != 0 {
            p += 2;
            ((pool[p - 2] as usize & 0x7f) << 8) | pool[p - 1] as usize
        } else {
            p += 1;
            pool[p - 1] as usize
        };
        String::from_utf8(pool[p..p + n].to_vec()).unwrap()
    }

    #[test]
    fn pool_append_keeps_old_strings_and_styles() {
        // Unsorted UTF-8 pool: two strings, one style span list ("b" -> [0xFFFFFFFF end]).
        let mut data = Vec::new();
        for s in ["a", "bee"] {
            utf8_len(&mut data, s.len());
            utf8_len(&mut data, s.len());
            data.extend_from_slice(s.as_bytes());
            data.push(0);
        }
        while !data.len().is_multiple_of(4) {
            data.push(0);
        }
        let style = [0xffu8; 4];
        let strings_start = 28 + 8 + 4;
        let mut pool = vec![0u8; 28];
        pool[0] = 1;
        pool[2] = 28;
        put_u32(&mut pool, 8, 2);
        put_u32(&mut pool, 12, 1);
        put_u32(&mut pool, 16, POOL_UTF8);
        put_u32(&mut pool, 20, strings_start);
        put_u32(&mut pool, 24, strings_start + data.len());
        pool.extend_from_slice(&0u32.to_le_bytes());
        pool.extend_from_slice(&4u32.to_le_bytes()); // "a" = 2 length bytes + 1 + NUL
        pool.extend_from_slice(&0u32.to_le_bytes()); // style offset
        pool.extend_from_slice(&data);
        pool.extend_from_slice(&style);
        let len = pool.len();
        put_u32(&mut pool, 4, len);

        let long = "x".repeat(200); // two-byte length prefix
        let (out, first) = pool_append(&pool, &["res/layout/new.xml", &long]).unwrap();
        assert_eq!(first, 2);
        assert_eq!(u32_at(&out, 4), out.len());
        assert_eq!(out.len() % 4, 0);
        assert_eq!(pool_string(&out, 0), "a");
        assert_eq!(pool_string(&out, 1), "bee");
        assert_eq!(pool_string(&out, 2), "res/layout/new.xml");
        assert_eq!(pool_string(&out, 3), long);
        assert_eq!(&out[u32_at(&out, 24)..], &style); // styles moved intact
        // Sorted pools cannot take appended strings.
        put_u32(&mut pool, 16, POOL_UTF8 | POOL_SORTED);
        assert!(pool_append(&pool, &["x"]).is_err());
    }

    #[test]
    #[ignore = "needs the supplied APKM; run with --include-ignored"]
    fn append_files_on_supplied_table() {
        let apkm = std::fs::read_dir(env!("CARGO_MANIFEST_DIR"))
            .unwrap()
            .map(|e| e.unwrap().path())
            .find(|p| p.extension().is_some_and(|x| x == "apkm"))
            .expect("supplied .apkm in the project root");
        let dir = std::env::temp_dir().join(format!("spatch-arsc-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        crate::input::extract_apks(&apkm, &dir).unwrap();
        let arsc = crate::input::read_entry(&dir.join("base.apk"), "resources.arsc").unwrap();
        let files = [
            NewFile {
                type_name: "layout",
                name: "spatch_test",
                path: "res/layout/spatch_test.xml",
            },
            NewFile {
                type_name: "drawable",
                name: "spatch_test_a",
                path: "res/drawable/spatch_test_a.xml",
            },
            NewFile {
                type_name: "drawable",
                name: "spatch_test_b",
                path: "res/drawable/spatch_test_b.xml",
            },
        ];
        let (out, ids) = append_files(&arsc, &files).unwrap();
        assert_eq!(ids, vec![0x7f0e0971, 0x7f080cf9, 0x7f080cfa]);
        assert_eq!(u32_at(&out, 4), out.len());
        // Every chunk must still nest cleanly (the theme overlays re-parse the table).
        let lavender = crate::theme::by_name("lavender").unwrap();
        // (It then rejects this test's empty file list, which is fine: only structure is checked.)
        if let Err(e) =
            crate::theme_assets::table(&out, lavender, lavender, &std::collections::HashSet::new())
        {
            let e = e.to_string();
            assert!(e.contains("incomplete"), "{e}");
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn theme_colours_never_match_a_later_search() {
        // Swaps run in order; a themed value equal to an original colour would be swapped again.
        for t in &crate::theme::THEMES {
            for (from, _, _) in COLOR_SWAPS {
                assert!(!t.rgb.contains(&(from & 0xffffff)), "{} {from:#x}", t.name);
            }
        }
    }

    #[test]
    fn swaps_exact_counts_in_place() {
        let mut arsc = Vec::new();
        for (from, _, n) in COLOR_SWAPS {
            for k in 0..n {
                arsc.extend_from_slice(&value(if k % 2 == 0 { 0x1c } else { 0x1d }, from));
            }
        }
        let g = crate::theme::by_name("gruvbox").unwrap();
        let out = swap_colors(&arsc, g).unwrap();
        assert_eq!(out.len(), arsc.len());
        assert_eq!(&out[..8], &value(0x1c, 0xff282828));
        // A missing occurrence (other build or already patched) is rejected.
        assert!(swap_colors(&arsc[8..], g).is_err());
        assert!(swap_colors(&out, g).is_err());
    }
}
