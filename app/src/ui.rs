//! The app's screens (Slint) in a GPU-rendered window on Java's surface (window.rs). Slint is
//! single-threaded: everything here runs on Android's main thread, called from SpatchApp's natives.
//! Core replies arrive on other threads and wait in `LATEST` until the next frame picks them up.

use crate::core;
use crate::images;
use crate::library::{self, Item};
use crate::player::{self, Command, NowPlaying};
use crate::window::SpatchWindow;
use slint::platform::{Platform, PointerEventButton, WindowAdapter, WindowEvent};
use slint::{LogicalPosition, ModelRc, SharedString, VecModel};
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

slint::slint! {
    export struct Row { title: string, subtitle: string, art: image, uri: string }

    component Art inherits Rectangle {
        in property <image> source;
        background: #2b2640;
        clip: true;
        Image { width: parent.width; height: parent.height; source: root.source; image-fit: cover; }
    }

    component Pill inherits Rectangle {
        in property <string> label;
        in property <bool> strong;
        callback clicked();
        height: 56px;
        border-radius: self.height / 2;
        background: strong ? (touch.pressed ? #b69df8 : #d0bcff) : (touch.pressed ? #3b3550 : #2b2640);
        Text { text: root.label; color: root.strong ? #381e72 : #e8def8; font-size: 18px; font-weight: 700; }
        touch := TouchArea { clicked => { root.clicked(); } }
    }

    component RowView inherits Rectangle {
        in property <Row> item;
        callback clicked();
        height: 68px;
        border-radius: 14px;
        background: touch.pressed ? #2b2640 : transparent;
        HorizontalLayout {
            padding: 8px;
            spacing: 14px;
            Art { width: 52px; height: 52px; border-radius: 10px; source: root.item.art; }
            VerticalLayout {
                alignment: center;
                spacing: 2px;
                Text { text: root.item.title; color: #f3eeff; font-size: 16px; font-weight: 600; overflow: elide; }
                Text { text: root.item.subtitle; color: #bcb3dc; font-size: 13px; overflow: elide; }
            }
        }
        touch := TouchArea { clicked => { root.clicked(); } }
    }

    component NavItem inherits Rectangle {
        in property <string> label;
        in property <bool> selected;
        callback clicked();
        VerticalLayout {
            alignment: center;
            Rectangle {
                height: 36px;
                width: 88px;
                border-radius: 18px;
                background: root.selected ? #4a4458 : (touch.pressed ? #2b2640 : transparent);
                Text { text: root.label; color: root.selected ? #f3eeff : #bcb3dc; font-size: 14px; font-weight: root.selected ? 700 : 500; }
            }
        }
        touch := TouchArea { clicked => { root.clicked(); } }
    }

    export component AppWindow inherits Window {
        in-out property <int> tab;
        in property <string> track: "Nothing playing";
        in property <string> artist;
        in property <string> album;
        in property <image> cover;
        in property <bool> playing;
        in property <float> progress;
        in property <string> position: "0:00";
        in property <string> duration: "0:00";
        in property <[Row]> up-next;
        in property <[Row]> library;
        in property <string> library-status: "Loading your library…";
        in property <string> status;
        callback toggle();
        callback next();
        callback previous();
        callback seek(float);
        callback play(string);
        callback library-opened();
        callback dismiss();
        background: #141218;

        VerticalLayout {
            HorizontalLayout {
                padding-left: 24px;
                padding-right: 16px;
                padding-top: 44px;
                padding-bottom: 8px;
                Text { text: root.tab == 0 ? "spatch" : "Your Library"; color: #e8def8; font-size: 26px; font-weight: 800; vertical-alignment: center; }
                Rectangle {}
                Pill { label: "Close"; width: 96px; height: 44px; clicked => { root.dismiss(); } }
            }

            if root.tab == 0: Flickable {
                content-height: page.preferred-height;
                page := VerticalLayout {
                    width: parent.width;
                    padding: 24px;
                    padding-top: 8px;
                    spacing: 18px;
                    Art { height: self.width; border-radius: 28px; source: root.cover; }
                    VerticalLayout {
                        spacing: 4px;
                        Text { text: root.track; color: #f3eeff; font-size: 24px; font-weight: 700; overflow: elide; }
                        Text { text: root.artist; color: #bcb3dc; font-size: 17px; overflow: elide; }
                    }
                    VerticalLayout {
                        spacing: 6px;
                        Rectangle {
                            height: 24px;
                            Rectangle {
                                y: (parent.height - self.height) / 2;
                                height: 6px;
                                border-radius: 3px;
                                background: #3b3550;
                                Rectangle { x: 0; width: parent.width * clamp(root.progress, 0, 1); height: parent.height; border-radius: 3px; background: #d0bcff; }
                            }
                            TouchArea { clicked => { root.seek(self.mouse-x / self.width); } }
                        }
                        HorizontalLayout {
                            Text { text: root.position; color: #bcb3dc; font-size: 13px; }
                            Rectangle {}
                            Text { text: root.duration; color: #bcb3dc; font-size: 13px; }
                        }
                    }
                    HorizontalLayout {
                        spacing: 12px;
                        Pill { label: "Prev"; clicked => { root.previous(); } }
                        Pill { label: root.playing ? "Pause" : "Play"; strong: true; clicked => { root.toggle(); } }
                        Pill { label: "Next"; clicked => { root.next(); } }
                    }
                    if root.up-next.length > 0: Text { text: "Up next"; color: #e8def8; font-size: 18px; font-weight: 700; }
                    for row in root.up-next: RowView { item: row; }
                    if root.status != "": Text { text: root.status; color: #8a83a0; font-size: 12px; wrap: word-wrap; }
                }
            }

            if root.tab == 1: VerticalLayout {
                if root.library-status != "": Text { text: root.library-status; color: #bcb3dc; font-size: 14px; wrap: word-wrap; x: 24px; }
                // shortcut: every row is built up front; fine for a few hundred, switch to a virtualised list for more.
                Flickable {
                    content-height: list.preferred-height;
                    list := VerticalLayout {
                        width: parent.width;
                        padding-left: 16px;
                        padding-right: 16px;
                        for row in root.library: RowView { item: row; clicked => { root.play(row.uri); } }
                    }
                }
            }

            HorizontalLayout {
                height: 76px;
                padding-bottom: 12px;
                NavItem { label: "Now playing"; selected: root.tab == 0; clicked => { root.tab = 0; } }
                NavItem { label: "Library"; selected: root.tab == 1; clicked => { root.tab = 1; root.library-opened(); } }
            }
        }
    }
}

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

fn rows(items: impl Iterator<Item = (String, String, String, String)>) -> ModelRc<Row> {
    let rows: Vec<Row> = items
        .map(|(title, subtitle, image, uri)| Row { title: title.into(), subtitle: subtitle.into(), art: images::get(&image, THUMB).unwrap_or_default(), uri: uri.into() })
        .collect();
    ModelRc::new(VecModel::from(rows))
}

impl App {
    /// Cover, Up Next and Library with whatever artwork has loaded so far.
    fn show_art(&self) {
        self.ui.set_cover(images::get(&self.now.image, COVER).unwrap_or_default());
        self.ui.set_up_next(rows(self.now.up_next.iter().map(|(t, a, i)| (t.clone(), a.clone(), i.clone(), String::new()))));
        self.ui.set_library(rows(self.library.iter().map(|i| (i.name.clone(), i.subtitle.clone(), i.image.clone(), i.uri.clone()))));
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

/// Opens the app's window at the screen's density and starts following the player.
pub fn open(density: f32) {
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
    ui.on_dismiss(core::close_app);
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
    APP.with(|a| *a.borrow_mut() = Some(App { window, ui, scale: density, now: NowPlaying::default(), library: Vec::new(), library_asked: false, shown_second: -1 }));
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
                app.ui.set_album(now.album.as_str().into());
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
    APP.with(|a| {
        let a = a.borrow();
        let Some(app) = a.as_ref() else { return };
        let position = LogicalPosition::new(x / app.scale, y / app.scale);
        let event = match action {
            0 | 5 => WindowEvent::PointerPressed { position, button: PointerEventButton::Left },
            1 | 6 => WindowEvent::PointerReleased { position, button: PointerEventButton::Left },
            2 => WindowEvent::PointerMoved { position },
            3 => WindowEvent::PointerExited,
            _ => return,
        };
        app.window.window().dispatch_event(event);
        if matches!(action, 1 | 3 | 6) {
            app.window.window().dispatch_event(WindowEvent::PointerExited); // no hover on a touch screen
        }
    });
}
