//! Guarded resource-entry replacement, rebuild, signing and verification.
//!
//! Only compiled XML entries listed in `TARGETS` and the DEX handled by `dex` change. `resources.arsc`,
//! the manifest and every other DEX stay byte-identical: apktool's full resource round trip is lossy for this build
//! (base references density-split-only dimens), so apktool is used only to compile the patched XML
//! against the original resource IDs, and the compiled entries are injected into the original APK.

use crate::Result;
use crate::arsc;
use crate::dex;
use crate::input::{self, APKS, PACKAGE, VERSION_CODE};
use crate::lottie;
use crate::theme::Theme;
use crate::tools::Tools;
use std::collections::HashSet;
use std::ffi::OsStr;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use zip::write::SimpleFileOptions;

pub struct Target {
    /// Entry path in base.apk (also the path in the decoded tree).
    pub entry: &'static str,
    /// SHA-256 of the original compiled entry; anything else (other build, already patched) is rejected.
    pub original_sha256: &'static str,
    /// Spatch's changes to the decoded original, as an RCS diff (`diff -n`): only the added lines are
    /// stored, so the repo holds none of Spotify's own markup.
    pub diff: &'static str,
}

/// Applies an RCS diff: `dL N` drops N lines from original line L, `aL N` adds the next N diff lines
/// after original line L. Commands refer to original line numbers in ascending order; the original is
/// pinned by `original_sha256`, so a diff that does not fit is an error, never a fuzzy match.
pub fn apply_diff(original: &str, diff: &str) -> Result<String> {
    let old: Vec<&str> = original.split_inclusive('\n').collect();
    let mut out = String::with_capacity(original.len() + diff.len());
    let mut next = 0; // original lines already handled
    let mut lines = diff.split_inclusive('\n');
    while let Some(cmd) = lines.next() {
        let bad = || {
            format!(
                "diff command {:?} does not fit the original",
                cmd.trim_end()
            )
        };
        let mut parts = cmd
            .get(1..)
            .unwrap_or("")
            .trim_end()
            .split(' ')
            .map(str::parse::<usize>);
        let (Some(Ok(at)), Some(Ok(n)), None) = (parts.next(), parts.next(), parts.next()) else {
            return Err(bad().into());
        };
        match cmd.as_bytes()[0] {
            b'd' if at > next && at - 1 + n <= old.len() => {
                old[next..at - 1].iter().for_each(|l| out.push_str(l));
                next = at - 1 + n;
            }
            b'a' if at >= next && at <= old.len() => {
                old[next..at].iter().for_each(|l| out.push_str(l));
                next = at;
                for _ in 0..n {
                    out.push_str(lines.next().ok_or_else(bad)?);
                }
            }
            _ => return Err(bad().into()),
        }
    }
    old[next..].iter().for_each(|l| out.push_str(l));
    Ok(out)
}

macro_rules! target {
    ($entry:literal, $sha:literal) => {
        Target {
            entry: $entry,
            original_sha256: $sha,
            diff: include_str!(concat!("../theme/player/", $entry, ".diff")),
        }
    };
}

