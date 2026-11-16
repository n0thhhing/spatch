//! Guarded smali edits for the Now Playing controls plus the spatch.Scallop helper DEX.
//!
//! Only DEX_TARGETS are decoded and reassembled; the helper ships as a new classes16.dex.
//! classes9.dex is at the 65,536 method-reference limit, so its edits add field refs and constants only.

use crate::Result;
use crate::edits::{EDITS, Edit};
use crate::helpers::{STUBS, helpers};
use crate::input;
use crate::names;
use crate::theme::Theme;
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
/// their edits add; behaviour and names are unchanged. Each is an alias (`S_...`) or a smali path.
const RELOCATED: [&str; 4] = [
    // classes.dex is at the method-reference limit: the core hooks' classes move out with their new calls.
    "smali/com/spotify/esperanto/esperanto/ClientBase.smali",
    "smali/com/spotify/esperanto/esperantoimpl/DispatchingCoroutineTransport.smali",
    "smali/com/spotify/cosmos/servicebasedrouter/RemoteNativeRxRouter.smali",
    // So is classes2.dex, for the audio session hooks.
    "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali",
];

/// Each relocated class as (smali folder, path in it, original DEX, descriptor).
fn relocated() -> Result<Vec<(String, String, String, String)>> {
    RELOCATED
        .iter()
        .map(|r| {
            let file = names::smali_file(r)?;
            let (folder, path) = file.split_once('/').ok_or("bad relocated path")?;
            let dex = format!(
                "classes{}.dex",
                folder.strip_prefix("smali_classes").unwrap_or("")
            );
            let class = format!("L{};", path.trim_end_matches(".smali"));
            Ok((folder.to_string(), path.to_string(), dex, class))
        })
        .collect()
}

/// Every Spatch method the edits call, as `invoke kind|class|method|descriptor`, for the on-device link test.
fn hooks() -> String {
    let mut hooks: Vec<String> = EDITS
        .iter()
        .flat_map(|edit| edit.replacement.lines())
        .filter_map(|line| {
            let (kind, target) = line.trim().split_once(" {")?;
            let (_, target) = target.split_once("}, Lspatch/")?;
            let (class, target) = target.split_once(";->")?;
            let (method, shape) = target.split_once('(')?;
            let kind = kind.trim_end_matches("/range");
            Some(format!("{kind}|spatch.{class}|{method}|({shape}"))
        })
        .collect();
    hooks.sort();
    hooks.dedup();
    hooks.join(",")
}

/// What each obfuscated stub declares, as `class|member|parameter types`, for the on-device binding
/// test: `=` marks a field and `<init>` a constructor. A stub that drifts from the app only fails when
/// that call first runs, so nothing else catches it.
fn stub_members() -> String {
    let mut members = Vec::new();
    for (file, source) in STUBS.iter().filter(|(_, s)| s.contains("package p;")) {
        let class = file.trim_end_matches(".java");
        let code: String = source
            .lines()
            .map(|line| line.split("//").next().unwrap())
            .collect::<Vec<_>>()
            .join(" ");
        let (mut depth, mut statement) = (0, String::new());
        for c in code.chars() {
            match c {
                '{' | ';' if depth == 1 => {
                    for member in stub_member(&statement) {
                        // Many stub constructors and fields exist only for javac or the stub's own
                        // body: list what a helper uses.
                        let used = |text: &str| helpers(true).iter().any(|(_, h)| h.contains(text));
                        let made = used(&format!("new {class}("));
                        let field = member.strip_suffix("|=").map(|name| format!(".{name}"));
                        if field.is_some_and(|name| !used(&name)) || statement.contains("private ")
                        {
                            continue;
                        }
                        if !member.starts_with(&format!("{class}|")) {
                            members.push(format!("p.{class}|{member}"));
                        } else if made {
                            members.push(
                                format!("p.{class}|{member}")
                                    .replace(&format!("|{class}|"), "|<init>|"),
                            );
                        }
                    }
                    statement.clear();
                }
                _ if depth == 1 => statement.push(c),
                _ => {}
            }
            match c {
                '{' => depth += 1,
                '}' => depth -= 1,
                _ => {}
            }
        }
    }
    members.join(",")
}

