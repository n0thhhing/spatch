// The app in a desktop window (demo data: no Spotify core off the phone).
fn main() {
    #[cfg(not(target_os = "android"))]
    spatch_app::app::app_main();
}
