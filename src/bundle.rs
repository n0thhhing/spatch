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
//! clone-authority <string>                            a resource string the manifest uses as an authority
//! clone-icon <entry>                                  the launcher icon's foreground, swapped in a clone
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
use std::path::{Path, PathBuf};
use zip::write::SimpleFileOptions;

const BASIS: &str = "resources.arsc";

pub fn write(
    apks: &Path,
    replace: &[(&str, Vec<u8>)],
    add: &[(&str, Vec<u8>)],
    clone: &str,
    out: &Path,
    compression: zip::CompressionMethod,
) -> Result<()> {
    let base = apks.join("base.apk");
    let basis = input::read_entry(&base, BASIS)?;
    let mut steps =
        format!("spatch-bundle 1\nspotify {VERSION_NAME} {VERSION_CODE}\napkm {APKM_SHA256}\n");
    for (name, sha) in APKS {
        writeln!(steps, "apk {name} {sha}")?;
    }
    let entries: Vec<_> = replace.iter().map(|(entry, _)| *entry).collect();
    let olds = input::read_entries(&base, &entries)?;
    let mut jobs: Vec<(&[u8], &[u8])> = Vec::new(); // (old, new), patch p/<index>
    for ((entry, bytes), old) in replace.iter().zip(&olds) {
        writeln!(
            steps,
            "replace {entry} {} {} p/{}",
            input::sha256_bytes(old),
            input::sha256_bytes(bytes),
            jobs.len()
        )?;
        jobs.push((old, bytes));
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
        writeln!(
            steps,
            "add {entry} {method} {} p/{}",
            input::sha256_bytes(bytes),
            jobs.len()
        )?;
        jobs.push((&basis, bytes));
    }
    let patches = diff_all(&jobs)?;
    if !PREVIEW_TRACK.is_empty() && add.iter().any(|(e, _)| *e == cover) {
        writeln!(steps, "cover {cover} {PREVIEW_TRACK}")?;
    }
    steps.push_str(clone);
    let mut zip = zip::ZipWriter::new(File::create_new(out)?);
    let opts = SimpleFileOptions::default()
        .compression_method(compression)
        .compression_level((compression == zip::CompressionMethod::Deflated).then_some(3));
    zip.start_file("bundle.txt", opts)?;
    zip.write_all(steps.as_bytes())?;
    for (i, patch) in patches.iter().enumerate() {
        zip.start_file(format!("p/{i}"), opts)?;
        zip.write_all(patch)?;
    }
    zip.finish()?;
    Ok(())
}

/// The `clone-` lines: what a clone must rename besides the manifest. `dump` is `aapt2 dump
/// resources` of base.apk, `tree(entry)` is `aapt2 dump xmltree` of one of its XML files.
pub fn clone_lines(dump: &str, tree: impl Fn(&str) -> Result<String>) -> Result<String> {
    let manifest = tree("AndroidManifest.xml")?;
    let mut lines = String::new();
    for id in refs(&manifest, "authorities") {
        for value in values(dump, &id) {
            let s = value
                .strip_prefix("() \"")
                .and_then(|v| v.strip_suffix('"'))
                .ok_or_else(|| format!("authority {id} is not a plain string: {value}"))?;
            writeln!(lines, "clone-authority {s}")?;
        }
    }
    // The application's own icon: the first android:icon after `E: application`.
    let app = manifest
        .split_once("E: application")
        .ok_or("the manifest has no application")?
        .1;
    let icon = refs(app, "icon")
        .next()
        .ok_or("the application has no icon")?;
    let adaptive = files(dump, &icon)
        .find(|f| f.starts_with("res/mipmap-anydpi"))
        .ok_or_else(|| format!("icon {icon} is not an adaptive icon"))?;
    let tree = tree(&adaptive)?;
    let foreground = tree
        .split_once("E: foreground")
        .and_then(|(_, f)| refs(f, "drawable").next())
        .ok_or_else(|| format!("{adaptive} has no foreground"))?;
    let mut any = false;
    for f in files(dump, &foreground) {
        writeln!(lines, "clone-icon {f}")?;
        any = true;
    }
    if !any {
        return Err(format!("the icon foreground {foreground} has no file").into());
    }
    Ok(lines)
}

/// The resource IDs an `android:<attr>` refers to in an xmltree dump.
fn refs<'a>(tree: &'a str, attr: &'a str) -> impl Iterator<Item = String> + 'a {
    let key = format!(":{attr}(");
    tree.lines()
        .filter(move |l| l.contains(&key))
        .filter_map(|l| Some(l.split_once("=@")?.1.trim().to_string()))
}