/// Now Playing slice. Ownership: p.jcs inflates revised_template_page_layout (which includes
/// revised_template_overlay); p.u1o inflates track_info_nowplaying_view; the seekbar drawables are
/// reached only through the NowPlayingSeekBar style and p.S_SeekbarTint.
pub const TARGETS: [Target; 20] = [
    // Mini-player surface (also its live-room and hat variants): adds a faint outline.
    target!(
        "res/drawable/floating_layout_bg.xml",
        "38a2e453dfa04a41bffa7966adb27596cf18b7ded105fe8929513d9d372be2a3"
    ),
    // Encore PlayButtonView header background (albums, shows): spatch.ScallopFill.
    target!(
        "res/drawable/play_button_header_style_background.xml",
        "c2205ec1f2a06c3bb3f32e4274a6bd472b5ae3bc1275710bb19bd2cd1bd4d9b6"
    ),
    target!(
        "res/layout/square_cover_art_content.xml",
        "be430f822bf1505cb7ec73f65dee5f6c4c6cfd82b7a90654357fcbbfaf581d97"
    ),
    target!(
        "res/layout/revised_template_page_layout.xml",
        "c616ffbf6aa8f51c453385870ce03efc2576578c8b7cfe93b6bbb3aa25fa9361"
    ),
    target!(
        "res/layout/revised_template_overlay.xml",
        "973d7edf5612c7c730b0ebd8888f62115a28af9cf1f9f5967a7e345ee36f35f9"
    ),
    target!(
        "res/layout/track_info_nowplaying_view.xml",
        "9bf266faf8b65235ca469fd81c2f5baa03339d7224f1788158ed0b290bfb614e"
    ),
    target!(
        "res/drawable/nowplaying_overlay_gradient.xml",
        "441662e890cab6c5ea08b3b302b04a405614d67b262a3418250907721b843d55"
    ),
    target!(
        "res/drawable/seekbar_thumb_default.xml",
        "77d89f8b63fed7bacef8bc2d709a854b2d185dab7f82f394b603dda66104fd75"
    ),
    target!(
        "res/drawable/seekbar_progress.xml",
        "5b47d070e7982c168d18f157bf5d8621aa99d473a0309917a73929cede19cff9"
    ),
    // Mini-player: p.m1p0 inflates now_playing_bar (MotionLayout driven by xml/now_playing_bar_scene);
    // TrackInfoView inflates now_playing_bar_track_info_view. The outer bar corners are drawn by a
    // Compose host (device check 2026-10-28), so floating_layout_bg is deliberately left alone.
    target!(
        "res/layout/now_playing_bar.xml",
        "d82f2e66955a6be84614c0de863aa8bdcdd2346e5bdf22f8fa3fdbd402a2b9b5"
    ),
    target!(
        "res/xml/now_playing_bar_scene.xml",
        "9afe1830631736ef1f7b32b4c413360d41a2e0d42a134c005b700b10e038aad6"
    ),
    target!(
        "res/layout/now_playing_bar_track_info_view.xml",
        "b12f9503969c41ebb1996f6343e17b0bc1498276543f71c8c5f67fea71b4938c"
    ),
    // Library's add rows (gra0): the icon tile matched the page, so it showed on a held row.
    target!(
        "res/drawable/library_compose_onboarding_card_default_background.xml",
        "435338e090e72b314587cc1ae7ad718ea09d445ee6adec8bfa4e3659af0f042f"
    ),
    target!(
        "res/drawable/library_onboarding_card_circular_background.xml",
        "0abe5c33408e437abe9d5f36c0808e069038efe408b150555173ec0258d9ee1c"
    ),
    // Page changes (tvn0.r): the fade Spotify already uses gains a small rise.
    target!(
        "res/anim/fade_in_hard.xml",
        "ff21800118ac677bf8ad53c60bb34a3786baadd6fdfdbb7ae2ac38b371ec966b"
    ),
    // Phone-portrait main layout (MainLayout, app:mode="vertical"): navigation_bar container and bottom
    // gradients. horizontal_main / large_main (landscape, tablets) are not patched.
    target!(
        "res/layout/vertical_main.xml",
        "e31f403f6ac29a03d5c656ff1a25159f7230c1b4eff56f2838a9a86769469545"
    ),
    // Home shelf card (p.a2s inflates image_link_card_layout); the same Encore card appears in other shelves.
    target!(
        "res/layout/image_link_card_layout.xml",
        "03047e5a25eaf1cc87fe8549fb18682ad727128b276ad96f5fd8ab74af49fe5c"
    ),
    // Player seekbar layout (p.S_SeekbarTint inflates it): taller track for the wavy progress.
    target!(
        "res/layout/track_seekbar.xml",
        "9a52a0a29e1431dec55ba411335251410f94882e3226ecb8cef9b49940af4364"
    ),
    // MainActivity window background (Theme.Glue.Launcher windowBackground); shows behind the
    // transparent Compose Search and Home pages.
    target!(
        "res/drawable/launcher_screen.xml",
        "78d71eb64c7fd787d8595ce19a1be21a6d555a0bca45bc71b1b624fa08aadfcb"
    ),
    // Now Playing widget's picker entry: previewLayout shows Spatch's widget instead of Spotify's
    // stock picture, which stays as previewImage for launchers before Android 12.
    target!(
        "res/xml/dynamic_npv_widget_info.xml",
        "07a21e2d672462203f14d8e1cd71d51bc89a76c3d41254b1b51a833f27498253"
    ),
];

/// Resources defined only in the density splits that a patched entry must keep referencing. apktool
/// cannot see them from base (it writes `@null`), so the staging copy declares each with its exact ID
/// and a placeholder value; only the compiled XML is shipped, never the staging resource table.
pub const SPLIT_REFS: [(&str, &str, &str); 4] = [
    ("drawable", "ic_spotify_icon_rgb_green", "0x7f080a71"),
    ("drawable", "npv_widget_preview", "0x7f080bbe"),
    ("dimen", "square_cover_art_size", "0x7f070755"),
    ("dimen", "square_cover_art_top_margin", "0x7f070756"),
];
const DENSITY_SPLITS: [&str; 2] = ["split_config.xxhdpi.apk", "split_config.xxxhdpi.apk"];

/// Original base resources.arsc; the output must differ (colour swaps applied).
const ORIGINAL_ARSC_SHA256: &str =
    "cc2b8bbe9712430de766c32031ef082d4d6307f6e2f26dfc57dd9b61ff3b4eb4";

/// Manifest flag values aapt2 36 rejects for recreateOnConfigChanges. Removed in the staging copy only:
/// the shipped manifest is the original binary entry.
const MANIFEST_WORKAROUND: &str = " android:recreateOnConfigChanges=\"0x40";
const MANIFEST_WORKAROUND_COUNT: usize = 2;

pub struct Signing {
    pub keystore: PathBuf,
    pub alias: String,
    /// Name of the environment variable holding the keystore password (passed to apksigner as env:NAME).
    pub pass_env: String,
}

/// Checks that every target entry in `base` still has its original bytes.
/// New file resources (Spatch widget), appended to the resource table by `arsc::append_files` and
/// compiled with the other targets. Sources live under theme/player like the targets.
macro_rules! new_file {
    ($ty:literal, $name:literal) => {
        (
            arsc::NewFile {
                type_name: $ty,
                name: $name,
                path: concat!("res/", $ty, "/", $name, ".xml"),
            },
            include_str!(concat!("../theme/player/res/", $ty, "/", $name, ".xml")),
        )
    };
}

