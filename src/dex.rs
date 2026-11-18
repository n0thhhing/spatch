//! Guarded edits to Spotify's core hooks and the build for the helper DEX (classes16.dex).

use crate::Result;
use crate::edits::{EDITS, Edit};
use crate::helpers::{HELPERS, STUBS};
use crate::input;
use crate::tools::Tools;
use std::ffi::OsStr;
use std::fs;
use std::path::Path;

/// Original DEX entries that get reassembled, with their pinned SHA-256.
pub const DEX_TARGETS: [(&str, &str); 2] = [
    (
        "classes.dex",
        "0520d0e2b66ba60593b815e71902247327da8c5a6392334219cd77d1d438ea21",
    ),
    (
        "classes2.dex",
        "6f75b6523f9c11d4de3cefa3209d9d408c61e07caaa12d42155b3c9141b0b532",
    ),
];
/// Next free multidex slot (base ships classes.dex..classes15.dex).
pub const HELPER_DEX: &str = "classes16.dex";
const MAX_REFS: u32 = 65536;

/// Original classes moved into `HELPER_DEX` because their own DEX has no room for the references
/// their edits add; behaviour and names are unchanged. Paths are relative to the decoded APK.
const RELOCATED: [&str; 3] = [
    "smali/com/spotify/cosmos/servicebasedrouter/RemoteNativeRxRouter.smali",
    // classes.dex is at the method-reference limit: the core hooks' classes move out with their new calls.
    "smali/com/spotify/esperanto/esperanto/ClientBase.smali",
    // So is classes2.dex, for the audio session hooks.
    "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali",
];

/// Each relocated class as (smali folder, path in it, original DEX, descriptor).
fn relocated() -> Result<Vec<(String, String, String, String)>> {
    RELOCATED
        .iter()
        .map(|r| {
            let (folder, path) = r.split_once('/').ok_or("bad relocated path")?;
            let dex = format!(
                "classes{}.dex",
                folder.strip_prefix("smali_classes").unwrap_or("")
            );
            let class = format!("L{};", path.trim_end_matches(".smali"));
            Ok((folder.to_string(), path.to_string(), dex, class))
        })
        .collect()
}

/// Verifies each DEX to be edited matches the pinned original.
pub fn check_originals(base: &Path) -> Result<()> {
    let entries: Vec<_> = DEX_TARGETS.iter().map(|(entry, _)| *entry).collect();
    for ((entry, sha), bytes) in DEX_TARGETS
        .into_iter()
        .zip(input::read_entries(base, &entries)?)
    {
        let got = input::sha256_bytes(&bytes);
        if got != sha {
            return Err(
                format!("{entry} does not match the supported original (got {got})").into(),
            );
        }
    }
    if input::read_entry(base, HELPER_DEX).is_ok() {
        return Err(format!("{HELPER_DEX} already present; input is already patched").into());
    }
    Ok(())
}

/// Replaces the single occurrence of `anchor`; zero or several occurrences is a hard error.
pub fn apply(text: &str, edit: &Edit) -> Result<String> {
    let anchor = edit.anchor;
    match text.matches(anchor).count() {
        1 => Ok(text.replacen(anchor, edit.replacement, 1)),
        n => Err(format!(
            "{}: expected 1 anchor, found {n}: {:?}",
            edit.file,
            anchor.trim()
        )
        .into()),
    }
}

/// Returns the reassembled edited DEX (see `DEX_TARGETS`) and the new helper DEX.
pub fn build(
    base: &Path,
    staging: &Path,
    tools: &Tools,
) -> Result<Vec<(&'static str, Vec<u8>)>> {
    let version = tools.run(&tools.apktool, &[OsStr::new("--version")])?;
    let cache = crate::cache::path(
        "dex",
        &[
            include_bytes!("dex.rs"),
            include_bytes!("edits.rs"),
            version.as_bytes(),
        ],
    );
    let names: Vec<_> = DEX_TARGETS
        .iter()
        .map(|(name, _)| *name)
        .chain([HELPER_DEX])
        .collect();
    let cached = cache
        .as_ref()
        .and_then(|p| crate::cache::load(p, base, &names).ok());
    let mut entries = if let Some(bytes) = cached {
        println!("DEX rebuild: verified cache hit");
        names.into_iter().zip(bytes).collect()
    } else {
        let entries = rebuild(base, staging, tools)?;
        if let Some(path) = &cache {
            let _ = crate::cache::save(
                path,
                base,
                &entries[..DEX_TARGETS.len()],
                &entries[DEX_TARGETS.len()..],
            );
        }
        entries
    };
    let listener = entries.pop().ok_or("missing relocated DEX")?.1;
    let path = staging.join("audio-listener.dex");
    fs::write(&path, listener)?;
    entries.push((
        HELPER_DEX,
        compile_helper(staging, tools, &path)?,
    ));
    Ok(entries)
}

