//! Exact-input identity and safe APKM extraction.

use crate::Result;
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

pub const APKM_SHA256: &str = "03c17c489820909ee1b8e7944fea9bb78e0b6fe00895c8a728b33793d4c16a9a";
pub const PACKAGE: &str = "com.spotify.music";
pub const VERSION_NAME: &str = "9.2.0.2053";
pub const VERSION_CODE: &str = "148387875";

/// Every APK in the supported APKM, with its pinned SHA-256.
pub const APKS: &[(&str, &str)] = &[
    (
        "base.apk",
        "a6bded836537f252d9e8f280649145d39a5e69d2797d0e7806f8ed504c6e174d",
    ),
    (
        "split_config.arm64_v8a.apk",
        "07960bbada3ceaf6265c54cfcc6bd91f616b71df3eadda28eff6699152b423a0",
    ),
    (
        "split_config.en.apk",
        "1ef18dc9a93b4cdd2ff9cd12865e60257ab4d2b87ba19955b6910b73ce6c2188",
    ),
    (
        "split_config.xxhdpi.apk",
        "ba702d247adb1ccecbec1448119e4814d6e1b33cc91792f421b19a48c0e05250",
    ),
];

/// Non-APK metadata entries the APKM is allowed to carry; they are never extracted.
const METADATA: [&str; 3] = ["info.json", "icon.png", "APKM_installer.url"];
const MAX_APK_BYTES: u64 = 512 * 1024 * 1024;

pub fn sha256_file(path: &Path) -> Result<String> {
    let mut f = File::open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
    let mut h = Sha256::new();
    io::copy(&mut f, &mut h)?;
    Ok(format!("{:x}", h.finalize()))
}

