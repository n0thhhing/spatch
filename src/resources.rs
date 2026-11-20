//! Spatch's widget resources, added to Spotify's compiled resources table (resources.arsc): a layout and a widget
//! description, each a file entry in the APK plus an entry in the table. Nothing Spotify has is changed or moved:
//! each addition takes the next free entry index of its type (so the ids of everything else stay), appends its
//! name to the key strings and its file path to the value strings, and the sizes follow.
//!
//! The files themselves are compiled by aapt2 (`compile`), with Spotify's own ids for the views, so the layout
//! refers to ids the table already has.

use crate::Result;
use crate::tools::{self, Tools};
use std::ffi::OsStr;
use std::fs;
use std::path::Path;

const POOL: u16 = 0x0001;
const PACKAGE: u16 = 0x0200;
const TYPE: u16 = 0x0201;
const SPEC: u16 = 0x0202;
const UTF8: u32 = 0x100;
const NO_ENTRY: u32 = u32::MAX;

fn u16_at(b: &[u8], at: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(b.get(at..at + 2).ok_or("resources table is cut off")?.try_into()?))
}

fn u32_at(b: &[u8], at: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(b.get(at..at + 4).ok_or("resources table is cut off")?.try_into()?))
}

fn put32(b: &mut [u8], at: usize, v: u32) {
    b[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

/// A string as a pool stores it (length prefixes, content, terminator).
fn encode(text: &str, utf8: bool) -> Result<Vec<u8>> {
    let mut s = Vec::new();
    if utf8 {
        if text.len() >= 0x80 {
            return Err("pool string too long".into());
        }
        s.extend([text.chars().count() as u8, text.len() as u8]);
        s.extend(text.as_bytes());
        s.push(0);
    } else {
        let units: Vec<u16> = text.encode_utf16().collect();
        if units.len() >= 0x8000 {
            return Err("pool string too long".into());
        }
        s.extend((units.len() as u16).to_le_bytes());
        for u in units {
            s.extend(u.to_le_bytes());
        }
        s.extend([0, 0]);
    }
    Ok(s)
}

/// `pool` (one whole string pool chunk) with `strings` appended; every existing string keeps its index. Styles, if
/// there are any, are carried over untouched. Returns the new chunk and the first new string's index.
fn append(pool: &[u8], strings: &[&str]) -> Result<(Vec<u8>, u32)> {
    if u16_at(pool, 0)? != POOL {
        return Err("expected a string pool".into());
    }
    let size = u32_at(pool, 4)? as usize;
    let (count, styles, flags, start, styles_at) = (u32_at(pool, 8)? as usize, u32_at(pool, 12)? as usize, u32_at(pool, 16)?, u32_at(pool, 20)? as usize, u32_at(pool, 24)? as usize);
    if pool.len() != size {
        return Err("string pool size does not match its chunk".into());
    }
    let data_end = if styles > 0 { styles_at } else { size };
    let offsets = pool.get(28..28 + count * 4 + styles * 4).ok_or("string pool offsets are cut off")?;
    let data = pool.get(start..data_end).ok_or("string pool data is cut off")?;
    let mut fresh = Vec::new();
    let mut new_offsets = Vec::new();
    for s in strings {
        new_offsets.push((data.len() + fresh.len()) as u32);
        fresh.extend(encode(s, flags & UTF8 != 0)?);
    }
    while !fresh.len().is_multiple_of(4) {
        fresh.push(0);
    }
    let new_count = count + strings.len();
    let new_start = 28 + (new_count + styles) * 4;
    let new_styles_at = if styles > 0 { new_start + data.len() + fresh.len() } else { 0 };
    let mut out = Vec::with_capacity(size + strings.len() * 64);
    out.extend(POOL.to_le_bytes());
    out.extend(28u16.to_le_bytes());
    out.extend(0u32.to_le_bytes());
    out.extend((new_count as u32).to_le_bytes());
    out.extend((styles as u32).to_le_bytes());
    out.extend(flags.to_le_bytes());
    out.extend((new_start as u32).to_le_bytes());
    out.extend((new_styles_at as u32).to_le_bytes());
    out.extend(&offsets[..count * 4]);
    for o in new_offsets {
        out.extend(o.to_le_bytes());
    }
    out.extend(&offsets[count * 4..]); // style offsets
    out.extend(data);
    out.extend(fresh);
    if styles > 0 {
        out.extend(&pool[styles_at..]);
    }
    let total = out.len() as u32;
    put32(&mut out, 4, total);
    Ok((out, count as u32))
}

/// Where the first package's chunks are: (package offset, its end).
fn package(b: &[u8]) -> Result<(usize, usize)> {
    if u16_at(b, 0)? != 0x0002 || u32_at(b, 4)? as usize != b.len() {
        return Err("not a resources table".into());
    }
    let global = 12 + u32_at(b, 12 + 4)? as usize;
    if u16_at(b, 12)? != POOL || u16_at(b, global)? != PACKAGE {
        return Err("the resources table is not a string pool then a package".into());
    }
    Ok((global, global + u32_at(b, global + 4)? as usize))
}

/// The id the next resource added to type `type_id` will get (0x7f000000 | type << 16 | entry index).
pub fn next_id(b: &[u8], type_id: u8) -> Result<u32> {
    let (p, end) = package(b)?;
    let mut at = p + u16_at(b, p + 2)? as usize;
    while at < end {
        let size = u32_at(b, at + 4)? as usize;
        if u16_at(b, at)? == SPEC && b[at + 8] == type_id {
            return Ok(0x7f00_0000 | u32::from(type_id) << 16 | u32_at(b, at + 12)?);
        }
        at += size.max(8);
    }
    Err(format!("the resources table has no type {type_id:#x}").into())
}

/// Adds one resource of type `type_id` called `name` whose file is `path`; returns the table and the new id.
fn add_one(b: &[u8], type_id: u8, name: &str, path: &str) -> Result<(Vec<u8>, u32)> {
    let id = next_id(b, type_id)?;
    let index = id & 0xffff;
    let (p, end) = package(b)?;
    let header = u16_at(b, p + 2)? as usize;
    let types_at = p + u32_at(b, p + 268)? as usize;
    let keys_at = p + u32_at(b, p + 276)? as usize;
    let keys_end = keys_at + u32_at(b, keys_at + 4)? as usize;
    if types_at != p + header || keys_at != types_at + u32_at(b, types_at + 4)? as usize {
        return Err("unexpected package layout in the resources table".into());
    }
    let (global, path_index) = append(&b[12..p], &[path])?;
    let (keys, key_index) = append(&b[keys_at..keys_end], &[name])?;

    let mut chunks = Vec::new();
    let mut at = keys_end;
    let mut seen = 0;
    while at < end {
        let (kind, size) = (u16_at(b, at)?, u32_at(b, at + 4)? as usize);
        let chunk = b.get(at..at + size).ok_or("a resource chunk is cut off")?;
        if kind == SPEC && chunk[8] == type_id {
            let n = u32_at(chunk, 12)?;
            let mut c = chunk.to_vec();
            put32(&mut c, 12, n + 1);
            c.extend(0u32.to_le_bytes());
            let total = c.len() as u32;
            put32(&mut c, 4, total);
            chunks.extend(c);
            seen += 1;
        } else if kind == TYPE && chunk[8] == type_id {
            let (head, n, start) = (u16_at(chunk, 2)? as usize, u32_at(chunk, 12)?, u32_at(chunk, 16)? as usize);
            if chunk[9] != 0 || n != index {
                return Err("this resources table's types are not in the plain form".into());
            }
            let default = chunk[24..head].iter().all(|&byte| byte == 0);
            let mut c = chunk[..head].to_vec();
            put32(&mut c, 12, n + 1);
            put32(&mut c, 16, (start + 4) as u32);
            c.extend(&chunk[head..start]); // the offsets so far
            c.extend((if default { (size - start) as u32 } else { NO_ENTRY }).to_le_bytes());
            c.extend(&chunk[start..]);
            if default {
                c.extend([8, 0, 0, 0]); // entry: size 8, flags 0
                c.extend(key_index.to_le_bytes());
                c.extend([8, 0, 0, 0x03]); // value: size 8, string
                c.extend(path_index.to_le_bytes());
            }
            let total = c.len() as u32;
            put32(&mut c, 4, total);
            chunks.extend(c);
        } else {
            chunks.extend(chunk);
        }
        at += size;
    }
    if seen != 1 {
        return Err(format!("type {type_id:#x} has {seen} type specs").into());
    }

    let mut package_chunk = b[p..types_at].to_vec();
    let mut body = Vec::new();
    body.extend(&b[types_at..keys_at]);
    body.extend(&keys);
    body.extend(chunks);
    let package_size = (package_chunk.len() + body.len()) as u32;
    put32(&mut package_chunk, 4, package_size);
    let mut out = b[..12].to_vec();
    out.extend(global);
    out.extend(package_chunk);
    out.extend(body);
    let total = out.len() as u32;
    put32(&mut out, 4, total);
    Ok((out, id))
}

/// `table` with each `(type id, name, file path)` added in order; returns it with the new ids.
pub fn add(table: &[u8], items: &[(u8, &str, &str)]) -> Result<(Vec<u8>, Vec<u32>)> {
    let (mut table, mut ids) = (table.to_vec(), Vec::new());
    for &(type_id, name, path) in items {
        let (next, id) = add_one(&table, type_id, name, path)?;
        table = next;
        ids.push(id);
    }
    Ok((table, ids))
}

/// Compiles XML resources with aapt2. `files` are `(path in the APK, source text)`; `ids` are `(type/name, id)` pairs
/// every name in the sources must be listed in (so no id is invented that could clash with Spotify's).
/// Returns each file as the compiled XML the APK holds, in order.
pub fn compile(tools: &Tools, dir: &Path, files: &[(&str, &str)], ids: &[(String, u32)]) -> Result<Vec<Vec<u8>>> {
    let aapt2 = tools::sdk_tool("aapt2")?;
    let android = tools::android_jar().ok_or("missing android.jar: install Android SDK platform 35 or later")?;
    let res = dir.join("res");
    for (path, text) in files {
        let target = dir.join(path);
        fs::create_dir_all(target.parent().unwrap())?;
        fs::write(target, text)?;
    }
    let manifest = dir.join("AndroidManifest.xml");
    fs::write(&manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.spotify.music\"/>")?;
    let stable = dir.join("ids.txt");
    fs::write(&stable, ids.iter().map(|(name, id)| format!("com.spotify.music:{name} = {id:#010x}\n")).collect::<String>())?;
    let (compiled, linked) = (dir.join("compiled.zip"), dir.join("linked.apk"));
    tools.run(&aapt2, &[OsStr::new("compile"), OsStr::new("--dir"), res.as_os_str(), OsStr::new("-o"), compiled.as_os_str()])?;
    tools.run(
        &aapt2,
        &[
            OsStr::new("link"),
            OsStr::new("-o"),
            linked.as_os_str(),
            OsStr::new("-I"),
            android.as_os_str(),
            OsStr::new("--manifest"),
            manifest.as_os_str(),
            OsStr::new("--stable-ids"),
            stable.as_os_str(),
            OsStr::new("--no-auto-version"),
            OsStr::new("--min-sdk-version"),
            OsStr::new("24"),
            OsStr::new("--target-sdk-version"),
            OsStr::new("35"),
            compiled.as_os_str(),
        ],
    )?;
    files.iter().map(|(path, _)| crate::input::read_entry(&linked, path)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pool(strings: &[&str], styles: usize) -> Vec<u8> {
        let mut data = Vec::new();
        let mut offsets = Vec::new();
        for s in strings {
            offsets.push(data.len() as u32);
            data.extend(encode(s, true).unwrap());
        }
        while data.len() % 4 != 0 {
            data.push(0);
        }
        let start = 28 + (strings.len() + styles) * 4;
        let mut out = Vec::new();
        out.extend(POOL.to_le_bytes());
        out.extend(28u16.to_le_bytes());
        out.extend(0u32.to_le_bytes());
        out.extend((strings.len() as u32).to_le_bytes());
        out.extend((styles as u32).to_le_bytes());
        out.extend(UTF8.to_le_bytes());
        out.extend((start as u32).to_le_bytes());
        out.extend((if styles > 0 { start + data.len() } else { 0 } as u32).to_le_bytes());
        for o in offsets {
            out.extend(o.to_le_bytes());
        }
        for _ in 0..styles {
            out.extend(0u32.to_le_bytes());
        }
        out.extend(data);
        if styles > 0 {
            out.extend([0xff; 8]); // the style list's end marker
        }
        let total = out.len() as u32;
        put32(&mut out, 4, total);
        out
    }

    /// One package with one type (id 3) of two entries, in a default and a night configuration.
    fn table() -> Vec<u8> {
        let globals = pool(&["res/layout/a.xml", "res/layout/b.xml"], 1);
        let types = pool(&["layout"], 0);
        let keys = pool(&["a", "b"], 0);
        let mut spec = vec![0x02, 0x02, 16, 0, 0, 0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0];
        spec.extend([0u8; 8]);
        put32(&mut spec, 4, 24);
        let ty = |night: bool| {
            let mut t = vec![0x01, 0x02, 28, 0, 0, 0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0, 36, 0, 0, 0, 8, 0, 0, 0];
            t.extend([0, 0, 0, 0]); // config bytes after its size: all zero is the default
            if night {
                *t.last_mut().unwrap() = 1;
            }
            let entry = |key: u32, value: u32| [[8u8, 0, 0, 0].as_slice(), &key.to_le_bytes(), &[8, 0, 0, 3], &value.to_le_bytes()].concat();
            if night {
                t.extend([NO_ENTRY.to_le_bytes(), NO_ENTRY.to_le_bytes()].concat());
            } else {
                t.extend([0u32.to_le_bytes(), 16u32.to_le_bytes()].concat());
                t.extend(entry(0, 0));
                t.extend(entry(1, 1));
            }
            let total = t.len() as u32;
            put32(&mut t, 4, total);
            t
        };
        let mut package = vec![0u8; 288];
        package[0..2].copy_from_slice(&PACKAGE.to_le_bytes());
        package[2..4].copy_from_slice(&288u16.to_le_bytes());
        put32(&mut package, 8, 0x7f);
        put32(&mut package, 268, 288);
        put32(&mut package, 276, 288 + types.len() as u32);
        for chunk in [&types, &keys, &spec, &ty(false), &ty(true)] {
            package.extend(chunk.iter());
        }
        let total = package.len() as u32;
        put32(&mut package, 4, total);
        let mut table = vec![2, 0, 12, 0, 0, 0, 0, 0, 1, 0, 0, 0];
        table.extend(globals);
        table.extend(package);
        let total = table.len() as u32;
        put32(&mut table, 4, total);
        table
    }

    #[test]
    fn a_resource_takes_the_next_entry_and_leaves_the_rest_alone() {
        let before = table();
        assert_eq!(next_id(&before, 3).unwrap(), 0x7f03_0002);
        let (after, ids) = add(&before, &[(3, "c", "res/layout/c.xml"), (3, "d", "res/layout/d.xml")]).unwrap();
        assert_eq!(ids, vec![0x7f03_0002, 0x7f03_0003]);
        assert_eq!(u32_at(&after, 4).unwrap() as usize, after.len());
        assert_eq!(next_id(&after, 3).unwrap(), 0x7f03_0004);
        let (p, end) = package(&after).unwrap();
        assert_eq!(u32_at(&after, p + 4).unwrap() as usize + p, end);
        assert_eq!(end, after.len());
        // The global pool keeps its style and gains the two paths after the old two.
        let (_, first) = append(&after[12..p], &[]).unwrap();
        assert_eq!(first, 4);
        assert_eq!(u32_at(&after, 12 + 12).unwrap(), 1);
        assert!(next_id(&before, 9).is_err());
        assert!(add(&before[..100], &[(3, "c", "x")]).is_err());
    }
}
