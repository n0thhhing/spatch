//! Reuse validated build stages as patch bundles, never as copies of the original APK.
use crate::{Result, bundle, input};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::{self, File},
    io::Read,
    path::{Path, PathBuf},
};

pub fn path(kind: &str, sources: &[&[u8]]) -> Option<PathBuf> {
    let root = std::env::var_os("XDG_CACHE_HOME")
        .filter(|p| !p.is_empty())
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join(".cache")))?;
    Some(
        root.join("spatch/build-v1")
            .join(format!("{kind}-{}.zip", fingerprint(sources))),
    )
}

fn fingerprint(sources: &[&[u8]]) -> String {
    let mut hash = Sha256::new();
    for source in [input::APKM_SHA256.as_bytes(), include_bytes!("cache.rs")]
        .into_iter()
        .chain(sources.iter().copied())
    {
        hash.update((source.len() as u64).to_le_bytes());
        hash.update(source);
    }
    format!("{:x}", hash.finalize())
}

pub fn load(path: &Path, base: &Path, names: &[&str]) -> Result<Vec<Vec<u8>>> {
    let mut zip = zip::ZipArchive::new(File::open(path)?)?;
    let manifest = String::from_utf8(read(&mut zip, "bundle.txt")?)?;
    if !manifest.starts_with("spatch-bundle 1\n")
        || !manifest
            .lines()
            .any(|l| l == format!("apkm {}", input::APKM_SHA256))
    {
        return Err("build cache is for a different input or format".into());
    }
    let mut steps = HashMap::new();
    for line in manifest.lines() {
        let fields: Vec<_> = line.split_whitespace().collect();
        if matches!(fields.first(), Some(&"replace") | Some(&"add"))
            && (fields.len() != 5 || steps.insert(fields[1], fields).is_some())
        {
            return Err("invalid or duplicate build cache step".into());
        }
    }
    if steps.len() != names.len() {
        return Err("build cache inventory differs".into());
    }
    let mut source = zip::ZipArchive::new(File::open(base)?)?;
    let basis = read(&mut source, "resources.arsc")?;
    let mut entries = Vec::new();
    for name in names {
        let step = steps.get(name).ok_or("missing build cache entry")?;
        let old = if step[0] == "replace" {
            read(&mut source, name)?
        } else {
            Vec::new()
        };
        if step[0] == "replace" && input::sha256_bytes(&old) != step[2] {
            return Err("build cache original hash differs".into());
        }
        let patch = read(&mut zip, step[4])?;
        let mut bytes = Vec::new();
        bsdiff::patch(
            if step[0] == "replace" { &old } else { &basis },
            &mut patch.as_slice(),
            &mut bytes,
        )?;
        if input::sha256_bytes(&bytes) != step[3] {
            return Err("build cache result hash differs".into());
        }
        entries.push(bytes);
    }
    Ok(entries)
}

fn read(zip: &mut zip::ZipArchive<File>, name: &str) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    zip.by_name(name)?.read_to_end(&mut bytes)?;
    Ok(bytes)
}

/// The old cache remains intact until a complete new bundle can be renamed over it.
pub fn save(
    path: &Path,
    base: &Path,
    replace: &[(&str, Vec<u8>)],
    add: &[(&str, Vec<u8>)],
) -> Result<()> {
    fs::create_dir_all(path.parent().ok_or("cache path has no parent")?)?;
    let temp = path.with_extension(format!("{}.tmp", std::process::id()));
    let result = bundle::write(
        base.parent().ok_or("base has no parent")?,
        replace,
        add,
        "",
        &temp,
        zip::CompressionMethod::Stored, // the CI cache already compresses these; avoid compressing twice
    )
    .and_then(|_| {
        fs::rename(&temp, path)?;
        Ok(())
    });
    let _ = fs::remove_file(temp);
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use input::tests::{temp_dir, write_zip};

    #[test]
    fn fingerprints_cover_every_source_and_its_boundaries() {
        assert_ne!(fingerprint(&[b"ab", b"c"]), fingerprint(&[b"a", b"bc"]));
        assert_ne!(
            fingerprint(&[b"tool v1", b"source"]),
            fingerprint(&[b"tool v2", b"source"])
        );
    }

    #[test]
    fn cached_stages_reconstruct_entries_and_reject_corruption() {
        let dir = temp_dir("build-cache");
        let base = dir.join("base.apk");
        write_zip(
            &base,
            &[("resources.arsc", b"table"), ("classes.dex", b"original")],
        );
        let path = dir.join("cache.zip");
        save(
            &path,
            &base,
            &[("classes.dex", b"patched".to_vec())],
            &[("classes16.dex", b"helper".to_vec())],
        )
        .unwrap();
        assert_eq!(
            load(&path, &base, &["classes.dex", "classes16.dex"]).unwrap(),
            vec![b"patched".to_vec(), b"helper".to_vec()]
        );
        assert!(load(&path, &base, &["missing", "classes16.dex"]).is_err());
        assert!(load(&path, &base, &["classes.dex"]).is_err());
        write_zip(
            &base,
            &[
                ("resources.arsc", b"table"),
                ("classes.dex", b"wrong original"),
            ],
        );
        assert!(load(&path, &base, &["classes.dex", "classes16.dex"]).is_err());
        write_zip(
            &base,
            &[("resources.arsc", b"table"), ("classes.dex", b"original")],
        );
        let manifest = input::read_entry(&path, "bundle.txt").unwrap();
        write_zip(
            &path,
            &[
                ("bundle.txt", &manifest),
                ("p/0", b"broken"),
                ("p/1", b"broken"),
            ],
        );
        assert!(load(&path, &base, &["classes.dex", "classes16.dex"]).is_err());
    }
}
