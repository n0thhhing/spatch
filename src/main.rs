mod arsc;
mod bundle;
mod dex;
mod edits;
mod fonts;
mod helpers;
mod input;
mod lottie;
mod patch;
mod source;
mod theme;
mod theme_assets;
mod tools;

use std::path::PathBuf;
use std::process::ExitCode;

pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

const USAGE: &str = "\
usage:
  spatch inspect <input.apkm>
  spatch patch <input.apkm> --out <new-dir> [--theme <name>] [--debug] --dry-run
  spatch patch <input.apkm> --out <new-dir> [--theme <name>] [--debug] --keystore <file> --alias <name> --ks-pass-env <VAR>
  spatch bundle <input.apkm> --out <new-file.zip> [--theme <name>] [--debug]
  spatch source <input.apkm> --out <dir>

Themes: lavender (default), gruvbox, catppuccin-mocha, rose-pine, nord, tokyo-night, dracula,
gruvbox-material-dark.

--debug adds Developer options to Spatch settings: on-device tests, logs and inspection tools.
Other builds contain none of that code.

A bundle holds Spatch's changes as diffs against the APKM, for Spatch Manager to apply and sign on
the phone; it contains none of Spotify's own files.

source checks the APKM and writes it under an immutable name with an index.json beside it, for a
private Spatch source that Spatch Manager downloads from with an invite. It publishes nothing.

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
        Some("source") if args.len() == 4 && args[2] == "--out" => {
            let out = PathBuf::from(&args[3]);
            std::fs::create_dir_all(&out)?;
            source::stage(&PathBuf::from(&args[1]), &out, &tools::Tools::discover()?)
        }
        Some(cmd @ ("patch" | "bundle")) if args.len() >= 2 => {
            let bundle = cmd == "bundle";
            let mut out = None;
            let (mut keystore, mut alias, mut pass_env, mut dry_run) = (None, None, None, false);
            let mut debug = false;
            let mut theme = theme::by_name("lavender")?;
            let mut it = args[2..].iter();
            while let Some(flag) = it.next() {
                let mut value = || it.next().cloned().ok_or(format!("{flag} needs a value"));
                match flag.as_str() {
                    "--out" => out = Some(PathBuf::from(value()?)),
                    "--keystore" => keystore = Some(PathBuf::from(value()?)),
                    "--alias" => alias = Some(value()?),
                    "--ks-pass-env" => pass_env = Some(value()?),
                    "--dry-run" => dry_run = true,
                    "--debug" => debug = true,
                    "--theme" => theme = theme::by_name(&value()?)?,
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
            patch::run(&PathBuf::from(&args[1]), &out, output, &tools, theme, debug)
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
    println!("patch targets in base.apk:");
    for t in &patch::TARGETS {
        println!("  {}", t.entry);
    }
    match tools::Tools::discover() {
        Ok(t) => println!(
            "tools: java={} apktool={} aapt2={} zipalign={} apksigner={}",
            t.java_home.display(),
            t.apktool.display(),
            t.aapt2.display(),
            t.zipalign.display(),
            t.apksigner.display()
        ),
        Err(e) => println!("tools: {e}"),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn usage_lists_every_theme() {
        for t in &crate::theme::THEMES {
            assert!(super::USAGE.contains(t.name), "{}", t.name);
        }
    }
}
