//! The app rebuild: Spotify's core under Spatch's own UI. Only the core hooks in classes.dex and
//! classes2.dex change; the helper DEX (CoreBridge, SpatchApp) and the Rust app library are added.
//! Every other DEX stays byte-identical, and so do Spotify's own resources: the manifest and resources table only
//! gain Spatch's home-screen widget (manifest.rs, resources.rs), beside Spotify's own.

use crate::Result;
use crate::dex;
use crate::input::{self, APKS, PACKAGE, VERSION_CODE};
use crate::manifest;
use crate::resources;
use crate::tools::{self, Tools};
use std::collections::HashSet;
use std::ffi::OsStr;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use zip::write::SimpleFileOptions;

pub struct Signing {
    pub keystore: PathBuf,
    pub alias: String,
    /// Name of the environment variable holding the keystore password (passed to apksigner as env:NAME).
    pub pass_env: String,
}

/// What `run` makes of the patched entries.
pub enum Output<'a> {
    /// Verify the input and rebuild the DEX without writing an output.
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
    dex::check_originals(&base)?;
    // The app rebuild ships only what Spatch's own UI needs: the core hooks in Spotify's DEX, the helper
    // DEX that hosts the app, and the Rust app itself. Spotify's own resources stay as they are; the manifest and resources table only gain Spatch's widget.
    let mut compiled = dex::build(&base, &staging.path, tools)?;
    let widget = widget(&base, &staging.path, tools)?;
    let expect = widget.expect.clone();
    if let Output::DryRun = output {
        println!("dry run: input and DEX reassembly verified; no output written");
        return Ok(());
    }
    let helper = compiled.pop().expect("dex::build returns the helper last");
    compiled.push(("AndroidManifest.xml", widget.manifest));
    compiled.push(("resources.arsc", widget.table));
    let mut added = vec![helper];
    added.extend(widget.files);
    added.push((RUST_APP_ASSET, rust_app()?.ok_or("the Rust app library is missing: build app/ (see app/README.md) or set SPATCH_APP_SO")?));
    let signing = match output {
        Output::DryRun => unreachable!(),
        Output::Bundle => {
            let aapt2 = tools::sdk_tool("aapt2")?;
            let dump = tools.run(
                &aapt2,
                &[
                    OsStr::new("dump"),
                    OsStr::new("resources"),
                    base.as_os_str(),
                ],
            )?;
            let clone = crate::bundle::clone_lines(&dump, |entry| {
                tools.run(
                    &aapt2,
                    &[
                        OsStr::new("dump"),
                        OsStr::new("xmltree"),
                        OsStr::new("--file"),
                        OsStr::new(entry),
                        base.as_os_str(),
                    ],
                )
            })?;
            let bundle = staging.path.join("bundle.zip");
            crate::bundle::write(
                &apks,
                &compiled,
                &added,
                &clone,
                &bundle,
                zip::CompressionMethod::Deflated,
            )?;
            // Publish the finished ZIP without overwriting a competing output.
            fs::hard_link(&bundle, out)?;
            println!("wrote {}", out.display());
            return Ok(());
        }
        Output::Signed(s) => s,
    };


    let aapt2 = tools::sdk_tool("aapt2")?;
    let zipalign = tools::sdk_tool("zipalign")?;
    let apksigner = tools::sdk_tool("apksigner")?;
    let result = staging.path.join("result");
    fs::create_dir(&result)?;
    let mut certs = HashSet::new();
    for &(name, _) in APKS {
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
            &zipalign,
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
            &apksigner,
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
        certs.insert(verify_apk(&signed, tools, &zipalign, &apksigner)?);
    }
    if certs.len() != 1 {
        return Err("signed APKs do not share one certificate".into());
    }
    verify_identity(&result.join("base.apk"), &aapt2)?;
    let table = tools.run(&aapt2, &[OsStr::new("dump"), OsStr::new("resources"), result.join("base.apk").as_os_str()])?;
    if let Some(missing) = expect.iter().find(|line| !table.contains(line.as_str())) {
        return Err(format!("the rebuilt resources are missing: {missing}").into());
    }
    let tree = tools.run(&aapt2, &[OsStr::new("dump"), OsStr::new("xmltree"), OsStr::new("--file"), OsStr::new("AndroidManifest.xml"), result.join("base.apk").as_os_str()])?;
    if !tree.contains("spatch.SpatchWidgetSmall") || !tree.contains("spatch.SpatchWidget\"") || !tree.contains("npvwidget.NpvWidgetProvider") {
        return Err("the rebuilt manifest must hold both Spatch's widget and Spotify's".into());
    }
    let originals = dex::DEX_TARGETS;
    for (entry, sha) in originals {
        let got = input::read_entry(&result.join("base.apk"), entry)?;
        if input::sha256_bytes(&got) == sha {
            return Err(format!("{entry} unchanged in output").into());
        }
    }
    input::read_entry(&result.join("base.apk"), dex::HELPER_DEX)?;
    fs::write(
        result.join("install-manifest.json"),
        install_manifest(certs.iter().next().unwrap()),
    )?;
    fs::rename(&result, out)?;
    println!("wrote {}", out.display());
    Ok(())
}