/// The value lines of one resource in a resources dump.
fn values<'a>(dump: &'a str, id: &str) -> impl Iterator<Item = &'a str> + 'a {
    let head = format!("resource {id} ");
    dump.lines()
        .skip_while(move |l| !l.trim_start().starts_with(&head))
        .skip(1)
        .take_while(|l| !l.trim_start().starts_with("resource "))
        .map(str::trim)
}

fn files<'a>(dump: &'a str, id: &str) -> impl Iterator<Item = String> + 'a {
    values(dump, id).filter_map(|v| {
        let path = v.split_once("(file) ")?.1;
        Some(path.split(' ').next()?.to_string())
    })
}

/// A bsdiff patch, checked by applying it before it is trusted.
/// Every patch, in order, spread over the machine's cores.
fn diff_all(jobs: &[(&[u8], &[u8])]) -> Result<Vec<Vec<u8>>> {
    let next = std::sync::atomic::AtomicUsize::new(0);
    let done = std::sync::Mutex::new(vec![None; jobs.len()]);
    let threads = std::thread::available_parallelism().map_or(1, |n| n.get());
    let cache = std::env::var_os("XDG_CACHE_HOME")
        .filter(|p| !p.is_empty())
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join(".cache")))
        .map(|p| p.join("spatch/diffs-v1"));
    std::thread::scope(|s| {
        for _ in 0..threads.min(jobs.len()) {
            let _ = std::thread::Builder::new()
                .stack_size(8 * 1024 * 1024)
                .spawn_scoped(s, || {
                    loop {
                        let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        let Some((old, new)) = jobs.get(i) else { break };
                        let patch = cached_diff(old, new, cache.as_deref()).map_err(|e| e.to_string());
                        done.lock().unwrap()[i] = Some(patch);
                    }
                });
        }
    });
    done.into_inner()
        .unwrap()
        .into_iter()
        .map(|p| Ok(p.expect("every job ran")?))
        .collect()
}

/// Reuse expensive diffs only for these exact bytes, and reapply every cache hit before trusting it.
fn cached_diff(old: &[u8], new: &[u8], cache: Option<&Path>) -> Result<Vec<u8>> {
    if old.starts_with(&TABLE_HEADER) && !like_table(new) {
        return diff(old, new); // literal patches don't sort the input
    }
    let path = cache.map(|dir| {
        dir.join(format!(
            "{}-{}.diff",
            input::sha256_bytes(old),
            input::sha256_bytes(new)
        ))
    });
    if let Some(path) = &path
        && let Ok(patch) = std::fs::read(path)
    {
        let mut back = Vec::with_capacity(new.len());
        if bsdiff::patch(old, &mut patch.as_slice(), &mut back).is_ok() && back == new {
            return Ok(patch);
        }
    }
    let patch = diff(old, new)?;
    if let Some(path) = path {
        // Cache failures never prevent a build; rename only after the complete patch is written.
        let temp = path.with_extension(format!("{}.tmp", std::process::id()));
        let _ = std::fs::create_dir_all(path.parent().unwrap());
        if let Ok(mut file) = File::create_new(&temp) {
            if file.write_all(&patch).is_ok() {
                let _ = std::fs::rename(&temp, &path);
            }
            let _ = std::fs::remove_file(temp);
        }
    }
    Ok(patch)
}

/// A resource table starts with this chunk header.
const TABLE_HEADER: [u8; 4] = [0x02, 0x00, 0x0c, 0x00];

/// Whether a new file can share bytes with the original table: a table, or a zip holding one (the
/// theme overlays). Nothing else Spatch adds does.
fn like_table(new: &[u8]) -> bool {
    new.starts_with(&TABLE_HEADER) || new.starts_with(b"PK\x03\x04")
}

/// A bsdiff patch that copies `new` in whole: one control (no mix, copy all, no seek), then the bytes.
fn literal(new: &[u8]) -> Vec<u8> {
    let mut patch = Vec::with_capacity(24 + new.len());
    for n in [0, new.len() as u64, 0] {
        patch.extend_from_slice(&n.to_le_bytes());
    }
    patch.extend_from_slice(new);
    patch
}