pub const NEW_RESOURCES: [(arsc::NewFile, &str); 69] = [
    new_file!("drawable", "spatch_widget_bg"),
    new_file!("drawable", "spatch_widget_art"),
    new_file!("drawable", "spatch_widget_scallop"),
    new_file!("drawable", "spatch_widget_scallop8"),
    new_file!("drawable", "spatch_widget_to_play"),
    new_file!("drawable", "spatch_widget_to_pause"),
    new_file!("drawable", "spatch_widget_bar"),
    new_file!("drawable", "spatch_widget_eq"),
    new_file!("drawable", "spatch_widget_tri_next"),
    new_file!("drawable", "spatch_widget_tri_prev"),
    new_file!("drawable", "spatch_widget_tri_next_in"),
    new_file!("drawable", "spatch_widget_tri_prev_in"),
    new_file!("drawable", "spatch_widget_skip_next"),
    new_file!("drawable", "spatch_widget_skip_prev"),
    new_file!("animator", "spatch_widget_to8"),
    new_file!("animator", "spatch_widget_to5"),
    new_file!("animator", "spatch_widget_turn"),
    new_file!("animator", "spatch_widget_press"),
    new_file!("animator", "spatch_widget_nudge_next"),
    new_file!("animator", "spatch_widget_nudge_prev"),
    new_file!("animator", "spatch_widget_tri_grow"),
    new_file!("animator", "spatch_widget_tri_fade"),
    new_file!("anim", "spatch_widget_in"),
    new_file!("anim", "spatch_widget_out"),
    new_file!("layout", "spatch_widget"),
    new_file!("layout", "spatch_widget_large"),
    new_file!("layout", "spatch_widget_preview"),
    // Material Symbols Rounded (Google, Apache 2.0) for Spatch settings' icons.
    new_file!("drawable", "spatch_ms_album"),
    new_file!("drawable", "spatch_ms_arrow_left"),
    new_file!("drawable", "spatch_ms_arrow_top_left"),
    new_file!("drawable", "spatch_ms_arrow_up"),
    new_file!("drawable", "spatch_ms_audiobook"),
    new_file!("drawable", "spatch_ms_check"),
    new_file!("drawable", "spatch_ms_chevron_right"),
    new_file!("drawable", "spatch_ms_collection_active"),
    new_file!("drawable", "spatch_ms_device_mobile"),
    new_file!("drawable", "spatch_ms_edit"),
    new_file!("drawable", "spatch_ms_enhance"),
    new_file!("drawable", "spatch_ms_external_link"),
    new_file!("drawable", "spatch_ms_filter"),
    new_file!("drawable", "spatch_ms_filter_active"),
    new_file!("drawable", "spatch_ms_fullscreen"),
    new_file!("drawable", "spatch_ms_gem"),
    new_file!("drawable", "spatch_ms_group"),
    new_file!("drawable", "spatch_ms_heart"),
    new_file!("drawable", "spatch_ms_hidden"),
    new_file!("drawable", "spatch_ms_home"),
    new_file!("drawable", "spatch_ms_image"),
    new_file!("drawable", "spatch_ms_information_alt"),
    new_file!("drawable", "spatch_ms_list_view"),
    new_file!("drawable", "spatch_ms_minimize"),
    new_file!("drawable", "spatch_ms_notification_new"),
    new_file!("drawable", "spatch_ms_notifications_active"),
    new_file!("drawable", "spatch_ms_now_playing"),
    new_file!("drawable", "spatch_ms_pause"),
    new_file!("drawable", "spatch_ms_play"),
    new_file!("drawable", "spatch_ms_playlist"),
    new_file!("drawable", "spatch_ms_plus"),
    new_file!("drawable", "spatch_ms_podcasts"),
    new_file!("drawable", "spatch_ms_repeat"),
    new_file!("drawable", "spatch_ms_shuffle"),
    new_file!("drawable", "spatch_ms_skip_forward"),
    new_file!("drawable", "spatch_ms_sort"),
    new_file!("drawable", "spatch_ms_soundwave"),
    new_file!("drawable", "spatch_ms_star"),
    new_file!("drawable", "spatch_ms_t_shirt"),
    new_file!("drawable", "spatch_ms_time"),
    new_file!("drawable", "spatch_ms_track"),
    new_file!("drawable", "spatch_ms_user"),
];

/// New bitmap resources: the cover of the widget picker's sample song (`preview_art`).
pub const NEW_IMAGES: [arsc::NewFile; 1] = [arsc::NewFile {
    type_name: "drawable",
    name: "spatch_widget_preview_art",
    path: "res/drawable/spatch_widget_preview_art.jpg",
}];

/// The sample song in `spatch_widget_preview` (its title and artist are written in that layout).
/// Only the link lives in the repo; the cover is fetched at build time. Empty: plain tile.
pub const PREVIEW_TRACK: &str = "https://open.spotify.com/track/3CWhQE4dYKzAC0qpbGJANW";
pub const PREVIEW_FALLBACK: &[u8] =
    include_bytes!("../theme/player/res/drawable/spatch_widget_preview_fallback.jpg");