fn rebuild(
    base: &Path,
    staging: &Path,
    tools: &Tools,
) -> Result<Vec<(&'static str, Vec<u8>)>> {
    let fw = staging.join("dex-framework");
    let smali = staging.join("smali");
    let built = staging.join("dex.apk");
    let sources = staging.join("sources.apk");
    input::select_entries(base, &sources, |name| {
        matches!(name, "AndroidManifest.xml" | "resources.arsc")
            || DEX_TARGETS.iter().any(|(entry, _)| *entry == name)
    })?;
    tools.run(
        &tools.apktool,
        &[
            OsStr::new("d"),
            OsStr::new("-r"),
            OsStr::new("-p"),
            fw.as_os_str(),
            OsStr::new("-o"),
            smali.as_os_str(),
            sources.as_os_str(),
        ],
    )?;
    for e in &EDITS {
        let path = smali.join(e.file);
        fs::write(&path, apply(&fs::read_to_string(&path)?, e)?)?;
    }
    // Their original DEX tables are full. Keep these classes' names and behavior in
    // the helper DEX so the new references they gain do not overflow them.
    let relocated = relocated()?;
    for (folder, name, _, _) in &relocated {
        let moved = smali.join("smali_classes16").join(name);
        fs::create_dir_all(moved.parent().unwrap())?;
        fs::rename(smali.join(folder).join(name), moved)?;
    }
    // Only the edited DEX need reassembling; the rest are taken from the original APK unchanged.
    for d in fs::read_dir(&smali)? {
        let d = d?;
        let name = d.file_name();
        let name = name.to_string_lossy();
        // smali -> classes.dex, smali_classesN -> classesN.dex; classes16 is the helper.
        let dex = format!(
            "classes{}.dex",
            name.strip_prefix("smali_classes").unwrap_or("")
        );
        if name.starts_with("smali")
            && name != "smali_classes16"
            && !DEX_TARGETS.iter().any(|(entry, _)| *entry == dex)
        {
            fs::remove_dir_all(d.path())?;
        }
    }
    tools.run(
        &tools.apktool,
        &[
            OsStr::new("b"),
            OsStr::new("-p"),
            fw.as_os_str(),
            OsStr::new("-o"),
            built.as_os_str(),
            smali.as_os_str(),
        ],
    )?;

    let mut out = Vec::new();
    for (entry, _) in DEX_TARGETS {
        let bytes = input::read_entry(&built, entry)?;
        let path = staging.join(entry);
        fs::write(&path, &bytes)?;
        let dump = tools.run(&tools.dexdump, &[OsStr::new("-f"), path.as_os_str()])?;
        check_ref_limits(entry, &dump)?;
        let still_here = relocated.iter().any(|(_, _, dex, class)| {
            dex == entry
                && dump
                    .lines()
                    .any(|line| line.trim().starts_with("Class descriptor") && line.contains(class))
        });
        if still_here {
            return Err(
                "relocated classes must have exactly one definition in the helper DEX".into(),
            );
        }
        out.push((entry, bytes));
    }
    // Nothing relocated means apktool builds no helper DEX of its own: d8 then takes the helpers alone.
    out.push((HELPER_DEX, if relocated.is_empty() { Vec::new() } else { input::read_entry(&built, HELPER_DEX)? }));
    Ok(out)
}


