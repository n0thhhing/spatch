//! The app's screens (Slint) in a GPU-rendered window on Java's surface (window.rs). Slint is
//! single-threaded: everything here runs on Android's main thread, called from SpatchApp's natives.
//! Core replies arrive on other threads and wait in `LATEST` until the next frame picks them up.

use crate::colors;
use crate::core;
use crate::images;
use crate::library::{self, Item};
use crate::player::{self, Command, NowPlaying};
use crate::window::SpatchWindow;
use slint::platform::{Platform, PointerEventButton, WindowAdapter, WindowEvent};
use slint::{ComponentHandle, LogicalPosition, ModelRc, SharedString, VecModel};
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

// The screens live in app/ui/*.slint (compiled by build.rs): Material 3 Expressive, the Pixel look.
slint::include_modules!();

thread_local! {
    static APP: RefCell<Option<App>> = const { RefCell::new(None) };
    static NEW_WINDOW: RefCell<Option<Rc<SpatchWindow>>> = const { RefCell::new(None) };
}
static LATEST: Mutex<Option<NowPlaying>> = Mutex::new(None);
static STATUS: Mutex<Option<String>> = Mutex::new(None);
static LIBRARY: Mutex<Option<Result<Vec<Item>, String>>> = Mutex::new(None);
static DIRTY: AtomicBool = AtomicBool::new(false);
static PLATFORM_SET: AtomicBool = AtomicBool::new(false);

struct AndroidPlatform {
    start: Instant,
}

impl Platform for AndroidPlatform {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
        let window = SpatchWindow::new();
        NEW_WINDOW.with(|w| *w.borrow_mut() = Some(window.clone()));
        Ok(window)
    }

    fn duration_since_start(&self) -> Duration {
        self.start.elapsed()
    }
}

struct App {
    window: Rc<SpatchWindow>,
    ui: AppWindow,
    scale: f32,
    now: NowPlaying,
    library: Vec<Item>,
    library_asked: bool,
    /// The selected chip (playlists, albums, artists, podcasts), or -1 for everything.
    filter: i32,
    /// The wallpaper's (or built-in) colours, and the ones on screen now (the cover's when it has some).
    base_theme: [u32; 9],
    shown_theme: [u32; 9],
    /// The second the clock last showed: the progress moves once a second, not every frame.
    shown_second: i64,
}

fn status(text: impl Into<String>) {
    *STATUS.lock().unwrap() = Some(text.into());
    DIRTY.store(true, Ordering::Release);
    core::wake();
}

fn send(c: Command) {
    std::thread::spawn(move || {
        let (method, request) = player::command(c);
        if let Err(e) = core::call(player::SERVICE, method, &request) {
            status(format!("{method} failed: {e}"));
        }
    });
}

/// Retries while Spotify hasn't made its first call to a service yet (the app opened at launch).
fn when_ready<T>(f: impl Fn() -> Result<T, String>) -> Result<T, String> {
    let mut tries = 0;
    loop {
        match f() {
            Err(e) if e.contains("isn't running yet") && tries < 40 => {
                tries += 1;
                std::thread::sleep(Duration::from_millis(250));
            }
            r => return r,
        }
    }
}

const COVER: i32 = 1200;
const THUMB: i32 = 160;

fn rows(items: impl Iterator<Item = (String, String, String, String, bool)>) -> ModelRc<Row> {
    let rows: Vec<Row> = items
        .map(|(title, subtitle, image, uri, round)| Row { title: title.into(), subtitle: subtitle.into(), art: images::get(&image, THUMB).unwrap_or_default(), uri: uri.into(), round })
        .collect();
    ModelRc::new(VecModel::from(rows))
}

impl App {
    /// Cover, Up Next and Library with whatever artwork has loaded so far.
    fn show_art(&mut self) {
        self.ui.set_cover(images::get(&self.now.image, COVER).unwrap_or_default());
        // Like Pixel's player, the screen takes its colours from the cover; grey covers keep the wallpaper's.
        let theme = match images::seed(&self.now.image) {
            Some(Some(seed)) => colors::from_seed(seed),
            _ => self.base_theme,
        };
        if theme != self.shown_theme {
            self.shown_theme = theme;
            apply_roles(&self.ui, &theme);
        }
        self.ui.set_up_next(rows(self.now.up_next.iter().map(|(t, a, i)| (t.clone(), a.clone(), i.clone(), String::new(), false))));
        let filter = self.filter;
        let shown = self.library.iter().filter(|i| filter < 0 || i.kind.chip() == Some(filter));
        self.ui.set_library(rows(shown.map(|i| (i.name.clone(), i.subtitle.clone(), i.image.clone(), i.uri.clone(), i.kind == library::Kind::Artist))));
    }
}