/// The sample song's cover from Spotify's public oEmbed endpoint, cached in `.cache/`; with no
/// network and no cached copy the preview shows a plain tile rather than failing the build.
fn preview_art() -> Vec<u8> {
    if PREVIEW_TRACK.is_empty() {
        return PREVIEW_FALLBACK.to_vec();
    }
    let id = PREVIEW_TRACK
        .rsplit('/')
        .next()
        .unwrap_or("")
        .split('?')
        .next()
        .unwrap_or("");
    let cached = Path::new(".cache").join(format!("widget-preview-{id}.jpg"));
    if let Ok(bytes) = fs::read(&cached) {
        return bytes;
    }
    let fetch = |url: &str| {
        std::process::Command::new("curl")
            .args(["-sfL", "--max-time", "20", url])
            .output()
            .ok()
            .filter(|o| o.status.success() && !o.stdout.is_empty())
            .map(|o| o.stdout)
    };
    let cover = fetch(&format!(
        "https://open.spotify.com/oembed?url={PREVIEW_TRACK}"
    ))
    .and_then(|json| thumbnail(&String::from_utf8_lossy(&json)))
    .and_then(|url| fetch(&url))
    .filter(|b| b.starts_with(&[0xff, 0xd8])); // JPEG
    match cover {
        Some(bytes) => {
            let _ = fs::create_dir_all(".cache").and_then(|_| fs::write(&cached, &bytes));
            bytes
        }
        None => {
            println!("widget preview cover not fetched; using the plain tile");
            PREVIEW_FALLBACK.to_vec()
        }
    }
}

/// `thumbnail_url` from an oEmbed response, without a JSON dependency.
fn thumbnail(json: &str) -> Option<String> {
    let rest = &json[json.find("\"thumbnail_url\"")? + 15..];
    let rest = &rest[rest.find('"')? + 1..];
    Some(rest[..rest.find('"')?].replace("\\/", "/"))
}

/// Every new file, XML first; `append_files` hands out IDs in this order.
fn new_files() -> Vec<arsc::NewFile> {
    NEW_RESOURCES
        .iter()
        .map(|(f, _)| f)
        .chain(NEW_IMAGES.iter())
        .map(|f| arsc::NewFile { ..*f })
        .collect()
}

pub fn check_targets(base: &Path) -> Result<()> {
    for t in &TARGETS {
        let got = input::sha256_bytes(&input::read_entry(base, t.entry)?);
        if got != t.original_sha256 {
            return Err(format!(
                "target {} does not match the supported original (got {got}); input is modified, \
                 already patched or a different build",
                t.entry
            )
            .into());
        }
    }
    dex::check_originals(base)
}

/// What `run` makes of the patched entries.
pub enum Output<'a> {
    /// Verify everything, write nothing.
    DryRun,
    /// A signed split-APK set in the output directory.
    Signed(&'a Signing),
    /// A patch bundle for Spatch Manager (`bundle::write`), at the output path.
    Bundle,
}

pub fn run(
    apkm: &Path,
    out: &Path,
    output: Output,
    tools: &Tools,
    theme: &Theme,
    debug: bool,
) -> Result<()> {
    if out.exists() {
        return Err(format!(
            "output {} already exists; refusing to overwrite",
            out.display()
        )
        .into());
    }
    if let Output::Signed(s) = output {
        if std::env::var_os(&s.pass_env).is_none() {
            return Err(format!(
                "environment variable {} (keystore password) is not set",
                s.pass_env
            )
            .into());
        }
        if !s.keystore.is_file() {
            return Err(format!("keystore {} not found", s.keystore.display()).into());
        }
    }
    input::check_identity(apkm)?;
    let staging = Staging::new(out)?;
    let apks = staging.path.join("apkm");
    fs::create_dir(&apks)?;
    input::extract_apks(apkm, &apks)?;
    let base = apks.join("base.apk");
    check_targets(&base)?;
    let (mut compiled, new_entries) = compile_targets(&base, &staging.path, tools, theme)?;
    let mut dexes = dex::build(&base, &staging.path, tools, theme, debug)?;
    let helper = dexes.pop().expect("dex::build returns the helper last");
    compiled.append(&mut dexes);
    compiled.extend(lottie::patch_all(&base, theme)?);
    let swapped = arsc::swap_colors(&input::read_entry(&base, "resources.arsc")?, theme)?;
    compiled.push((
        "resources.arsc",
        arsc::append_files(&swapped, &new_files())?.0,
    ));
    let mut added = vec![helper];
    added.extend(new_entries);
    added.extend(crate::theme_assets::build(&base, &compiled, theme)?);
    added.extend(
        crate::fonts::FILES
            .iter()
            .map(|(name, bytes)| (*name, bytes.to_vec())),
    );
    let signing = match output {
        Output::DryRun => {
            println!(
                "dry run: input, targets, resource compilation and DEX reassembly verified; no output written"
            );
            return Ok(());
        }
        Output::Bundle => {
            crate::bundle::write(&apks, &compiled, &added, out)?;
            println!("wrote {}", out.display());
            return Ok(());
        }
        Output::Signed(s) => s,
    };

    let result = staging.path.join("result");
    fs::create_dir(&result)?;
    let mut certs = HashSet::new();
    for (name, _) in APKS {
        let unsigned = staging.path.join(format!("{name}.unsigned"));
        let aligned = staging.path.join(format!("{name}.aligned"));
        let signed = result.join(name);
        let (replace, add): (&[_], &[_]) = if name == "base.apk" {
            (&compiled, &added)
        } else {
            (&[], &[])
        };
        rewrite_apk(&apks.join(name), &unsigned, replace, add)?;
        tools.run(
            &tools.zipalign,
            &[
                OsStr::new("-p"),
                OsStr::new("-f"),
                OsStr::new("4"),
                unsigned.as_os_str(),
                aligned.as_os_str(),
            ],
        )?;
        let pass = format!("env:{}", signing.pass_env);
        tools.run(
            &tools.apksigner,
            &[
                OsStr::new("sign"),
                OsStr::new("--ks"),
                signing.keystore.as_os_str(),
                OsStr::new("--ks-key-alias"),
                OsStr::new(&signing.alias),
                OsStr::new("--ks-pass"),
                OsStr::new(&pass),
                OsStr::new("--out"),
                signed.as_os_str(),
                aligned.as_os_str(),
            ],
        )?;
        certs.insert(verify_apk(&signed, tools)?);
    }
    if certs.len() != 1 {
        return Err("signed APKs do not share one certificate".into());
    }
    verify_identity(&result.join("base.apk"), tools)?;
    let originals = TARGETS
        .iter()
        .map(|t| (t.entry, t.original_sha256))
        .chain(dex::DEX_TARGETS)
        .chain([("resources.arsc", ORIGINAL_ARSC_SHA256)]);
    for (entry, sha) in originals {
        let got = input::read_entry(&result.join("base.apk"), entry)?;
        if input::sha256_bytes(&got) == sha {
            return Err(format!("{entry} unchanged in output").into());
        }
    }
    input::read_entry(&result.join("base.apk"), dex::HELPER_DEX)?;
    fs::write(
        result.join("install-manifest.json"),
        install_manifest(certs.iter().next().unwrap(), theme.name),
    )?;
    fs::rename(&result, out)?;
    println!("wrote {}", out.display());
    Ok(())
}