pub fn sha256_bytes(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

/// Rejects any APKM whose hash differs from the one supported build.
pub fn check_identity(apkm: &Path) -> Result<()> {
    let actual = sha256_file(apkm)?;
    if actual != APKM_SHA256 {
        return Err(format!(
            "unsupported input {}: SHA-256 {actual} does not match the only supported build \
             ({PACKAGE} {VERSION_NAME} / {VERSION_CODE}, {APKM_SHA256})",
            apkm.display()
        )
        .into());
    }
    Ok(())
}

/// Validates the archive inventory, then extracts the known APKs into `dest`.
/// Unknown, duplicate, nested or oversized entries are rejected before anything is written.
pub fn extract_apks(apkm: &Path, dest: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(File::open(apkm)?)?;
    let mut seen = Vec::new();
    for i in 0..zip.len() {
        let f = zip.by_index_raw(i)?;
        let name = f.name().to_string();
        if name.ends_with('/') || name.starts_with("META-INF/") || METADATA.contains(&name.as_str())
        {
            continue;
        }
        if !APKS.iter().any(|(n, _)| *n == name) {
            return Err(format!("unexpected APKM entry {name:?}").into());
        }
        if seen.contains(&name) {
            return Err(format!("duplicate APKM entry {name:?}").into());
        }
        if f.size() > MAX_APK_BYTES {
            return Err(format!(
                "APKM entry {name:?} declares {} bytes, over limit",
                f.size()
            )
            .into());
        }
        seen.push(name);
    }
    for &(name, _) in APKS {
        if !seen.iter().any(|s| s == name) {
            return Err(format!("APKM is missing {name}").into());
        }
    }
    for &(name, want) in APKS {
        let out = dest.join(name);
        let mut entry = zip.by_name(name)?;
        let mut w = File::create_new(&out)?;
        // take() bounds the write even if the declared size lied.
        io::copy(&mut (&mut entry).take(MAX_APK_BYTES + 1), &mut w)?;
        let got = sha256_file(&out)?;
        if got != want {
            return Err(format!("{name} SHA-256 {got} does not match pinned {want}").into());
        }
    }
    Ok(())
}

/// Reads one entry fully from an APK.
pub fn read_entry(apk: &Path, name: &str) -> Result<Vec<u8>> {
    Ok(read_entries(apk, &[name])?.remove(0))
}

/// Parse the ZIP directory once for a batch, rather than once per entry.
pub fn read_entries(apk: &Path, names: &[&str]) -> Result<Vec<Vec<u8>>> {
    let mut zip = zip::ZipArchive::new(File::open(apk)?)?;
    names
        .iter()
        .map(|name| {
            let mut entry = zip
                .by_name(name)
                .map_err(|e| format!("{}: entry {name}: {e}", apk.display()))?;
            let mut buf = Vec::new();
            entry.read_to_end(&mut buf)?;
            Ok(buf)
        })
        .collect()
}

/// Small tool inputs keep apktool from copying and repacking unrelated APK contents.
pub fn select_entries(apk: &Path, out: &Path, keep: impl Fn(&str) -> bool) -> Result<()> {
    let mut source = zip::ZipArchive::new(File::open(apk)?)?;
    let mut selected = zip::ZipWriter::new(File::create_new(out)?);
    for i in 0..source.len() {
        let entry = source.by_index_raw(i)?;
        if keep(entry.name()) {
            selected.raw_copy_file(entry)?;
        }
    }
    selected.finish()?;
    Ok(())
}

#[cfg(test)]
pub mod tests {
    use super::*;
    use std::io::Write;
    use std::path::PathBuf;

    pub fn temp_dir(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("spatch-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    pub fn write_zip(path: &Path, entries: &[(&str, &[u8])]) {
        let mut z = zip::ZipWriter::new(File::create(path).unwrap());
        for (name, data) in entries {
            z.start_file(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            z.write_all(data).unwrap();
        }
        z.finish().unwrap();
    }

    fn all_apks_plus(
        extra: &[(&'static str, &'static [u8])],
    ) -> Vec<(&'static str, &'static [u8])> {
        let mut v: Vec<(&str, &[u8])> = APKS.iter().map(|(n, _)| (*n, &b"apk"[..])).collect();
        v.extend_from_slice(extra);
        v
    }

    #[test]
    fn identity_rejects_other_input() {
        let d = temp_dir("identity");
        let p = d.join("x.apkm");
        std::fs::write(&p, b"not the build").unwrap();
        assert!(
            check_identity(&p)
                .unwrap_err()
                .to_string()
                .contains("unsupported input")
        );
    }

    #[test]
    fn tool_input_keeps_only_selected_entries_without_changing_them() {
        let d = temp_dir("selected");
        let source = d.join("base.apk");
        let selected = d.join("resources.apk");
        write_zip(
            &source,
            &[
                ("res/layout/player.xml", b"layout"),
                ("classes.dex", b"dex"),
                ("assets/large", b"asset"),
            ],
        );
        let before = sha256_file(&source).unwrap();
        select_entries(&source, &selected, |name| name.starts_with("res/")).unwrap();
        assert_eq!(
            read_entry(&selected, "res/layout/player.xml").unwrap(),
            b"layout"
        );
        assert_eq!(
            zip::ZipArchive::new(File::open(&selected).unwrap())
                .unwrap()
                .len(),
            1
        );
        assert_eq!(sha256_file(&source).unwrap(), before);
        assert_eq!(
            read_entries(
                &source,
                &["classes.dex", "res/layout/player.xml", "classes.dex"]
            )
            .unwrap(),
            vec![b"dex".to_vec(), b"layout".to_vec(), b"dex".to_vec()]
        );
        assert!(
            read_entries(&source, &["missing"])
                .unwrap_err()
                .to_string()
                .contains("entry missing")
        );
        assert!(select_entries(&source, &selected, |_| true).is_err());
    }

    #[test]
    fn extract_rejects_unsafe_or_unknown_entries() {
        for (case, bad) in [
            ("traversal", "../evil.apk"),
            ("unknown", "split_config.x86.apk"),
            ("nested", "a/base.apk"),
        ] {
            let d = temp_dir(case);
            let p = d.join("in.apkm");
            write_zip(&p, &all_apks_plus(&[(bad, b"x")]));
            let out = d.join("out");
            std::fs::create_dir(&out).unwrap();
            let err = extract_apks(&p, &out).unwrap_err().to_string();
            assert!(err.contains("unexpected APKM entry"), "{case}: {err}");
            assert_eq!(
                std::fs::read_dir(&out).unwrap().count(),
                0,
                "{case}: wrote files before rejecting"
            );
        }
    }

    #[test]
    fn extract_rejects_missing_split_and_wrong_hash() {
        let d = temp_dir("missing");
        let p = d.join("in.apkm");
        write_zip(&p, &[("base.apk", b"apk")]);
        assert!(
            extract_apks(&p, &d)
                .unwrap_err()
                .to_string()
                .contains("missing")
        );

        let d = temp_dir("hash");
        let p = d.join("in.apkm");
        write_zip(&p, &all_apks_plus(&[("info.json", b"{}")]));
        let out = d.join("out");
        std::fs::create_dir(&out).unwrap();
        assert!(
            extract_apks(&p, &out)
                .unwrap_err()
                .to_string()
                .contains("does not match pinned")
        );
    }
}