/// javac against compile-only stubs, then d8; the stubs are passed as a library, never packaged.
fn compile_helper(
    staging: &Path,
    tools: &Tools,
    listener_dex: &Path,
) -> Result<Vec<u8>> {
    let root = staging.join("helper");
    let (stub_src, stub_cls, cls, dex) = (
        root.join("stubs-src"),
        root.join("stubs"),
        root.join("classes"),
        root.join("dex"),
    );
    for d in [&stub_src, &stub_cls, &cls, &dex] {
        fs::create_dir_all(d)?;
    }
    let mut javac_stubs = vec![
        OsStr::new("--release"),
        OsStr::new("11"),
        OsStr::new("-d"),
        stub_cls.as_os_str(),
    ];
    let stub_paths: Vec<_> = STUBS
        .iter()
        .map(|(n, _)| stub_src.join(n))
        .collect();
    for ((_, src), p) in STUBS.iter().zip(&stub_paths) {
        fs::write(p, src)?;
        javac_stubs.push(p.as_os_str());
    }
    let javac = tools.java_home.join("bin/javac");
    tools.run(&javac, &javac_stubs)?;
    let android = crate::tools::android_jar().ok_or("missing android.jar: install Android SDK platform 35 or later")?;
    let classpath = std::env::join_paths([android.as_path(), stub_cls.as_path()])?;
    let mut javac_helpers = vec![
        OsStr::new("--release"),
        OsStr::new("11"),
        OsStr::new("-cp"),
        classpath.as_os_str(),
        OsStr::new("-d"),
        cls.as_os_str(),
    ];
    let helper_paths: Vec<_> = HELPERS
        .iter()
        .map(|(n, _)| root.join(format!("{n}.java")))
        .collect();
    for ((_, src), p) in HELPERS.iter().zip(&helper_paths) {
        if let Some(parent) = p.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(p, src)?;
        javac_helpers.push(p.as_os_str());
    }
    tools.run(&javac, &javac_helpers)?;
    let stub_jar = root.join("stubs.jar");
    tools.run(
        &tools.java_home.join("bin/jar"),
        &[
            OsStr::new("cf"),
            stub_jar.as_os_str(),
            OsStr::new("-C"),
            stub_cls.as_os_str(),
            OsStr::new("."),
        ],
    )?;
    // Each helper plus its nested classes (Name$Inner.class).
    let mut class_paths = Vec::new();
    collect_classes(&cls, &mut class_paths)?;
    let mut d8 = vec![
        OsStr::new("--release"),
        OsStr::new("--min-api"),
        OsStr::new("24"),
        OsStr::new("--lib"),
        android.as_os_str(),
        OsStr::new("--lib"),
        stub_jar.as_os_str(),
        OsStr::new("--output"),
        dex.as_os_str(),
    ];
    if fs::metadata(listener_dex)?.len() > 0 {
        d8.push(listener_dex.as_os_str());
    }
    d8.extend(class_paths.iter().map(|p| p.as_os_str()));
    tools.run(&tools.d8, &d8)?;
    let out = dex.join("classes.dex");
    let classes = tools.run(&tools.dexdump, &[OsStr::new("-f"), out.as_os_str()])?;
    let mut expected: std::collections::HashSet<String> = class_paths
        .iter()
        .map(|p| -> Result<String> {
            let relative = p.strip_prefix(&cls)?.with_extension("");
            Ok(format!("L{};", relative.to_string_lossy().replace('\\', "/")))
        })
        .collect::<Result<_>>()?;
    expected.extend(relocated()?.into_iter().map(|(_, _, _, class)| class.to_owned()));
    let mut lines = classes.lines();
    let mut actual = std::collections::HashMap::new();
    while let Some(line) = lines.next() {
        if let Some((_, name)) = line.trim().strip_prefix("Class descriptor").and_then(|line| line.split_once(':')) {
            let flags = lines.next().unwrap_or_default();
            actual.insert(name.trim().trim_matches('\''), flags.contains("SYNTHETIC"));
        }
    }
    let unexpected = actual.iter().find(|&(&name, &synthetic)| {
        !expected.contains(name) && !(synthetic && generated_helper(name, &expected))
    });
    if let Some((name, _)) = unexpected {
        return Err(format!("unexpected helper DEX class {name}").into());
    }
    if let Some(name) = expected.iter().find(|name| !actual.contains_key(name.as_str())) {
        return Err(format!("missing helper DEX class {name}").into());
    }
    check_ref_limits(HELPER_DEX, &classes)?;
    Ok(fs::read(out)?)
}