/// Decodes base in staging, applies the theme sources and returns the compiled replacement entries.
fn compile_targets(base: &Path, staging: &Path, tools: &Tools, theme: &Theme) -> Result<Compiled> {
    let fw = staging.join("framework");
    let decoded = staging.join("decoded");
    let rebuilt = staging.join("rebuilt.apk");
    tools.run(
        &tools.apktool,
        &[
            OsStr::new("d"),
            OsStr::new("-s"),
            OsStr::new("-p"),
            fw.as_os_str(),
            OsStr::new("-o"),
            decoded.as_os_str(),
            base.as_os_str(),
        ],
    )?;
    let manifest = decoded.join("AndroidManifest.xml");
    fs::write(
        &manifest,
        strip_manifest_workaround(&fs::read_to_string(&manifest)?)?,
    )?;
    let dump_resources = |apk: &Path| {
        tools.run(
            &tools.aapt2,
            &[OsStr::new("dump"), OsStr::new("resources"), apk.as_os_str()],
        )
    };
    let mut split_dumps = Vec::new();
    for split in DENSITY_SPLITS {
        split_dumps.push(dump_resources(&base.with_file_name(split))?);
    }
    let public = decoded.join("res/values/public.xml");
    // New resources get the IDs the table append will give them, so compiled references agree.
    let new_ids = arsc::append_files(&input::read_entry(base, "resources.arsc")?, &new_files())?.1;
    let mut declared = declare_split_refs(&fs::read_to_string(&public)?, &split_dumps)?;
    let lines: String = new_files()
        .iter()
        .zip(&new_ids)
        .map(|(f, id)| {
            format!(
                "    <public type=\"{}\" name=\"{}\" id=\"{id:#010x}\" />\n",
                f.type_name, f.name
            )
        })
        .collect();
    declared = declared.replacen("</resources>", &format!("{lines}</resources>"), 1);
    fs::write(&public, declared)?;
    for (f, source) in &NEW_RESOURCES {
        let path = decoded.join(f.path);
        if path.exists() {
            return Err(format!(
                "{} already exists in the base; not the supported build",
                f.path
            )
            .into());
        }
        fs::write(path, theme.apply(source))?;
    }
    let art = preview_art();
    fs::write(decoded.join(NEW_IMAGES[0].path), &art)?;
    let placeholders: String = SPLIT_REFS
        .iter()
        .map(|(ty, name, _)| {
            let value = if *ty == "dimen" { "0dp" } else { "#00000000" };
            format!("    <{ty} name=\"{name}\">{value}</{ty}>\n")
        })
        .collect();
    fs::write(
        decoded.join("res/values/spatch_split_refs.xml"),
        format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n{placeholders}</resources>\n"
        ),
    )?;
    for t in &TARGETS {
        let path = decoded.join(t.entry);
        if !path.is_file() {
            return Err(format!("decoded target {} missing", t.entry).into());
        }
        let patched = apply_diff(&fs::read_to_string(&path)?, t.diff)
            .map_err(|e| format!("{}: {e}", t.entry))?;
        fs::write(path, theme.apply(&patched))?;
    }
    tools.run(
        &tools.apktool,
        &[
            OsStr::new("b"),
            OsStr::new("-p"),
            fw.as_os_str(),
            OsStr::new("-o"),
            rebuilt.as_os_str(),
            decoded.as_os_str(),
        ],
    )?;
    let mut known = resource_ids(&dump_resources(base)?);
    known.extend(SPLIT_REFS.iter().map(|(_, _, id)| id.to_string()));
    known.extend(new_ids.iter().map(|id| format!("{id:#010x}")));
    let mut compiled = Vec::new();
    for t in &TARGETS {
        let dump = |apk: &Path| {
            tools.run(
                &tools.aapt2,
                &[
                    OsStr::new("dump"),
                    OsStr::new("xmltree"),
                    OsStr::new("--file"),
                    OsStr::new(t.entry),
                    apk.as_os_str(),
                ],
            )
        };
        check_references(t.entry, &dump(&rebuilt)?, &dump(base)?, &known)?;
        compiled.push((t.entry, input::read_entry(&rebuilt, t.entry)?));
    }
    let mut added = Vec::new();
    for (f, _) in &NEW_RESOURCES {
        let tree = tools.run(
            &tools.aapt2,
            &[
                OsStr::new("dump"),
                OsStr::new("xmltree"),
                OsStr::new("--file"),
                OsStr::new(f.path),
                rebuilt.as_os_str(),
            ],
        )?;
        check_references(f.path, &tree, "", &known)?;
        added.push((f.path, input::read_entry(&rebuilt, f.path)?));
    }
    added.push((NEW_IMAGES[0].path, art));
    Ok((compiled, added))
}

