mod bundle;
mod cache;
mod dex;
mod edits;
mod helpers;
mod input;
mod patch;
mod source;
mod tools;

use std::path::PathBuf;
use std::process::ExitCode;

pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

const USAGE: &str = "\
usage:
  spatch inspect <input.apkm>
  spatch patch <input.apkm> --out <new-dir> --dry-run
  spatch patch <input.apkm> --out <new-dir> --keystore <file> --alias <name> --ks-pass-env <VAR>
  spatch bundle <input.apkm> --out <new-file.zip>
  spatch source <input.apkm> --out <dir> [--index-only]

A bundle holds Spatch's changes as diffs against the APKM, for Spatch Manager to apply and sign on
the phone; it contains none of Spotify's own files.

source checks the APKM and writes it under an immutable name with an index.json beside it, for a
private Spatch source that Spatch Manager downloads from with an invite. Versions staged into the
same folder before stay listed. --index-only skips the copy, for a source that serves the APKM from
elsewhere. It publishes nothing.

The input is never modified. The keystore password is read by apksigner from the named
environment variable; spatch never reads or prints it.";

fn main() -> ExitCode {
    match run(std::env::args().skip(1).collect()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: Vec<String>) -> Result<()> {
    match args.first().map(String::as_str) {
        Some("inspect") if args.len() == 2 => inspect(&PathBuf::from(&args[1])),
        Some("source") if (4..=5).contains(&args.len()) && args[2] == "--out" => {
            let index_only = match args.get(4).map(String::as_str) {
                None => false,
                Some("--index-only") => true,
                Some(other) => return Err(format!("unknown argument {other}\n{USAGE}").into()),
            };
            let out = PathBuf::from(&args[3]);
            std::fs::create_dir_all(&out)?;
            source::stage(
                &PathBuf::from(&args[1]),
                &out,
                index_only,
                &tools::sdk_tool("aapt2")?,
            )
        }
        Some(cmd @ ("patch" | "bundle")) if args.len() >= 2 => {
            let bundle = cmd == "bundle";
            let mut out = None;
            let (mut keystore, mut alias, mut pass_env, mut dry_run) = (None, None, None, false);
            let mut it = args[2..].iter();
            while let Some(flag) = it.next() {
                let mut value = || it.next().cloned().ok_or(format!("{flag} needs a value"));
                match flag.as_str() {
                    "--out" => out = Some(PathBuf::from(value()?)),
                    "--keystore" => keystore = Some(PathBuf::from(value()?)),
                    "--alias" => alias = Some(value()?),
                    "--ks-pass-env" => pass_env = Some(value()?),
                    "--dry-run" => dry_run = true,
                    _ => return Err(format!("unknown argument {flag}\n{USAGE}").into()),
                }
            }
            let out = out.ok_or(format!("--out is required\n{USAGE}"))?;
            let signing = match (bundle, dry_run, keystore, alias, pass_env) {
                (true, false, None, None, None) => None,
                (false, true, None, None, None) => None,
                (false, false, Some(keystore), Some(alias), Some(pass_env)) => {
                    Some(patch::Signing {
                        keystore,
                        alias,
                        pass_env,
                    })
                }
                (true, ..) => {
                    return Err(
                        format!("bundle takes no signing or --dry-run flags\n{USAGE}").into(),
                    );
                }
                _ => {
                    return Err(format!(
                        "use either --dry-run or all of --keystore, --alias, --ks-pass-env\n{USAGE}"
                    )
                    .into());
                }
            };
            let output = match &signing {
                Some(s) => patch::Output::Signed(s),
                None if bundle => patch::Output::Bundle,
                None => patch::Output::DryRun,
            };
            let tools = tools::Tools::discover()?;
            patch::run(&PathBuf::from(&args[1]), &out, output, &tools)
        }
        _ => Err(USAGE.into()),
    }
}

fn inspect(apkm: &std::path::Path) -> Result<()> {
    input::check_identity(apkm)?;
    println!(
        "supported: {} {} ({})",
        input::PACKAGE,
        input::VERSION_NAME,
        input::VERSION_CODE
    );
    println!("apkm sha256: {}", input::APKM_SHA256);
    for (name, sha) in input::APKS {
        println!("  {name}  {sha}");
    }
    println!("patched DEX in base.apk:");
    for (entry, _) in &dex::DEX_TARGETS {
        println!("  {entry}");
    }
    match tools::Tools::discover() {
        Ok(t) => println!(
            "DEX tools: java={} apktool={} d8={} dexdump={}",
            t.java_home.display(),
            t.apktool.display(),
            t.d8.display(),
            t.dexdump.display()
        ),
        Err(e) => println!("DEX tools: {e}"),
    }
    for name in ["aapt2", "zipalign", "apksigner"] {
        match tools::sdk_tool(name) {
            Ok(path) => println!("{name}: {}", path.display()),
            Err(e) => println!("{e}"),
        }
    }
    Ok(())
}
