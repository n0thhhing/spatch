//! Exact-input identity and safe APKM extraction.

use crate::Result;
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

pub const APKM_SHA256: &str = "1b966decbcaaa5d1473f38cc8ae0f562f41bc90d76b671eb3726773ad36cbd8b";
pub const PACKAGE: &str = "com.spotify.music";
pub const VERSION_NAME: &str = "9.1.98.521";
pub const VERSION_CODE: &str = "148115007";

/// Every APK in the supported APKM, with its pinned SHA-256.
pub const APKS: [(&str, &str); 5] = [
    (
        "base.apk",
        "3374d8a2d1b6ec9319355b0c749072cd88cde1f382fdabb710ccdc0f66605093",
    ),
    (
        "split_config.arm64_v8a.apk",
        "69ecd638588dd5200562dc3ee8c88ceeacc732f2874fbbe4c21c153ec0f8b44e",
    ),
    (
        "split_config.en.apk",
        "ea9f683ebced9c7eb02610be53e1c336add873e10fd54248ed7a16bf94b7d47d",
    ),
    (
        "split_config.xxhdpi.apk",
        "0f5d19f82f19c431157ac300d55beda3584d03b75d204838cd3b15e26656f987",
    ),
    (
        "split_config.xxxhdpi.apk",
        "6dccbd4b6fda715f4d62e0da96ae03b241ceb0bb34648ed44ad1cbb28fd81c39",
    ),
];

/// Non-APK metadata entries the APKM is allowed to carry; they are never extracted.
const METADATA: [&str; 3] = ["info.json", "icon.png", "APKM_installer.url"];
const MAX_APK_BYTES: u64 = 512 * 1024 * 1024;

pub fn sha256_file(path: &Path) -> Result<String> {
    let mut f = File::open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
    let mut h = Sha256::new();
    io::copy(&mut f, &mut h)?;
    Ok(hex(&h.finalize()))
}

pub fn sha256_bytes(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
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
    for (name, _) in APKS {
        if !seen.iter().any(|s| s == name) {
            return Err(format!("APKM is missing {name}").into());
        }
    }
    for (name, want) in APKS {
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
    let mut zip = zip::ZipArchive::new(File::open(apk)?)?;
    let mut entry = zip
        .by_name(name)
        .map_err(|e| format!("{}: entry {name}: {e}", apk.display()))?;
    let mut buf = Vec::new();
    entry.read_to_end(&mut buf)?;
    Ok(buf)
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