/// Replaced entries, then new ones.
type Compiled = (Vec<(&'static str, Vec<u8>)>, Vec<(&'static str, Vec<u8>)>);

/// Adds `SPLIT_REFS` to the decoded public.xml after checking each is absent from base and defined
/// with the same type/name/ID in every density split.
pub fn declare_split_refs(public: &str, split_dumps: &[String]) -> Result<String> {
    let mut decls = String::new();
    for (ty, name, id) in SPLIT_REFS {
        if public.contains(&format!("name=\"{name}\"")) || public.contains(&format!("id=\"{id}\""))
        {
            return Err(format!("{ty}/{name} ({id}) unexpectedly present in base").into());
        }
        let want = format!("resource {id} {ty}/{name}");
        if !split_dumps
            .iter()
            .all(|d| d.lines().any(|l| l.trim() == want))
        {
            return Err(format!("{ty}/{name} ({id}) not defined in every density split").into());
        }
        decls.push_str(&format!(
            "    <public type=\"{ty}\" name=\"{name}\" id=\"{id}\" />\n"
        ));
    }
    let end = public
        .rfind("</resources>")
        .ok_or("public.xml has no </resources>")?;
    Ok(format!("{}{decls}{}", &public[..end], &public[end..]))
}

pub fn strip_manifest_workaround(manifest: &str) -> Result<String> {
    let count = manifest.matches(MANIFEST_WORKAROUND).count();
    if count != MANIFEST_WORKAROUND_COUNT {
        return Err(format!(
            "expected {MANIFEST_WORKAROUND_COUNT} recreateOnConfigChanges attributes, found {count}"
        )
        .into());
    }
    let mut out = String::with_capacity(manifest.len());
    let mut rest = manifest;
    while let Some(i) = rest.find(MANIFEST_WORKAROUND) {
        out.push_str(&rest[..i]);
        let value = i + MANIFEST_WORKAROUND.len();
        let close = rest[value..]
            .find('"')
            .ok_or("unterminated manifest attribute")?;
        rest = &rest[value + close + 1..];
    }
    out.push_str(rest);
    Ok(out)
}

/// IDs (`0x7f......`) defined by the original resource table.
pub fn resource_ids(dump: &str) -> HashSet<String> {
    dump.lines()
        .filter_map(|l| l.trim_start().strip_prefix("resource 0x"))
        .filter_map(|r| r.get(..8).map(|id| format!("0x{id}")))
        .collect()
}

/// Every app resource referenced by a compiled entry must exist in the original table, and the
/// entry may hold no more `@null` values than the original did (extra ones are references apktool
/// lost to density-split-only resources).
pub fn check_references(
    entry: &str,
    tree: &str,
    original: &str,
    known: &HashSet<String>,
) -> Result<()> {
    let nulls = |t: &str| t.matches("=@null").count();
    if nulls(tree) > nulls(original) {
        return Err(format!(
            "{entry}: {} @null values vs {} in the original; a reference was lost",
            nulls(tree),
            nulls(original)
        )
        .into());
    }
    for (i, _) in tree.match_indices("@0x7f") {
        let id = &tree[i + 1..i + 11];
        if !known.contains(id) {
            return Err(format!(
                "{entry}: reference {id} is not defined in the original base resource table"
            )
            .into());
        }
    }
    Ok(())
}

pub fn is_signature_entry(name: &str) -> bool {
    let Some(rest) = name.strip_prefix("META-INF/") else {
        return false;
    };
    rest == "MANIFEST.MF"
        || rest.starts_with("stamp-cert-sha256")
        || [".SF", ".RSA", ".DSA", ".EC"]
            .iter()
            .any(|ext| rest.ends_with(ext))
}

/// Copies `src` to `dst` without old signatures, replacing the given entries (which must all exist)
/// and appending `add` (which must not exist yet).
pub fn rewrite_apk(
    src: &Path,
    dst: &Path,
    replace: &[(&str, Vec<u8>)],
    add: &[(&str, Vec<u8>)],
) -> Result<()> {
    let mut zin = zip::ZipArchive::new(File::open(src)?)?;
    let mut zout = zip::ZipWriter::new(File::create_new(dst)?);
    let mut replaced = 0;
    for i in 0..zin.len() {
        let f = zin.by_index_raw(i)?;
        let name = f.name().to_string();
        if is_signature_entry(&name) {
            continue;
        }
        if add.iter().any(|(n, _)| *n == name) {
            return Err(format!("{}: entry {name} to add already exists", src.display()).into());
        }
        if let Some((_, bytes)) = replace.iter().find(|(n, _)| *n == name) {
            // Keep the original method: resources.arsc must stay stored (uncompressed) to be mapped.
            let opts = SimpleFileOptions::default().compression_method(f.compression());
            drop(f);
            zout.start_file(name, opts)?;
            std::io::Write::write_all(&mut zout, bytes)?;
            replaced += 1;
        } else {
            zout.raw_copy_file(f)?;
        }
    }
    for (name, bytes) in add {
        let opts = if crate::fonts::stored(name) {
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored)
        } else {
            deflated()
        };
        zout.start_file(*name, opts)?;
        std::io::Write::write_all(&mut zout, bytes)?;
    }
    zout.finish()?;
    if replaced != replace.len() {
        return Err(format!(
            "{}: replaced {replaced} of {} entries",
            src.display(),
            replace.len()
        )
        .into());
    }
    Ok(())
}