// Some d8 versions retain the lambda name; others emit a numbered nested class.
fn generated_helper(name: &str, expected: &std::collections::HashSet<String>) -> bool {
    let Some(name) = name.strip_suffix(';') else { return false };
    let parts = name.split_once("$$ExternalSyntheticLambda").or_else(|| name.rsplit_once('$'));
    parts.is_some_and(|(owner, number)| {
        expected.contains(&format!("{owner};"))
            && !number.is_empty()
            && number.bytes().all(|c| c.is_ascii_digit())
    })
}

fn collect_classes(dir: &Path, out: &mut Vec<std::path::PathBuf>) -> Result<()> {
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            collect_classes(&path, out)?;
        } else if path.extension().and_then(|e| e.to_str()) == Some("class") {
            out.push(path);
        }
    }
    Ok(())
}

/// Fails if a reassembled DEX exceeds the method or field reference limit.
pub fn check_ref_limits(entry: &str, dexdump: &str) -> Result<()> {
    for key in ["method_ids_size", "field_ids_size"] {
        let n: u32 = dexdump
            .lines()
            .find_map(|l| {
                l.trim()
                    .strip_prefix(key)?
                    .trim()
                    .strip_prefix(':')?
                    .trim()
                    .parse()
                    .ok()
            })
            .ok_or_else(|| format!("{entry}: dexdump did not report {key}"))?;
        if n > MAX_REFS {
            return Err(format!("{entry}: {key} {n} exceeds {MAX_REFS}").into());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Spotify's classes sit in other packages: a hook that isn't public crashes at its first call.
    #[test]
    fn every_hook_spotify_calls_is_public() {
        for edit in EDITS {
            for call in edit.replacement.split("Lspatch/").skip(1) {
                let Some((class, rest)) = call.split_once(";->") else {
                    continue;
                };
                let method = rest.split(['(', ':']).next().unwrap();
                let source = HELPERS.iter().find(|(name, _)| *name == class).unwrap().1;
                let public_class = source
                    .lines()
                    .any(|l| l.starts_with("public ") && l.contains(&format!("class {class} ")));
                let public_method = source.lines().any(|l| {
                    l.trim_start().starts_with("public ")
                        && [
                            format!(" {method}("),
                            format!(" {method};"),
                            format!(" {method} ="),
                        ]
                        .iter()
                        .any(|name| l.contains(name))
                });
                assert!(public_class, "{class} must be public");
                assert!(public_method, "{class}.{method} must be public");
            }
        }
    }

    #[test]
    fn edits_require_exactly_one_anchor() {
        for e in &EDITS {
            let patched = apply(&format!("x\n{}y", e.anchor), e).unwrap();
            assert_eq!(patched, format!("x\n{}y", e.replacement));
            assert!(apply("nothing", e).is_err(), "{} needs its anchor", e.file);
            assert!(apply(&e.anchor.repeat(2), e).is_err(), "{}: an anchor twice is ambiguous", e.file);
        }
    }

    #[test]
    fn artwork_shape_edits_reject_missing_ambiguous_and_repeated_inputs() {
        for edit in EDITS.iter().filter(|e| e.file == "smali/p/kvx.smali") {
            assert!(apply("", edit).is_err());
            assert!(apply(&edit.anchor.repeat(2), edit).is_err());
            let patched = apply(edit.anchor, edit).unwrap();
            assert_eq!(patched, edit.replacement);
            assert!(apply(&patched, edit).is_err());
        }
    }


    #[test]
    fn every_helper_class_called_by_an_edit_is_built() {
        // A patched call into a helper that is not compiled crashes at runtime (NoClassDefFoundError).
        for e in &EDITS {
            for (i, _) in e.replacement.match_indices("Lspatch/") {
                let name = &e.replacement[i + 8..i + e.replacement[i..].find(';').unwrap()];
                assert!(
                    HELPERS.iter().any(|(n, _)| *n == name),
                    "{name} missing from HELPERS"
                );
            }
        }
    }


    #[test]
    fn ref_limits_parse_dexdump() {
        let ok = "method_ids_size     : 65536\nfield_ids_size      : 59764\n";
        assert!(check_ref_limits("c", ok).is_ok());
        assert!(
            check_ref_limits("c", "method_ids_size     : 65537\nfield_ids_size : 1\n").is_err()
        );
        assert!(check_ref_limits("c", "garbage").is_err());
    }

}