/// One class-body statement as `name|parameter types`; a field line can name several, a nested type none.
fn stub_member(statement: &str) -> Vec<String> {
    let simple = |text: &str| {
        let text = text.replace("...", "[] ");
        let text = text.trim().split('<').next().unwrap().trim();
        let mut words: Vec<_> = text.split_whitespace().filter(|w| *w != "final").collect();
        words.pop();
        words.join(" ").rsplit('.').next().unwrap().to_string()
    };
    if let Some((head, rest)) = statement.split_once('(') {
        let name = head.split_whitespace().last().unwrap_or_default();
        let params = rest.split(')').next().unwrap_or_default();
        let types: Vec<_> = params
            .split(',')
            .filter(|p| !p.trim().is_empty())
            .map(simple)
            .collect();
        return vec![format!("{name}|{}", types.join(";"))];
    }
    let words: Vec<_> = statement.split_whitespace().collect();
    if words.is_empty()
        || words
            .iter()
            .any(|w| matches!(*w, "class" | "interface" | "enum"))
    {
        return Vec::new();
    }
    statement
        .split(',')
        .filter_map(|field| field.split('=').next()?.split_whitespace().last())
        .map(|name| format!("{name}|="))
        .collect()
}

/// Every obfuscated class the helpers compile against, for the on-device binding test.
fn obfuscated_classes() -> String {
    let names: Vec<_> = STUBS
        .iter()
        .filter(|(_, source)| source.contains("package p;"))
        .map(|(name, _)| format!("p.{}", name.trim_end_matches(".java")))
        .collect();
    names.join(",")
}

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
pub fn apply(text: &str, edit: &Edit, theme: &Theme) -> Result<String> {
    let anchor = names::rewrite(edit.anchor)?;
    match text.matches(&anchor).count() {
        1 => Ok(text.replacen(&anchor, &names::rewrite(&theme.apply(edit.replacement))?, 1)),
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
    theme: &Theme,
    debug: bool,
) -> Result<Vec<(&'static str, Vec<u8>)>> {
    let version = tools.run(&tools.apktool, &[OsStr::new("--version")])?;
    let cache = crate::cache::path(
        "dex",
        &[
            include_bytes!("dex.rs"),
            include_bytes!("edits.rs"),
            include_bytes!("names.rs"),
            include_bytes!("theme.rs"),
            include_bytes!("../scripts/port/aliases.tsv"),
            theme.name.as_bytes(),
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
        let entries = rebuild(base, staging, tools, theme)?;
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
        compile_helper(staging, tools, theme, &path, debug)?,
    ));
    Ok(entries)
}

fn rebuild(
    base: &Path,
    staging: &Path,
    tools: &Tools,
    theme: &Theme,
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
        let path = smali.join(names::smali_file(e.file)?);
        fs::write(&path, apply(&fs::read_to_string(&path)?, e, theme)?)?;
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
    theme: &Theme,
    listener_dex: &Path,
    debug: bool,
) -> Result<Vec<u8>> {
    let root = staging.join("helper");
    let helpers = helpers(debug);
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
        .map(|(n, _)| Ok(stub_src.join(names::rewrite(n)?)))
        .collect::<Result<_>>()?;
    for ((_, src), p) in STUBS.iter().zip(&stub_paths) {
        fs::write(p, names::rewrite(src)?)?;
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
    let helper_paths: Vec<_> = helpers
        .iter()
        .map(|(n, _)| root.join(format!("{n}.java")))
        .collect();
    for ((_, src), p) in helpers.iter().zip(&helper_paths) {
        if let Some(parent) = p.parent() {
            fs::create_dir_all(parent)?;
        }
        let source = src
            .replace("@THEME@", theme.name)
            .replace("@VERSION@", env!("CARGO_PKG_VERSION"))
            .replace("@OBFUSCATED@", &obfuscated_classes())
            .replace("@HOOKS@", &hooks())
            .replace("@MEMBERS@", &stub_members());
        fs::write(p, names::rewrite(&source)?)?;
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
    use crate::helpers::{DEBUG_HELPERS, HELPERS};

    #[test]
    fn device_binding_test_lists_stub_members() {
        let members = names::rewrite(&stub_members()).unwrap();
        if let Ok(path) = std::env::var("SPATCH_DUMP_MEMBERS") {
            std::fs::write(path, members.replace(',', "\n")).unwrap();
        }
        let has = |m: &str| members.contains(&names::rewrite(m).unwrap());
        assert!(has("p.S_ComposeMutableState|getValue|"));
        assert!(has("p.S_NavBarTabState|b|="));
        assert!(has("p.S_KotlinCollections|s|Object[]"));
        assert!(!has("p.S_SettingsRowModel|mask|=") && !has("p.S_KotlinMutableStateFlow|value|="));
        assert!(has(
            "p.S_ComposeGraphicsLayer|D|S_ComposeModifier;float;float;float;float;float;float;float;long;S_ComposeShape;boolean;long;long;int;"
        ));
    }

    #[test]
    fn device_link_test_lists_the_hooks() {
        let hooks = hooks();
        assert!(hooks.contains("invoke-static|spatch.CoreBridge|wrap|(Ljava/lang/Object;)Lcom/spotify/esperanto/esperanto/Transport;"));
        assert!(hooks.contains("invoke-static|spatch.SpatchApp|resumed|(Landroid/app/Activity;)V"));
        assert_eq!(hooks.split(',').count(), EDITS.len());
    }

    /// Spotify's classes sit in other packages: a hook that isn't public crashes at its first call.
    #[test]
    fn every_hook_spotify_calls_is_public() {
        let sources = helpers(false);
        for edit in EDITS {
            for call in edit.replacement.split("Lspatch/").skip(1) {
                let Some((class, rest)) = call.split_once(";->") else {
                    continue;
                };
                let method = rest.split(['(', ':']).next().unwrap();
                let source = sources.iter().find(|(name, _)| *name == class).unwrap().1;
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
        let lav = crate::theme::by_name("lavender").unwrap();
        for e in &EDITS {
            let patched = apply(&format!("x\n{}y", e.anchor), e, lav).unwrap();
            assert_eq!(patched, format!("x\n{}y", e.replacement));
            assert!(apply("nothing", e, lav).is_err(), "{} needs its anchor", e.file);
            assert!(apply(&e.anchor.repeat(2), e, lav).is_err(), "{}: an anchor twice is ambiguous", e.file);
        }
    }

    #[test]
    fn artwork_shape_edits_reject_missing_ambiguous_and_repeated_inputs() {
        let theme = crate::theme::by_name("gruvbox").unwrap();
        for edit in EDITS.iter().filter(|e| e.file == "smali/p/kvx.smali") {
            assert!(apply("", edit, theme).is_err());
            assert!(apply(&edit.anchor.repeat(2), edit, theme).is_err());
            let patched = apply(edit.anchor, edit, theme).unwrap();
            assert_eq!(patched, edit.replacement);
            assert!(apply(&patched, edit, theme).is_err());
        }
    }



    #[test]
    fn only_debug_builds_contain_developer_code() {
        let names = |debug| -> Vec<&str> { helpers(debug).iter().map(|(n, _)| *n).collect() };
        assert!(!names(false).iter().any(|n| n.starts_with("Dev")));
        for (name, _) in DEBUG_HELPERS {
            assert!(names(true).contains(&name));
        }
        // Normal helpers may only reach it by name: a compile-time reference would fail without --debug.
        for (name, source) in HELPERS {
            for class in ["DevOptions.", "DevTests.", "DevOverlay.", "DevSampler."] {
                assert!(!source.contains(class), "{name} refers to {class}");
            }
        }
        assert!(
            names::rewrite(&obfuscated_classes())
                .unwrap()
                .contains(&names::rewrite("p.S_DrawerLayout").unwrap())
        );
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
