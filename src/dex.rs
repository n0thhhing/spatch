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
pub const DEX_TARGETS: [(&str, &str); 11] = [
    (
        "classes.dex",
        "0520d0e2b66ba60593b815e71902247327da8c5a6392334219cd77d1d438ea21",
    ),
    (
        "classes2.dex",
        "6f75b6523f9c11d4de3cefa3209d9d408c61e07caaa12d42155b3c9141b0b532",
    ),
    (
        "classes3.dex",
        "28b77b1cc585e4d4853a662a48144e4941e7532265f21d8b2dc598325c290da3",
    ),
    (
        "classes4.dex",
        "6c97eeef14cdcb27979633bd4d1e929156b0426e0e631a15eaf9fb67ecc32e52",
    ),
    (
        "classes5.dex",
        "d7fa0ae3238dd5a5d4c3755f7b63116c610c8e84be973871a8ef61cee05e95b5",
    ),
    (
        "classes7.dex",
        "f81bc0fa078bc2134b63e9043969060f42eb3044d29bc5d1d2d9c6f051d4f8b8",
    ),
    (
        "classes8.dex",
        "7956386cce678c2d4c0b907ef04eb9ed6aa564edefd4054067f91b2e128c8d5a",
    ),
    (
        "classes9.dex",
        "3cf0dd51390e5cfa4adc4389a90e921921656eef9178ad434d60239ff6b7cb90",
    ),
    (
        "classes10.dex",
        "0b48ba31f2c4905e0541172be072874873babc6b7322fc14238a3a9ceaa577a1",
    ),
    (
        "classes11.dex",
        "f994a60a684dd0e02567afa439a28e2717edaa372d749511c369e0431d3dac22",
    ),
    (
        "classes12.dex",
        "b51ab44ec17867799300aa46a0a46fe3eba6169ba7382a996bbb928d034ac526",
    ),
];
/// Next free multidex slot (base ships classes.dex..classes15.dex).
pub const HELPER_DEX: &str = "classes16.dex";
const MAX_REFS: u32 = 65536;

/// Original classes moved into `HELPER_DEX` because their own DEX has no room for the references
/// their edits add; behaviour and names are unchanged. Each is an alias (`S_...`) or a smali path.
const RELOCATED: [&str; 14] = [
    "smali_classes9/com/spotify/nowplaying/scroll/view/NowPlayingScrollView.smali",
    // Typography and the Home filter row: these DEX tables are full too.
    "S_MixFontLoaderD",
    "S_MixFontLoaderG",
    "S_HomeFilterRow",
    // Compose surface and shape policy (spatch.Surfaces): both DEX tables are full.
    "S_PageBackground",
    "S_ComposeShapes",
    // Encore's press spring: classes.dex has no room for the hook's reference.
    "S_EncorePressable",
    "smali_classes2/com/spotify/playbacknative/AudioEffectsListener.smali",
    "S_EncoreTint",
    // Widget hooks: Glance's RemoteViews hand-off and an outlined MediaSession constructor.
    "S_GlanceRemoteViews",
    "S_MediaSessionSite",
    "S_HomeFeedModel",
    "S_NavBarTabState",
    // Toggle chips: since 9.2.0.26 they sit in classes7, which has no room for Pill's references.
    "S_ToggleChip",
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
    out.push((HELPER_DEX, input::read_entry(&built, HELPER_DEX)?));
    Ok(out)
}

