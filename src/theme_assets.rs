//! Resource-loader palettes: retain only changed literal colours and our injected XML entries.
use crate::{
    Result,
    patch::TARGETS,
    theme::{THEMES, Theme},
};
use std::{
    collections::HashSet,
    io::{Cursor, Write},
};
use zip::{ZipWriter, write::SimpleFileOptions};

pub const PATHS: [&str; 1] = ["assets/spatch-themes/lavender.apk"];
fn u16at(b: &[u8], p: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(
        b.get(p..p + 2).ok_or("short resource table")?.try_into()?,
    ))
}
fn u32at(b: &[u8], p: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(
        b.get(p..p + 4).ok_or("short resource table")?.try_into()?,
    ))
}
fn put(b: &mut [u8], p: usize, v: u32) {
    b[p..p + 4].copy_from_slice(&v.to_le_bytes());
}
fn chunk(b: &[u8], p: usize, limit: usize) -> Result<(u16, usize, usize)> {
    let h = u16at(b, p + 2)? as usize;
    let n = u32at(b, p + 4)? as usize;
    if h < 8 || n < h || p.checked_add(n).is_none_or(|end| end > limit) {
        return Err("invalid resource chunk".into());
    }
    Ok((u16at(b, p)?, h, n))
}
fn len8(b: &[u8], p: &mut usize) -> Result<usize> {
    let a = *b.get(*p).ok_or("short string")?;
    *p += 1;
    if a & 128 == 0 {
        Ok(a as usize)
    } else {
        let c = *b.get(*p).ok_or("short string")?;
        *p += 1;
        Ok(((a as usize & 127) << 8) | c as usize)
    }
}
fn strings(b: &[u8], p: usize) -> Result<Vec<String>> {
    if u32at(b, p + 16)? & 0x100 == 0 {
        return Err("expected UTF-8 resource strings".into());
    }
    let mut out = Vec::new();
    let h = u16at(b, p + 2)? as usize;
    let start = u32at(b, p + 20)? as usize;
    for i in 0..u32at(b, p + 8)? as usize {
        let mut q = p + start + u32at(b, p + h + i * 4)? as usize;
        len8(b, &mut q)?;
        let n = len8(b, &mut q)?;
        out.push(String::from_utf8_lossy(b.get(q..q + n).ok_or("short string data")?).into_owned());
    }
    Ok(out)
}
fn map(v: u32, from: &Theme, to: &Theme) -> Option<u32> {
    from.rgb
        .iter()
        .position(|c| *c == v & 0xffffff)
        .map(|i| (v & 0xff000000) | to.rgb[i])
        // Retain native ink and inverse surfaces so runtime light palettes can map them too.
        .or_else(|| matches!(v & 0xffffff, 0 | 0xffffff).then_some(v))
}

