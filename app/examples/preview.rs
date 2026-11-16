//! Renders the app's screens on the desktop (software renderer) to PPM files, for checking layout
//! without a phone: cargo run --example preview -- <out-dir>
use slint::platform::software_renderer::{MinimalSoftwareWindow, RepaintBufferType};
use slint::Rgb8Pixel;
use slint::platform::{Platform, WindowAdapter};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use spatch_app::ui::{AppWindow, Row};
use std::rc::Rc;

struct Desktop(Rc<MinimalSoftwareWindow>);
impl Platform for Desktop {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
        Ok(self.0.clone())
    }
}

fn main() {
    let out = std::env::args().nth(1).unwrap_or_else(|| ".".into());
    let (w, h, scale) = (1280u32, 2856u32, 3.5f32); // Pixel Pro class panel
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    slint::platform::set_platform(Box::new(Desktop(window.clone()))).unwrap();
    let ui = AppWindow::new().unwrap();
    window.dispatch_event(slint::platform::WindowEvent::ScaleFactorChanged { scale_factor: scale });
    window.set_size(slint::PhysicalSize::new(w, h));
    ui.set_track("city".into());
    ui.set_artist("i wanna be a jack-o-lantern".into());
    ui.set_playing(true);
    ui.set_shuffle(true);
    ui.set_repeat(2);
    // A stand-in blurred cover: a 2x2 image stretched as the phone stretches the 12x12 one.
    let cover: Vec<u8> = (0..64 * 64).flat_map(|i| { let (x, y) = (i % 64, i / 64); [200 - x as u8, 40 + y as u8 * 2, 60 + (x + y) as u8, 255] }).collect();
    let (pixels, size) = spatch_app::images::backdrop(&cover, 64, 64);
    let tiny = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(&pixels, size, size);
    ui.set_backdrop(slint::Image::from_rgba8(tiny));
    ui.set_progress(0.4);
    ui.set_position("0:34".into());
    ui.set_duration("1:25".into());
    let row = |t: &str, s: &str| Row { title: t.into(), subtitle: s.into(), art: Default::default(), uri: "".into(), round: s == "Artist" };
    ui.set_up_next(ModelRc::new(VecModel::from(vec![row("annoying", "i wanna be mesmerizing to you"), row("ill die before 25", "odihu")])));
    ui.set_library(ModelRc::new(VecModel::from(vec![
        row("Liked Songs", "Playlist"), row("moron", "Playlist • levi"), row("we went no contact", "Album • pooterdabooter"), row("anxiously", "Artist"),
    ])));
    ui.set_library_status("".into());
    // A cover-coloured theme, as a red cover gives one on the phone.
    let theme = spatch_app::colors::roles(&spatch_app::colors::from_seed(0xffc0303a));
    let c = |v: u32| slint::Color::from_argb_encoded(v);
    let g = ui.global::<spatch_app::ui::Theme>();
    g.set_background(c(theme.background)); g.set_surface_container(c(theme.surface_container)); g.set_surface_container_high(c(theme.surface_container_high));
    g.set_surface_container_highest(c(theme.surface_container_highest)); g.set_on_surface(c(theme.on_surface)); g.set_on_surface_variant(c(theme.on_surface_variant));
    g.set_primary(c(theme.primary)); g.set_on_primary(c(theme.on_primary)); g.set_primary_container(c(theme.primary_container));
    g.set_secondary_container(c(theme.secondary_container)); g.set_on_secondary_container(c(theme.on_secondary_container)); g.set_outline_variant(c(theme.outline_variant));
    let _ = ui.show();
    for (tab, name) in [(1, "library"), (0, "player")] {
        ui.set_tab(tab);
        if tab == 1 { ui.set_library_filter(0); }
        slint::platform::update_timers_and_animations();
        eprintln!("{name}: window {:?}", ui.window().size());
        let mut buf = vec![Rgb8Pixel::default(); (w * h) as usize];
        window.request_redraw();
        window.draw_if_needed(|r| { r.render(&mut buf, w as usize); });
        let mut ppm = format!("P6 {w} {h} 255\n").into_bytes();
        for p in &buf { ppm.extend([p.r, p.g, p.b]); }
        std::fs::write(format!("{out}/{name}.ppm"), ppm).unwrap();
    }
    let _ = ui.get_library().row_count();
}
