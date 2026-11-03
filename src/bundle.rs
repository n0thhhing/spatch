//! Patch bundles for Spatch Manager: Spatch's changes to base.apk as bsdiff patches against the
//! user's own APKM, so a bundle can be published without any of Spotify's files. The manager
//! checks the APKM, applies the patches, signs every APK with the phone's own key and installs.
//!
//! The bundle is a zip: `bundle.txt` lists the steps, `p/<n>` holds each raw bsdiff patch.
//!
//! ```text
//! spatch-bundle 1
//! spotify <version name> <version code>
//! apkm <sha256>
//! apk <name> <sha256>                                every APK in the APKM, in install order
//! replace <entry> <original sha256> <result sha256> <patch>
//! add <entry> <S|D> <result sha256> <patch>          S stored, D deflated; appended in this order
//! cover <entry> <spotify track url>                   the phone fetches this cover for that entry
//! ```
//!
//! A replaced entry keeps its place and compression method. Added entries are patched against the
//! original `resources.arsc`: the theme overlays carry an edited copy of that table, and for
//! Spatch's own files the patch is just their bytes. Old signatures are dropped from every APK.
//! The widget preview cover is someone else's artwork, so the bundle carries the plain tile and a
//! `cover` line; the manager swaps in the cover it fetches, as the desktop build does.

use crate::Result;
use crate::input::{self, APKM_SHA256, APKS, VERSION_CODE, VERSION_NAME};
use crate::patch::{NEW_IMAGES, PREVIEW_FALLBACK, PREVIEW_TRACK};
use std::fmt::Write as _;
use std::fs::File;
use std::io::Write;
use std::path::Path;
use zip::write::SimpleFileOptions;

const BASIS: &str = "resources.arsc";

pub fn write(
    apks: &Path,
    replace: &[(&str, Vec<u8>)],
    add: &[(&str, Vec<u8>)],
    out: &Path,
) -> Result<()> {
    let base = apks.join("base.apk");
    let basis = input::read_entry(&base, BASIS)?;
    let mut steps =
        format!("spatch-bundle 1\nspotify {VERSION_NAME} {VERSION_CODE}\napkm {APKM_SHA256}\n");
    for (name, sha) in APKS {
        writeln!(steps, "apk {name} {sha}")?;
    }
    let mut patches = Vec::new();
    for (entry, bytes) in replace {
        let old = input::read_entry(&base, entry)?;
        let name = format!("p/{}", patches.len());
        writeln!(
            steps,
            "replace {entry} {} {} {name}",
            input::sha256_bytes(&old),
            input::sha256_bytes(bytes)
        )?;
        patches.push((name, diff(&old, bytes)?));
    }
    let cover = NEW_IMAGES[0].path;
    for (entry, bytes) in add {
        let bytes: &[u8] = if *entry == cover {
            PREVIEW_FALLBACK
        } else {
            bytes
        };
        let method = if crate::fonts::stored(entry) {
            "S"
        } else {
            "D"
        };
        let name = format!("p/{}", patches.len());
        writeln!(
            steps,
            "add {entry} {method} {} {name}",
            input::sha256_bytes(bytes)
        )?;
        patches.push((name, diff(&basis, bytes)?));
    }
    if !PREVIEW_TRACK.is_empty() && add.iter().any(|(e, _)| *e == cover) {
        writeln!(steps, "cover {cover} {PREVIEW_TRACK}")?;
    }
    let mut zip = zip::ZipWriter::new(File::create_new(out)?);
    let opts = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .compression_level(Some(9));
    zip.start_file("bundle.txt", opts)?;
    zip.write_all(steps.as_bytes())?;
    for (name, patch) in &patches {
        zip.start_file(name.as_str(), opts)?;
        zip.write_all(patch)?;
    }
    zip.finish()?;
    Ok(())
}

/// A bsdiff patch, checked by applying it before it is trusted.
fn diff(old: &[u8], new: &[u8]) -> Result<Vec<u8>> {
    let mut patch = Vec::new();
    bsdiff::diff(old, new, &mut patch)?;
    let mut back = Vec::with_capacity(new.len());
    bsdiff::patch(old, &mut patch.as_slice(), &mut back)?;
    if back != new {
        return Err("bsdiff patch does not reproduce its entry".into());
    }
    Ok(patch)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::tests::{temp_dir, write_zip};
    use std::io::Read;

    #[test]
    fn bundle_lists_steps_and_patches_reproduce_entries() {
        let d = temp_dir("bundle");
        write_zip(
            &d.join("base.apk"),
            &[
                ("resources.arsc", b"table table table"),
                ("classes.dex", b"old dex bytes"),
            ],
        );
        let out = d.join("b.zip");
        write(
            &d,
            &[("classes.dex", b"new dex bytes!".to_vec())],
            &[("assets/x", b"table plus".to_vec())],
            &out,
        )
        .unwrap();
        let mut zip = zip::ZipArchive::new(File::open(&out).unwrap()).unwrap();
        let mut steps = String::new();
        zip.by_name("bundle.txt")
            .unwrap()
            .read_to_string(&mut steps)
            .unwrap();
        assert!(steps.starts_with("spatch-bundle 1\n"));
        assert_eq!(
            steps.lines().filter(|l| l.starts_with("apk ")).count(),
            APKS.len()
        );
        let replace = steps.lines().find(|l| l.starts_with("replace ")).unwrap();
        assert!(replace.starts_with("replace classes.dex ") && replace.ends_with(" p/0"));
        let add = steps.lines().find(|l| l.starts_with("add ")).unwrap();
        assert!(add.starts_with("add assets/x D ") && add.ends_with(" p/1"));
        let mut patch = Vec::new();
        zip.by_name("p/1").unwrap().read_to_end(&mut patch).unwrap();
        let mut back = Vec::new();
        bsdiff::patch(b"table table table", &mut patch.as_slice(), &mut back).unwrap();
        assert_eq!(back, b"table plus");
        assert!(write(&d, &[], &[], &out).is_err(), "never overwrites");
    }

    #[test]
    fn bundle_never_carries_the_cover() {
        let d = temp_dir("bundle-cover");
        write_zip(&d.join("base.apk"), &[("resources.arsc", b"table")]);
        let out = d.join("b.zip");
        let cover = NEW_IMAGES[0].path;
        write(&d, &[], &[(cover, b"someone's artwork".to_vec())], &out).unwrap();
        let mut zip = zip::ZipArchive::new(File::open(&out).unwrap()).unwrap();
        let mut steps = String::new();
        zip.by_name("bundle.txt")
            .unwrap()
            .read_to_string(&mut steps)
            .unwrap();
        assert!(steps.contains(&format!("cover {cover} {PREVIEW_TRACK}\n")));
        let mut patch = Vec::new();
        zip.by_name("p/0").unwrap().read_to_end(&mut patch).unwrap();
        let mut back = Vec::new();
        bsdiff::patch(b"table", &mut patch.as_slice(), &mut back).unwrap();
        assert_eq!(back, PREVIEW_FALLBACK);
    }
}