fn helper_source(source: &str, theme: &Theme) -> String {
    if source.contains("@PALETTES@") {
        let rows: Vec<_> = crate::theme::THEMES
            .iter()
            .map(|t| {
                format!(
                    "{{{}}}",
                    t.rgb
                        .iter()
                        .map(|c| format!("0x{c:06x}"))
                        .collect::<Vec<_>>()
                        .join(",")
                )
            })
            .collect();
        let light: Vec<_> = crate::theme::LIGHT
            .iter()
            .map(|row| {
                format!(
                    "{{{}}}",
                    row.iter()
                        .map(|c| format!("0x{c:06x}"))
                        .collect::<Vec<_>>()
                        .join(",")
                )
            })
            .collect();
        return theme
            .apply(source)
            .replace("@THEME@", theme.name)
            .replace("@PALETTES@", &rows.join(","))
            .replace("@LIGHT_PALETTES@", &light.join(","));
    }
    let mut out = String::new();
    let mut p = 0;
    while p < source.len() {
        if source[p..].starts_with("0x") {
            let end = p
                + 2
                + source[p + 2..]
                    .bytes()
                    .take_while(u8::is_ascii_hexdigit)
                    .count();
            if let Ok(value) = u32::from_str_radix(&source[p + 2..end], 16)
                && crate::theme::THEMES[0].rgb.contains(&(value & 0xffffff))
            {
                out.push_str("Palette.color(");
                out.push_str(&source[p..end]);
                out.push(')');
                p = end;
                continue;
            }
        }
        let ch = source[p..].chars().next().unwrap();
        out.push(ch);
        p += ch.len_utf8();
    }
    theme.apply(&out)
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
    let mut javac_helpers = vec![
        OsStr::new("--release"),
        OsStr::new("11"),
        OsStr::new("-cp"),
        stub_cls.as_os_str(),
        OsStr::new("-d"),
        cls.as_os_str(),
    ];
    let helper_paths: Vec<_> = helpers
        .iter()
        .map(|(n, _)| root.join(format!("{n}.java")))
        .collect();
    for ((_, src), p) in helpers.iter().zip(&helper_paths) {
        let source = helper_source(src, theme)
            .replace("@THEME@", theme.name)
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
    let class_paths: Vec<_> = fs::read_dir(cls.join("spatch"))?
        .map(|e| Ok(e?.path()))
        .collect::<Result<_>>()?;
    let mut d8 = vec![
        OsStr::new("--release"),
        OsStr::new("--min-api"),
        OsStr::new("24"),
        OsStr::new("--lib"),
        stub_jar.as_os_str(),
        OsStr::new("--output"),
        dex.as_os_str(),
    ];
    d8.push(listener_dex.as_os_str());
    d8.extend(class_paths.iter().map(|p| p.as_os_str()));
    tools.run(&tools.d8, &d8)?;
    let out = dex.join("classes.dex");
    let classes = tools.run(&tools.dexdump, &[OsStr::new("-f"), out.as_os_str()])?;
    let all_present = helpers
        .iter()
        .all(|(n, _)| classes.contains(&format!("'Lspatch/{n};'")));
    let nested = class_paths
        .iter()
        .filter(|p| p.to_string_lossy().contains('$'))
        .count();
    if classes.matches("Class descriptor").count() != helpers.len() + nested + RELOCATED.len()
        || !all_present
        || !relocated()?
            .iter()
            .all(|(_, _, _, class)| classes.contains(&format!("'{class}'")))
    {
        return Err(
            "helper DEX must contain exactly the spatch helpers and the relocated original classes"
                .into(),
        );
    }
    check_ref_limits(HELPER_DEX, &classes)?;
    Ok(fs::read(out)?)
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
        assert!(hooks.contains("invoke-static|spatch.Flourish|pressed|(Lkotlin/jvm/functions/Function0;I)Lkotlin/jvm/functions/Function0;"));
        assert!(hooks.split(',').count() > 20);
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
        names::load_resources(
            "    resource 0x7f06066c color/default_seekbar_color\n    resource 0x7f0605f6 color/dark_base_essential_brightaccent\n",
        );
        let e = EDITS
            .iter()
            .find(|e| e.anchor.contains("R_color_default_seekbar_color"))
            .unwrap();
        assert_eq!(
            apply("x\n    const v1, 0x7f06066c\ny", e, lav).unwrap(),
            "x\n    const v1, 0x7f0605f6\ny"
        );
        assert!(apply("nothing", e, lav).is_err());
        assert!(
            apply(
                "    const v1, 0x7f06066c\n    const v1, 0x7f06066c\n",
                e,
                lav
            )
            .is_err()
        );
        // Re-applying to patched text fails, so a mixed/already-patched tree is rejected.
        assert!(apply(e.replacement, e, lav).is_err());
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
    fn audio_session_hooks_reject_missing_ambiguous_and_repeated_inputs() {
        let theme = crate::theme::by_name("gruvbox").unwrap();
        let edits: Vec<_> = EDITS
            .iter()
            .filter(|e| e.file.ends_with("AudioEffectsListener.smali"))
            .collect();
        assert_eq!(edits.len(), 3);
        for edit in edits {
            assert!(apply("", edit, theme).is_err());
            assert!(apply(&edit.anchor.repeat(2), edit, theme).is_err());
            let patched = apply(edit.anchor, edit, theme).unwrap();
            assert_eq!(patched, edit.replacement);
            assert!(apply(&patched, edit, theme).is_err());
        }
    }

    #[test]
    fn runtime_palette_source_keeps_all_palettes_and_maps_role_literals() {
        let theme = crate::theme::by_name("gruvbox").unwrap();
        assert_eq!(
            helper_source("int c=0x80d0bcff; int id=0x7f123456;", theme),
            "int c=Palette.color(0x80fe8019); int id=0x7f123456;"
        );
        let source = helper_source(
            include_str!("../theme/dex/src/spatch/look/Palette.java"),
            theme,
        );
        assert!(!source.contains("@PALETTES@"));
        assert!(!source.contains("@THEME@"));
        assert!(source.contains("active=index(\"gruvbox\")"));
        assert!(source.contains("{0x14121c,0x1d1a2a"));
        assert!(source.contains("{0x282828,0x3c3836"));
        assert!(source.contains("BASE={0x282828"));
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
    fn custom_player_view_is_packaged_and_keeps_split_dimensions() {
        let layout = crate::patch::TARGETS
            .iter()
            .find(|t| t.entry == "res/layout/square_cover_art_content.xml")
            .unwrap()
            .diff;
        assert!(layout.contains("<spatch.PlayerArt "));
        assert!(HELPERS.iter().any(|(name, _)| *name == "PlayerArt"));
        for name in ["square_cover_art_size", "square_cover_art_top_margin"] {
            assert!(layout.contains(&format!("@dimen/{name}")));
            assert!(
                crate::patch::SPLIT_REFS
                    .iter()
                    .any(|(ty, n)| *ty == "dimen" && *n == name)
            );
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

    #[test]
    fn lbg_branch_keeps_circle_fallback() {
        let r = EDITS
            .iter()
            .find(|e| e.replacement.contains(":spatch_circle"))
            .unwrap()
            .replacement;
        assert!(
            r.contains(":spatch_circle")
                && r.contains("drawCircle")
                && r.trim_end().ends_with("const/4 v6, 0x0")
        );
    }
}