fn deflated() -> SimpleFileOptions {
    SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated)
}

/// Verifies alignment and signature; returns the signer certificate SHA-256.
fn verify_apk(apk: &Path, tools: &Tools) -> Result<String> {
    tools.run(
        &tools.zipalign,
        &[
            OsStr::new("-c"),
            OsStr::new("-p"),
            OsStr::new("4"),
            apk.as_os_str(),
        ],
    )?;
    let out = tools.run(
        &tools.apksigner,
        &[
            OsStr::new("verify"),
            OsStr::new("--print-certs"),
            apk.as_os_str(),
        ],
    )?;
    out.lines()
        .find_map(|l| l.strip_prefix("Signer #1 certificate SHA-256 digest: "))
        .map(str::to_string)
        .ok_or_else(|| format!("{}: no signer certificate reported", apk.display()).into())
}

fn verify_identity(base: &Path, tools: &Tools) -> Result<()> {
    let badging = tools.run(
        &tools.aapt2,
        &[OsStr::new("dump"), OsStr::new("badging"), base.as_os_str()],
    )?;
    let want = format!("package: name='{PACKAGE}' versionCode='{VERSION_CODE}'");
    if !badging.starts_with(&want) {
        return Err(format!(
            "rebuilt base identity mismatch: {}",
            badging.lines().next().unwrap_or("")
        )
        .into());
    }
    Ok(())
}

fn install_manifest(cert: &str, theme: &str) -> String {
    format!(
        r#"{{
  "package": "{PACKAGE}",
  "versionCode": {VERSION_CODE},
  "theme": "{theme}",
  "signerCertificateSha256": "{cert}",
  "required": ["base.apk", "split_config.arm64_v8a.apk"],
  "language": ["split_config.en.apk"],
  "densityChooseOne": ["split_config.xxhdpi.apk", "split_config.xxxhdpi.apk"],
  "note": "requiredSplitTypes=base__abi,base__density: install base + ABI + one density split (+ language). A different certificate cannot update the stock Spotify installation."
}}
"#
    )
}

/// Disposable staging directory next to the output; removed on drop, success or failure.
struct Staging {
    path: PathBuf,
}

impl Staging {
    fn new(out: &Path) -> Result<Staging> {
        let name = out
            .file_name()
            .ok_or("output path has no file name")?
            .to_string_lossy();
        let path = out.with_file_name(format!(".{name}.spatch-staging-{}", std::process::id()));
        fs::create_dir_all(
            out.parent()
                .filter(|p| !p.as_os_str().is_empty())
                .unwrap_or(Path::new(".")),
        )?;
        fs::create_dir(&path)?;
        Ok(Staging { path })
    }
}

impl Drop for Staging {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::tests::{temp_dir, write_zip};

    #[test]
    fn split_refs_require_every_density_split() {
        let (_, name, id) = SPLIT_REFS[0];
        let dump: String = SPLIT_REFS
            .iter()
            .map(|(ty, name, id)| {
                format!("    resource {id} {ty}/{name}\n      (xxhdpi) placeholder\n")
            })
            .collect();
        let public = "<resources>\n</resources>\n";
        let out = declare_split_refs(public, &[dump.clone(), dump.clone()]).unwrap();
        assert!(out.contains(&format!("name=\"{name}\" id=\"{id}\"")));
        assert!(out.trim_end().ends_with("</resources>"));
        assert!(declare_split_refs(public, &[dump.clone(), String::new()]).is_err());
        assert!(declare_split_refs(&out, &[dump.clone(), dump]).is_err());
    }

    #[test]
    fn manifest_workaround_removes_exactly_the_two_attributes() {
        let m = r#"<a android:x="1" android:recreateOnConfigChanges="0x4058" android:y="2"/><b android:recreateOnConfigChanges="0x4078"/>"#;
        assert_eq!(
            strip_manifest_workaround(m).unwrap(),
            r#"<a android:x="1" android:y="2"/><b/>"#
        );
        assert!(strip_manifest_workaround("<a/>").is_err());
    }

    #[test]
    fn references_must_exist_and_not_be_null() {
        let known = resource_ids(
            "    resource 0x7f080c54 drawable/seek_timestamp_background_large\n      () (file) res/x.xml",
        );
        assert!(known.contains("0x7f080c54"));
        let ok = "A: background(0x010100d4)=@0x7f080c54";
        assert!(check_references("e", ok, "", &known).is_ok());
        assert!(check_references("e", "A: textSize(0x01010095)=@0x7f0707c5", "", &known).is_err());
        let lost = "A: textSize(0x01010095)=@null";
        assert!(check_references("e", lost, "A: textSize=@0x7f0707c5", &known).is_err());
        // Deliberate @null in the original (e.g. now_playing_bar ViewStub backgrounds) stays allowed.
        assert!(check_references("e", lost, "A: background=@null", &known).is_ok());
    }

