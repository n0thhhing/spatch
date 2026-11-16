//! Renders the app's screens on the desktop (software renderer) to PPM files, for checking layout
//! without a phone: cargo run --example preview -- <out-dir>
use slint::platform::software_renderer::{MinimalSoftwareWindow, RepaintBufferType};
use slint::platform::{Platform, WindowAdapter};
use slint::{ComponentHandle, ModelRc, Rgb8Pixel, VecModel};
use spatch_app::ui::{AppWindow, Row};
use std::rc::Rc;

struct Desktop(Rc<MinimalSoftwareWindow>);
impl Platform for Desktop {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
        Ok(self.0.clone())
    }
}

/// A stand-in cover: a diagonal blend between two colours.
fn art(a: [u8; 3], b: [u8; 3]) -> (Vec<u8>, slint::Image) {
    let n = 64u32;
    let rgba: Vec<u8> = (0..n * n)
        .flat_map(|i| {
            let t = ((i % n) + (i / n)) as f32 / (2 * n) as f32;
            let m = |k: usize| (a[k] as f32 + (b[k] as f32 - a[k] as f32) * t) as u8;
            [m(0), m(1), m(2), 255]
        })
        .collect();
    let image = slint::Image::from_rgba8(slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(&rgba, n, n));
    (rgba, image)
}

fn main() {
    let out = std::env::args().nth(1).unwrap_or_else(|| ".".into());
    let (w, h, scale) = (1280u32, 2856u32, 3.5f32); // Pixel Pro class panel
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    slint::platform::set_platform(Box::new(Desktop(window.clone()))).unwrap();
    let ui = AppWindow::new().unwrap();
    window.dispatch_event(slint::platform::WindowEvent::ScaleFactorChanged { scale_factor: scale });
    window.set_size(slint::PhysicalSize::new(w, h));

    let (cover_px, cover) = art([200, 60, 80], [60, 30, 120]);
    let (pixels, size) = spatch_app::images::backdrop(&cover_px, 64, 64);
    ui.set_backdrop(slint::Image::from_rgba8(slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(&pixels, size, size)));
    ui.set_cover(cover.clone());
    ui.set_greeting(spatch_app::ui::greeting(1).into());
    ui.set_track("city".into());
    ui.set_artist("i wanna be a jack-o-lantern".into());
    ui.set_playing(true);
    ui.set_shuffle(true);
    ui.set_repeat(2);
    ui.set_progress(0.4);
    ui.set_position("0:34".into());
    ui.set_remaining("-0:51".into());
    ui.set_library_status("".into());
    ui.set_library_count("212 saved".into());
    let row = |t: &str, s: &str, round: bool, a: [u8; 3], b: [u8; 3]| Row { title: t.into(), subtitle: s.into(), art: art(a, b).1, uri: "".into(), round };
    ui.set_up_next(ModelRc::new(VecModel::from(vec![row("annoying", "i wanna be mesmerizing to you", false, [90, 140, 90], [30, 60, 40])])));
    ui.set_playlists(ModelRc::new(VecModel::from(vec![
        row("Liked Songs", "Playlist", false, [90, 60, 220], [180, 160, 255]),
        row("moron", "Playlist • levi", false, [230, 230, 230], [120, 20, 30]),
        row("Ear buzz", "Playlist • levi", false, [240, 230, 210], [90, 90, 90]),
    ])));
    ui.set_albums(ModelRc::new(VecModel::from(vec![row("we went no contact", "Album • pooterdabooter", false, [40, 60, 160], [230, 230, 250]), row("altered", "Album", false, [200, 60, 60], [30, 10, 10])])));
    ui.set_artists(ModelRc::new(VecModel::from(vec![row("anxiously", "Artist", true, [220, 80, 140], [40, 10, 40]), row("odihu", "Artist", true, [60, 60, 90], [10, 10, 20])])));
    // A cover-coloured theme, as a red cover gives one on the phone.
    let theme = spatch_app::colors::roles(&spatch_app::colors::from_seed(0xffc0507a));
    let c = |v: u32| slint::Color::from_argb_encoded(v);
    let g = ui.global::<spatch_app::ui::Theme>();
    g.set_background(c(theme.background)); g.set_surface_container(c(theme.surface_container)); g.set_surface_container_high(c(theme.surface_container_high));
    g.set_surface_container_highest(c(theme.surface_container_highest)); g.set_on_surface(c(theme.on_surface)); g.set_on_surface_variant(c(theme.on_surface_variant));
    g.set_primary(c(theme.primary)); g.set_on_primary(c(theme.on_primary)); g.set_primary_container(c(theme.primary_container)); g.set_on_primary_container(c(theme.on_primary_container));
    g.set_secondary_container(c(theme.secondary_container)); g.set_on_secondary_container(c(theme.on_secondary_container)); g.set_outline_variant(c(theme.outline_variant));
    let _ = ui.show();
    for (tab, name) in [(0, "home"), (1, "player")] {
        ui.set_tab(tab);
        slint::platform::update_timers_and_animations();
        let mut buf = vec![Rgb8Pixel::default(); (w * h) as usize];
        window.request_redraw();
        window.draw_if_needed(|r| {
            r.render(&mut buf, w as usize);
        });
        let mut ppm = format!("P6 {w} {h} 255\n").into_bytes();
        for p in &buf {
            ppm.extend([p.r, p.g, p.b]);
        }
        std::fs::write(format!("{out}/{name}.ppm"), ppm).unwrap();
    }
}