fn diff(old: &[u8], new: &[u8]) -> Result<Vec<u8>> {
    let mut patch = Vec::new();
    if old.starts_with(&TABLE_HEADER) && !like_table(new) {
        // Diffing against an unrelated table only costs a sort of it.
        patch = literal(new);
    } else {
        bsdiff::diff(old, new, &mut patch)?;
    }
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
    use zip::CompressionMethod::Deflated;

    #[test]
    fn new_files_unlike_the_table_are_copied_in_whole() {
        let table = [&TABLE_HEADER[..], b"the original table"].concat();
        // diff checks every patch reproduces its file
        assert_eq!(diff(&table, b"a font").unwrap(), literal(b"a font"));
        for overlay in [
            [&TABLE_HEADER[..], b"the edited table"].concat(),
            [&b"PK\x03\x04"[..], &table].concat(),
        ] {
            assert_ne!(
                diff(&table, &overlay).unwrap(),
                literal(&overlay),
                "overlays are diffed"
            );
        }
        let overlay = [&TABLE_HEADER[..], b"the edited table"].concat();
        let jobs: Vec<(&[u8], &[u8])> = vec![(&table, b"x"), (&table, &overlay), (b"old", b"new")];
        let all = diff_all(&jobs).unwrap();
        assert_eq!(all.len(), 3);
        assert_eq!(all[0], diff(&table, b"x").unwrap(), "kept in order");
    }

    #[test]
    fn clone_lines_follow_the_manifest_to_strings_and_the_icon() {
        let dump = "    resource 0x7f0809cf drawable/fg\n      () (file) res/drawable/fg.xml type=XML\n\
            \x20   resource 0x7f0f0000 mipmap/ic_launcher\n      () @mipmap/old\n      (anydpi-v26) (file) res/mipmap-anydpi-v26/ic_launcher.xml type=XML\n\
            \x20   resource 0x7f131aa3 string/media_provider_authority\n      () \"com.spotify.mobile.android.mediaapi\"\n";
        let tree = |entry: &str| -> Result<String> {
            Ok(match entry {
                "AndroidManifest.xml" => "  E: activity\n    A: android:icon(0x01010002)=@0x7f0f0002\n  E: application\n    A: android:icon(0x01010002)=@0x7f0f0000\n      E: provider\n        A: android:authorities(0x01010018)=\"com.spotify.music.share\" (Raw: \"x\")\n        A: android:authorities(0x01010018)=@0x7f131aa3\n",
                "res/mipmap-anydpi-v26/ic_launcher.xml" => "  E: adaptive-icon\n      E: background\n        A: android:drawable(0x01010199)=@0x7f06025c\n      E: foreground\n        A: android:drawable(0x01010199)=@0x7f0809cf\n",
                other => return Err(format!("unexpected {other}").into()),
            }
            .to_string())
        };
        assert_eq!(
            clone_lines(dump, tree).unwrap(),
            "clone-authority com.spotify.mobile.android.mediaapi\nclone-icon res/drawable/fg.xml\n"
        );
    }

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
            "",
            &out,
            Deflated,
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
        assert!(
            write(&d, &[], &[], "", &out, Deflated).is_err(),
            "never overwrites"
        );
    }

    #[test]
    fn bundle_never_carries_the_cover() {
        let d = temp_dir("bundle-cover");
        write_zip(&d.join("base.apk"), &[("resources.arsc", b"table")]);
        let out = d.join("b.zip");
        let cover = NEW_IMAGES[0].path;
        write(
            &d,
            &[],
            &[(cover, b"someone's artwork".to_vec())],
            "",
            &out,
            Deflated,
        )
        .unwrap();
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
#[test]
fn cached_diffs_are_reapplied_and_corruption_is_rebuilt() {
    let dir = input::tests::temp_dir("diff-cache");
    let (old, new) = (&b"original bytes"[..], &b"updated bytes"[..]);
    let expected = diff(old, new).unwrap();
    assert_eq!(cached_diff(old, new, Some(&dir)).unwrap(), expected);
    let path = std::fs::read_dir(&dir)
        .unwrap()
        .next()
        .unwrap()
        .unwrap()
        .path();
    // A different but valid patch proves the next call reads and validates the cache.
    std::fs::write(&path, literal(new)).unwrap();
    assert_eq!(cached_diff(old, new, Some(&dir)).unwrap(), literal(new));
    std::fs::write(&path, b"broken").unwrap();
    assert_eq!(cached_diff(old, new, Some(&dir)).unwrap(), expected);
    let unavailable = dir.join("not-a-directory");
    std::fs::write(&unavailable, b"x").unwrap();
    assert_eq!(cached_diff(old, new, Some(&unavailable)).unwrap(), expected);
}