/// The resource types Spatch's widget files belong to, and their ids in Spotify 9.2.0.2053's table (the rebuilt table
/// is checked for every added resource, so a wrong id fails the patch instead of shipping).
const WIDGET_TYPES: [(&str, u8); 5] = [("anim", 0x01), ("animator", 0x02), ("drawable", 0x08), ("layout", 0x0e), ("xml", 0x16)];

/// A widget resource: its type, its name and its source text (java/widget/res/<type>/<name>.xml).
macro_rules! res {
    ($t:literal, $n:literal) => {
        ($t, $n, include_str!(concat!("../java/widget/res/", $t, "/", $n, ".xml")))
    };
}

/// Spatch's widget, ported from the old Spatch: layouts, animations (the scallop morph and turn, the equaliser,
/// press and skip nudges), drawables and the two widget descriptions.
const WIDGET_RES: &[(&str, &str, &str)] = &[
    res!("anim", "spatch_widget_in"),
    res!("anim", "spatch_widget_out"),
    res!("animator", "spatch_widget_nudge_next"),
    res!("animator", "spatch_widget_nudge_prev"),
    res!("animator", "spatch_widget_press"),
    res!("animator", "spatch_widget_to5"),
    res!("animator", "spatch_widget_to8"),
    res!("animator", "spatch_widget_tri_fade"),
    res!("animator", "spatch_widget_tri_grow"),
    res!("animator", "spatch_widget_turn"),
    res!("drawable", "spatch_widget_art"),
    res!("drawable", "spatch_widget_bar"),
    res!("drawable", "spatch_widget_bg"),
    res!("drawable", "spatch_widget_eq"),
    res!("drawable", "spatch_widget_scallop"),
    res!("drawable", "spatch_widget_scallop8"),
    res!("drawable", "spatch_widget_skip_next"),
    res!("drawable", "spatch_widget_skip_prev"),
    res!("drawable", "spatch_widget_to_pause"),
    res!("drawable", "spatch_widget_to_play"),
    res!("drawable", "spatch_widget_tri_next"),
    res!("drawable", "spatch_widget_tri_next_in"),
    res!("drawable", "spatch_widget_tri_prev"),
    res!("drawable", "spatch_widget_tri_prev_in"),
    res!("layout", "spatch_widget"),
    res!("layout", "spatch_widget_small"),
    res!("xml", "spatch_widget_info"),
    res!("xml", "spatch_widget_small_info"),
];

/// Views of Spotify's own widget layouts whose ids (and names) Spatch's widget layouts reuse, so no id resources
/// are added. Their numbers are read from the app being patched; the rebuilt table is checked for each of them.
const WIDGET_VIEWS: [&str; 20] = ["frame", "metadata", "artwork", "thumbnail", "text_container", "title", "subtitle", "title2", "subtitle2", "header", "text1", "text2", "icon_view", "image_view", "previous_button", "play_button", "image", "spinner", "icon", "next_button"];