fn load_library() {
    std::thread::spawn(|| {
        let result = when_ready(|| core::call(library::SERVICE, "All", &library::request(300))).and_then(|bytes| library::decode(&bytes));
        *LIBRARY.lock().unwrap() = Some(result);
        DIRTY.store(true, Ordering::Release);
        core::wake();
    });
}

fn clock(ms: i64) -> String {
    let s = ms.max(0) / 1000;
    format!("{}:{:02}", s / 60, s % 60)
}

fn epoch_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// The look the app opens with: the Spatch theme's nine colours and the system bars' heights (px).
pub struct Look {
    pub density: f32,
    pub theme: [u32; 9],
    pub inset_top: f32,
    pub inset_bottom: f32,
}

fn apply_roles(ui: &AppWindow, theme: &[u32; 9]) {
    let r = colors::roles(theme);
    let c = |argb: u32| slint::Color::from_argb_encoded(argb);
    let t = ui.global::<Theme>();
    t.set_background(c(r.background));
    t.set_surface_container(c(r.surface_container));
    t.set_surface_container_high(c(r.surface_container_high));
    t.set_surface_container_highest(c(r.surface_container_highest));
    t.set_on_surface(c(r.on_surface));
    t.set_on_surface_variant(c(r.on_surface_variant));
    t.set_primary(c(r.primary));
    t.set_on_primary(c(r.on_primary));
    t.set_primary_container(c(r.primary_container));
    t.set_on_primary_container(c(r.on_primary_container));
    t.set_secondary_container(c(r.secondary_container));
    t.set_on_secondary_container(c(r.on_secondary_container));
    t.set_outline(c(r.outline));
    t.set_outline_variant(c(r.outline_variant));
}

fn apply_insets(ui: &AppWindow, look: &Look) {
    let t = ui.global::<Theme>();
    t.set_inset_top(look.inset_top / look.density);
    t.set_inset_bottom(look.inset_bottom / look.density);
}

/// Opens the app's window at the screen's density and starts following the player.
pub fn open(look: Look) {
    let density = look.density;
    if !PLATFORM_SET.swap(true, Ordering::AcqRel) {
        if let Err(e) = slint::platform::set_platform(Box::new(AndroidPlatform { start: Instant::now() })) {
            log::error!("set_platform: {e}");
        }
    }
    let ui = match AppWindow::new() {
        Ok(ui) => ui,
        Err(e) => {
            log::error!("AppWindow: {e}");
            return;
        }
    };
    let Some(window) = NEW_WINDOW.with(|w| w.borrow_mut().take()) else { return };
    window.window().dispatch_event(WindowEvent::ScaleFactorChanged { scale_factor: density });
    apply_roles(&ui, &look.theme);
    apply_insets(&ui, &look);
    ui.on_dismiss(core::close_app);
    ui.on_filter_changed(|filter| {
        APP.with(|a| {
            if let Some(app) = a.borrow_mut().as_mut() {
                app.filter = filter;
                app.show_art();
            }
        })
    });
    ui.on_play(|uri| send(Command::Play(uri.to_string())));
    ui.on_seek(|fraction| {
        APP.with(|a| {
            if let Some(app) = a.borrow().as_ref() {
                if app.now.duration_ms > 0 {
                    send(Command::SeekTo((fraction.clamp(0.0, 1.0) as f64 * app.now.duration_ms as f64) as i64));
                }
            }
        })
    });
    ui.on_library_opened(|| {
        APP.with(|a| {
            if let Some(app) = a.borrow_mut().as_mut() {
                if !app.library_asked {
                    app.library_asked = true;
                    load_library();
                }
            }
        })
    });
    ui.on_next(|| send(Command::Next));
    ui.on_previous(|| send(Command::Previous));
    let weak = ui.as_weak();
    ui.on_toggle(move || {
        let playing = weak.upgrade().map(|u| u.get_playing()).unwrap_or(false);
        send(if playing { Command::Pause } else { Command::Resume });
    });
    let _ = ui.show();
    ui.set_status("Connecting to Spotify's player…".into());
    APP.with(|a| *a.borrow_mut() = Some(App { window, ui, scale: density, now: NowPlaying::default(), library: Vec::new(), library_asked: false, filter: -1, base_theme: look.theme, shown_theme: look.theme, shown_second: -1 }));
    std::thread::spawn(|| {
        match when_ready(subscribe_player) {
            Ok(id) => STREAM.lock().unwrap().replace(id),
            Err(e) => {
                status(format!("Couldn't reach Spotify's player: {e}"));
                None
            }
        };
    });
}