pub fn table(b: &[u8], from: &Theme, to: &Theme, files: &HashSet<&str>) -> Result<Vec<u8>> {
    let (kind, head, size) = chunk(b, 0, b.len())?;
    if kind != 2 || size != b.len() {
        return Err("not a resource table".into());
    }
    let mut names = Vec::new();
    let mut out = b.to_vec();
    let mut seen = HashSet::new();
    let mut colours = 0;
    let mut p = head;
    while p < size {
        let (kind, h, n) = chunk(b, p, size)?;
        if kind == 1 {
            names = strings(b, p)?;
        }
        if kind == 0x200 {
            let mut q = p + h;
            while q < p + n {
                let (ty, th, tn) = chunk(b, q, p + n)?;
                if ty == 0x201 {
                    if b[q + 9] != 0 {
                        return Err("unsupported sparse/compact resource offsets".into());
                    }
                    let count = u32at(b, q + 12)? as usize;
                    let start = u32at(b, q + 16)? as usize;
                    if th + count * 4 > start || start > tn {
                        return Err("invalid resource entry offsets".into());
                    }
                    for i in 0..count {
                        let at = q + th + 4 * i;
                        let off = u32at(b, at)?;
                        if off == u32::MAX {
                            continue;
                        }
                        let entry = q + start + off as usize;
                        let eh = u16at(b, entry)? as usize;
                        let flags = u16at(b, entry + 2)?;
                        let mut keep = false;
                        if flags & 1 == 0 {
                            let v = entry + eh;
                            if v + 8 > q + tn || u16at(b, v)? != 8 {
                                return Err("invalid simple resource value".into());
                            }
                            let data = u32at(b, v + 4)?;
                            if matches!(b[v + 3], 0x1c | 0x1d)
                                && let Some(mapped) = map(data, from, to)
                            {
                                put(&mut out, v + 4, mapped);
                                keep = true;
                                colours += 1;
                            }
                            if b[v + 3] == 3 {
                                let name =
                                    names.get(data as usize).ok_or("invalid resource string")?;
                                if files.contains(name.as_str()) {
                                    keep = true;
                                    seen.insert(name.clone());
                                }
                            }
                        }
                        if flags & 1 != 0 {
                            let count = u32at(b, entry + 12)? as usize;
                            if entry + eh + count * 12 > q + tn {
                                return Err("invalid resource bag".into());
                            }
                            for j in 0..count {
                                let v = entry + eh + j * 12 + 4;
                                if matches!(b[v + 3], 0x1c | 0x1d)
                                    && let Some(mapped) = map(u32at(b, v + 4)?, from, to)
                                {
                                    put(&mut out, v + 4, mapped);
                                    keep = true;
                                    colours += 1;
                                }
                            }
                        }
                        if !keep {
                            put(&mut out, at, u32::MAX);
                        }
                    }
                }
                q += tn;
            }
        }
        p += n;
    }
    if colours
        < crate::arsc::COLOR_SWAPS
            .iter()
            .map(|(_, _, n)| n)
            .sum::<usize>()
        || !files.iter().all(|f| seen.contains(*f))
    {
        return Err(format!(
            "theme overlay incomplete: {colours} colours, {} of {} XML entries",
            seen.len(),
            files.len()
        )
        .into());
    }
    Ok(out)
}

fn xml(b: &[u8], from: &Theme, to: &Theme) -> Vec<u8> {
    let mut out = b.to_vec();
    for p in 0..b.len().saturating_sub(7) {
        if b[p..p + 3] == [8, 0, 0] && matches!(b[p + 3], 0x1c | 0x1d) {
            let value = u32::from_le_bytes(b[p + 4..p + 8].try_into().unwrap());
            if let Some(v) = map(value, from, to) {
                put(&mut out, p + 4, v);
            }
        }
    }
    out
}

pub fn build(
    base: &std::path::Path,
    compiled: &[(&str, Vec<u8>)],
    from: &Theme,
) -> Result<Vec<(&'static str, Vec<u8>)>> {
    let arsc = &compiled
        .iter()
        .find(|(n, _)| *n == "resources.arsc")
        .ok_or("theme overlay needs compiled resource table")?
        .1;
    let files: HashSet<_> = TARGETS
        .iter()
        .map(|t| t.entry)
        .chain(crate::lottie::FILES)
        .collect();
    let mut overlays = Vec::new();
    let manifest = crate::input::read_entry(base, "AndroidManifest.xml")?;
    for (theme, path) in THEMES[..1].iter().zip(PATHS) {
        let mut z = ZipWriter::new(Cursor::new(Vec::new()));
        z.start_file(
            "resources.arsc",
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored),
        )?;
        z.write_all(&table(arsc, from, theme, &files)?)?;
        z.start_file(
            "AndroidManifest.xml",
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated),
        )?;
        z.write_all(&manifest)?;
        for (name, bytes) in compiled
            .iter()
            .filter(|(n, _)| TARGETS.iter().any(|t| t.entry == *n))
        {
            z.start_file(
                *name,
                SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated),
            )?;
            z.write_all(&xml(bytes, from, theme))?;
        }
        for (name, bytes) in crate::lottie::patch_all(base, theme)? {
            z.start_file(
                name,
                SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated),
            )?;
            z.write_all(&bytes)?;
        }
        overlays.push((path, z.finish()?.into_inner()));
    }
    Ok(overlays)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn colour_mapping_preserves_alpha_and_semantics() {
        let from = &THEMES[1];
        let to = &THEMES[4];
        assert_eq!(map(0x80fe8019, from, to), Some(0x8088c0d0));
        assert_eq!(map(0xff123456, from, to), None);
    }
    #[test]
    fn rejects_truncated_or_foreign_tables() {
        for b in [&[][..], &[0; 8][..]] {
            assert!(table(b, &THEMES[0], &THEMES[1], &HashSet::new()).is_err());
        }
    }
}