    #[test]
    fn rewrite_keeps_stored_entries_stored() {
        let d = temp_dir("stored");
        let src = d.join("in.apk");
        let mut z = zip::ZipWriter::new(File::create(&src).unwrap());
        let stored =
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
        z.start_file("resources.arsc", stored).unwrap();
        std::io::Write::write_all(&mut z, b"old").unwrap();
        z.finish().unwrap();
        let dst = d.join("out.apk");
        rewrite_apk(&src, &dst, &[("resources.arsc", b"new".to_vec())], &[]).unwrap();
        let mut out = zip::ZipArchive::new(File::open(&dst).unwrap()).unwrap();
        assert_eq!(
            out.by_name("resources.arsc").unwrap().compression(),
            zip::CompressionMethod::Stored
        );
    }

    #[test]
    fn rewrite_replaces_entries_and_drops_old_signatures() {
        let d = temp_dir("rewrite");
        let src = d.join("in.apk");
        write_zip(
            &src,
            &[
                ("META-INF/MANIFEST.MF", b"m"),
                ("META-INF/CERT.RSA", b"r"),
                ("META-INF/services/x", b"keep"),
                ("res/a.xml", b"old"),
                ("classes.dex", b"dex"),
            ],
        );
        let dst = d.join("out.apk");
        rewrite_apk(
            &src,
            &dst,
            &[("res/a.xml", b"new".to_vec())],
            &[("classes2.dex", b"add".to_vec())],
        )
        .unwrap();
        let names: Vec<String> = zip::ZipArchive::new(File::open(&dst).unwrap())
            .unwrap()
            .file_names()
            .map(String::from)
            .collect();
        assert_eq!(
            names,
            [
                "META-INF/services/x",
                "res/a.xml",
                "classes.dex",
                "classes2.dex"
            ]
        );
        assert_eq!(input::read_entry(&dst, "classes2.dex").unwrap(), b"add");
        let dst3 = d.join("out3.apk");
        assert!(rewrite_apk(&src, &dst3, &[], &[("classes.dex", vec![])]).is_err());
        assert_eq!(input::read_entry(&dst, "res/a.xml").unwrap(), b"new");
        assert_eq!(input::read_entry(&dst, "classes.dex").unwrap(), b"dex");

        let dst2 = d.join("out2.apk");
        assert!(rewrite_apk(&src, &dst2, &[("res/missing.xml", vec![])], &[]).is_err());
    }

    #[test]
    fn reads_the_oembed_thumbnail() {
        let json = r#"{"html":"x","thumbnail_url":"https:\/\/i.scdn.co\/image\/ab67","thumbnail_width":300}"#;
        assert_eq!(thumbnail(json).unwrap(), "https://i.scdn.co/image/ab67");
        assert!(thumbnail("{}").is_none());
    }

    #[test]
    fn diffs_apply_exactly_or_not_at_all() {
        let original = "a\nb\nc\nd\n";
        assert_eq!(
            apply_diff(original, "d2 1\na3 2\nx\ny\n").unwrap(),
            "a\nc\nx\ny\nd\n"
        );
        assert_eq!(
            apply_diff(original, "a0 1\nz\nd4 1\n").unwrap(),
            "z\na\nb\nc\n"
        );
        assert_eq!(apply_diff(original, "").unwrap(), original);
        for bad in [
            "d4 2\n",
            "a5 1\nx\n",
            "a2 2\nx\n",
            "d3 1\nd2 1\n",
            "x1 1\n",
            "d1\n",
        ] {
            assert!(apply_diff(original, bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn targets_reject_patched_or_foreign_base() {
        let d = temp_dir("targets");
        let base = d.join("base.apk");
        let entries: Vec<(&str, &[u8])> = TARGETS
            .iter()
            .map(|t| (t.entry, t.diff.as_bytes()))
            .collect();
        write_zip(&base, &entries);
        assert!(
            check_targets(&base)
                .unwrap_err()
                .to_string()
                .contains("already patched")
        );
        write_zip(&base, &[("res/other.xml", b"x")]);
        assert!(check_targets(&base).is_err());
    }

    #[test]
    fn refuses_existing_output_before_touching_input() {
        let d = temp_dir("exists");
        let out = d.join("out");
        std::fs::create_dir(&out).unwrap();
        let tools = Tools {
            java_home: d.clone(),
            apktool: d.clone(),
            aapt2: d.clone(),
            zipalign: d.clone(),
            apksigner: d.clone(),
            d8: d.clone(),
            dexdump: d.clone(),
        };
        let err = run(
            &d.join("missing.apkm"),
            &out,
            Output::DryRun,
            &tools,
            &crate::theme::THEMES[0],
            false,
        )
        .unwrap_err()
        .to_string();
        assert!(err.contains("already exists"));
    }

    /// Full dry run against the supplied APKM; needs the real input and Android tools.
    #[test]
    #[ignore]
    fn dry_run_on_supplied_apkm() {
        let apkm = std::fs::read_dir(env!("CARGO_MANIFEST_DIR"))
            .unwrap()
            .map(|e| e.unwrap().path())
            .find(|p| p.extension().is_some_and(|x| x == "apkm"))
            .expect("supplied .apkm in the project root");
        let before = input::sha256_file(&apkm).unwrap();
        let out = temp_dir("dry").join("out");
        run(
            &apkm,
            &out,
            Output::DryRun,
            &Tools::discover().unwrap(),
            crate::theme::by_name("gruvbox").unwrap(),
            false,
        )
        .unwrap();
        assert!(!out.exists());
        assert_eq!(input::sha256_file(&apkm).unwrap(), before);
    }
}