fn subscribe_player() -> Result<i64, String> {
    {
        core::subscribe(player::SERVICE, "GetState", &player::state_request(), |reply| match reply {
            Ok(bytes) => match NowPlaying::decode(bytes) {
                Ok(now) => {
                    *LATEST.lock().unwrap() = Some(now);
                    *STATUS.lock().unwrap() = Some(String::new());
                    DIRTY.store(true, Ordering::Release);
                    core::wake();
                }
                Err(e) => status(format!("Couldn't read the player state: {e}")),
            },
            Err(e) => status(format!("The player stream ended: {e}")),
        })
    }
}

static STREAM: Mutex<Option<i64>> = Mutex::new(None);

pub fn close() {
    if let Some(id) = STREAM.lock().unwrap().take() {
        std::thread::spawn(move || core::cancel(id));
    }
    APP.with(|a| {
        if let Some(app) = a.borrow_mut().take() {
            let _ = app.ui.hide();
        }
    });
}

pub fn resize(width: u32, height: u32) {
    with_window(|w| w.resize(width, height));
}

/// The app's window, if open.
pub fn with_window<R>(f: impl FnOnce(&SpatchWindow) -> R) -> Option<R> {
    APP.with(|a| a.borrow().as_ref().map(|app| f(&app.window)))
}

/// Once per wanted frame: takes in core updates, moves the clock, runs Slint's timers and draws if
/// anything changed. Returns when the next frame is wanted: 0 next vsync (animating), a delay in ms
/// (the clock's next second while playing), or -1 when nothing will change until woken.
pub fn frame() -> i64 {
    let next = APP.with(|a| {
        let mut a = a.borrow_mut();
        let Some(app) = a.as_mut() else { return -1 };
        if DIRTY.swap(false, Ordering::AcqRel) {
            if let Some(now) = LATEST.lock().unwrap().take() {
                app.ui.set_track(if now.title.is_empty() { "Nothing playing".into() } else { SharedString::from(now.title.as_str()) });
                app.ui.set_artist(now.artist.as_str().into());
                app.ui.set_playing(now.playing);
                app.ui.set_duration(clock(now.duration_ms).into());
                app.now = now;
                app.shown_second = -1;
                app.show_art();
            }
            match LIBRARY.lock().unwrap().take() {
                Some(Ok(items)) => {
                    app.ui.set_library_status(if items.is_empty() { "Nothing in your library yet.".into() } else { SharedString::new() });
                    app.library = items;
                    app.show_art();
                }
                Some(Err(e)) => app.ui.set_library_status(format!("Couldn't load your library: {e}").into()),
                None => {}
            }
            if let Some(s) = STATUS.lock().unwrap().take() {
                app.ui.set_status(s.into());
            }
        }
        if images::collect() {
            app.show_art();
        }
        let at = app.now.position_at(epoch_ms());
        if at / 1000 != app.shown_second {
            app.shown_second = at / 1000;
            app.ui.set_position(clock(at).into());
            app.ui.set_progress(if app.now.duration_ms > 0 { at as f32 / app.now.duration_ms as f32 } else { 0.0 });
        }
        if app.now.playing { 1000 - at % 1000 } else { -1 }
    });
    slint::platform::update_timers_and_animations();
    with_window(|w| {
        w.draw_if_needed();
        if w.animating() || w.wants_frame() { 0 } else { next }
    })
    .unwrap_or(-1)
}

/// MotionEvent's masked action and position in pixels.
pub fn touch(action: i32, x: f32, y: f32) {
    // Take the window out before dispatching: Slint runs callbacks (Library, the chips) inside
    // dispatch_event, and those borrow the app mutably; holding a borrow here panicked.
    let Some((window, scale)) = APP.with(|a| a.borrow().as_ref().map(|app| (app.window.clone(), app.scale))) else { return };
    let position = LogicalPosition::new(x / scale, y / scale);
    let event = match action {
        0 | 5 => WindowEvent::PointerPressed { position, button: PointerEventButton::Left },
        1 | 6 => WindowEvent::PointerReleased { position, button: PointerEventButton::Left },
        2 => WindowEvent::PointerMoved { position },
        3 => WindowEvent::PointerExited,
        _ => return,
    };
    window.window().dispatch_event(event);
    if matches!(action, 1 | 3 | 6) {
        window.window().dispatch_event(WindowEvent::PointerExited); // no hover on a touch screen
    }
}