/// Spatch's home-screen widgets, added beside Spotify's own: the manifest receivers, the compiled resource files
/// and the resources table that lists them.
struct Widget {
    manifest: Vec<u8>,
    table: Vec<u8>,
    files: Vec<(&'static str, Vec<u8>)>,
    /// `aapt2 dump resources` lines the finished app must contain.
    expect: Vec<String>,
}

/// The id of `kind/name` in an `aapt2 dump resources` listing.
fn resource_id(dump: &str, kind: &str, name: &str) -> Result<u32> {
    let want = format!("{kind}/{name}");
    for line in dump.lines() {
        let mut words = line.split_whitespace();
        if let (Some("resource"), Some(id), Some(found), None) = (words.next(), words.next(), words.next(), words.next())
            && found == want
        {
            return Ok(u32::from_str_radix(id.trim_start_matches("0x"), 16)?);
        }
    }
    Err(format!("Spotify's resources have no {want}, which Spatch's widget layouts reuse").into())
}

fn widget(base: &Path, staging: &Path, tools: &Tools) -> Result<Widget> {
    let table = input::read_entry(base, "resources.arsc")?;
    let aapt2 = tools::sdk_tool("aapt2")?;
    let dump = tools.run(&aapt2, &[OsStr::new("dump"), OsStr::new("resources"), base.as_os_str()])?;
    let mut ids: Vec<(String, u32)> = Vec::new();
    let mut expect = Vec::new();
    for name in WIDGET_VIEWS {
        let id = resource_id(&dump, "id", name)?;
        ids.push((format!("id/{name}"), id));
        expect.push(format!("resource {id:#010x} id/{name}"));
    }
    // Each type hands out its next free entries in the order they are added.
    let mut next = std::collections::BTreeMap::new();
    let (mut items, mut sources, mut paths) = (Vec::new(), Vec::new(), Vec::new());
    for &(kind, name, text) in WIDGET_RES {
        let type_id = WIDGET_TYPES.iter().find(|t| t.0 == kind).ok_or("unknown widget resource type")?.1;
        let id = match next.get(&type_id) {
            Some(&id) => id,
            None => resources::next_id(&table, type_id)?,
        };
        next.insert(type_id, id + 1);
        ids.push((format!("{kind}/{name}"), id));
        expect.push(format!("resource {id:#010x} {kind}/{name}"));
        // Every path lives until the process ends, so each is leaked once.
        let path: &'static str = Box::leak(format!("res/{kind}/{name}.xml").into_boxed_str());
        items.push((type_id, name, path));
        sources.push((path, text));
        paths.push(path);
    }
    // Every id a layout declares must be one of the listed (Spotify's own) views, or aapt2 would invent a new number.
    for (_, name, text) in WIDGET_RES {
        for declared in text.split("@+id/").skip(1) {
            let id: String = declared.chars().take_while(|c| c.is_alphanumeric() || *c == '_').collect();
            if !WIDGET_VIEWS.contains(&id.as_str()) {
                return Err(format!("{name} declares the new id {id}; widget layouts may only use Spotify's own ids").into());
            }
        }
    }
    let compiled = resources::compile(tools, &staging.join("widget"), &sources, &ids)?;
    let (table, got) = resources::add(&table, &items)?;
    let wanted: Vec<u32> = ids.iter().skip(WIDGET_VIEWS.len()).map(|(_, id)| *id).collect();
    if got != wanted {
        return Err("the new resources did not get the ids they were compiled with".into());
    }
    let info = |name: &str| resource_id_of(&ids, "xml", name);
    let manifest = input::read_entry(base, "AndroidManifest.xml")?;
    let manifest = manifest::add_widget(&manifest, "spatch.SpatchWidget", "Spatch", info("spatch_widget_info")?)?;
    let manifest = manifest::add_widget(&manifest, "spatch.SpatchWidgetSmall", "Spatch", info("spatch_widget_small_info")?)?;
    Ok(Widget { manifest, table, files: paths.into_iter().zip(compiled).collect(), expect })
}

fn resource_id_of(ids: &[(String, u32)], kind: &str, name: &str) -> Result<u32> {
    let want = format!("{kind}/{name}");
    ids.iter().find(|(n, _)| *n == want).map(|(_, id)| *id).ok_or_else(|| format!("no {want}").into())
}

/// Where SpatchApp finds the Rust app library (copied out and loaded at run time, so the splits stay untouched).
pub const RUST_APP_ASSET: &str = "assets/spatch/libspatch_app.so";

/// The arm64 Rust app library to ship: $SPATCH_APP_SO, else app/'s release build if one exists.
fn rust_app() -> Result<Option<Vec<u8>>> {
    if let Some(path) = std::env::var_os("SPATCH_APP_SO") {
        return Ok(Some(fs::read(&path).map_err(|e| format!("SPATCH_APP_SO {}: {e}", Path::new(&path).display()))?));
    }
    let built = Path::new(env!("CARGO_MANIFEST_DIR")).join("app/target/aarch64-linux-android/release/libspatch_app.so");
    Ok(if built.is_file() { Some(fs::read(built)?) } else { None })
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
        let opts = deflated();
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
fn verify_apk(apk: &Path, tools: &Tools, zipalign: &Path, apksigner: &Path) -> Result<String> {
    tools.run(
        zipalign,
        &[
            OsStr::new("-c"),
            OsStr::new("-p"),
            OsStr::new("4"),
            apk.as_os_str(),
        ],
    )?;
    let out = tools.run(
        apksigner,
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

fn verify_identity(base: &Path, aapt2: &Path) -> Result<()> {
    let badging = tools::run(
        aapt2,
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

fn install_manifest(cert: &str) -> String {
    format!(
        r#"{{
  "package": "{PACKAGE}",
  "versionCode": {VERSION_CODE},
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
    fn refuses_existing_output_before_touching_input() {
        let d = temp_dir("exists");
        let out = d.join("out");
        std::fs::create_dir(&out).unwrap();
        let tools = Tools {
            java_home: d.clone(),
            apktool: d.clone(),
            d8: d.clone(),
            dexdump: d.clone(),
        };
        let err = run(
            &d.join("missing.apkm"),
            &out,
            Output::DryRun,
            &tools,
        )
        .unwrap_err()
        .to_string();
        assert!(err.contains("already exists"));
    }

    /// Full dry run against the supplied APKM; needs the real input and Android tools.
    #[test]
    #[ignore]
    fn dry_run_on_supplied_apkm() {
        let apkm = PathBuf::from(
            std::env::var_os("SPATCH_APKM").expect("set SPATCH_APKM to the supported .apkm"),
        );
        let before = input::sha256_file(&apkm).unwrap();
        let out = temp_dir("dry").join("out");
        run(
            &apkm,
            &out,
            Output::DryRun,
            &Tools::discover().unwrap(),
        )
        .unwrap();
        assert!(!out.exists());
        assert_eq!(input::sha256_file(&apkm).unwrap(), before);
    }
}
