//! The app's screens (Slint) in a GPU-rendered window on Java's surface (window.rs). Slint is
//! single-threaded: everything here runs on Android's main thread, called from SpatchApp's natives.
//! Core replies arrive on other threads and wait in `LATEST` until the next frame picks them up.

use crate::core;
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
    struct Row { title: string, artist: string }

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

    export component AppWindow inherits Window {
        in property <string> track: "Nothing playing";
        in property <string> artist;
        in property <string> album;
        in property <bool> playing;
        in property <float> progress;
        in property <string> position: "0:00";
        in property <string> duration: "0:00";
        in property <[Row]> up-next;
        in property <string> status;
        callback toggle();
        callback next();
        callback previous();
        callback dismiss();
        background: #141218;

        VerticalLayout {
            padding: 24px;
            padding-top: 48px;
            spacing: 18px;

            HorizontalLayout {
                Text { text: "spatch"; color: #e8def8; font-size: 26px; font-weight: 800; vertical-alignment: center; }
                Rectangle {}
                Pill { label: "Close"; width: 96px; height: 44px; clicked => { root.dismiss(); } }
            }

            // Cover placeholder until images load (Phase 3).
            Rectangle {
                height: self.width;
                border-radius: 28px;
                background: @linear-gradient(135deg, #4f378b 0%, #2b2640 100%);
                Text { text: "♪"; color: #d0bcff; font-size: 96px; }
            }

            VerticalLayout {
                spacing: 4px;
                Text { text: root.track; color: #f3eeff; font-size: 24px; font-weight: 700; overflow: elide; }
                Text { text: root.artist; color: #bcb3dc; font-size: 17px; overflow: elide; }
            }

            VerticalLayout {
                spacing: 6px;
                Rectangle {
                    height: 6px;
                    border-radius: 3px;
                    background: #3b3550;
                    Rectangle { x: 0; width: parent.width * clamp(root.progress, 0, 1); height: parent.height; border-radius: 3px; background: #d0bcff; }
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

            Text { text: "Up next"; color: #e8def8; font-size: 18px; font-weight: 700; }
            for row in root.up-next: VerticalLayout {
                Text { text: row.title; color: #f3eeff; font-size: 16px; overflow: elide; }
                Text { text: row.artist; color: #bcb3dc; font-size: 13px; overflow: elide; }
            }
            Rectangle {}
            Text { text: root.status; color: #8a83a0; font-size: 12px; wrap: word-wrap; }
        }
    }
}

thread_local! {
    static APP: RefCell<Option<App>> = const { RefCell::new(None) };
    static NEW_WINDOW: RefCell<Option<Rc<SpatchWindow>>> = const { RefCell::new(None) };
}
static LATEST: Mutex<Option<NowPlaying>> = Mutex::new(None);
static STATUS: Mutex<Option<String>> = Mutex::new(None);
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
    ui.on_next(|| send(Command::Next));
    ui.on_previous(|| send(Command::Previous));
    let weak = ui.as_weak();
    ui.on_toggle(move || {
        let playing = weak.upgrade().map(|u| u.get_playing()).unwrap_or(false);
        send(if playing { Command::Pause } else { Command::Resume });
    });
    let _ = ui.show();
    ui.set_status("Connecting to Spotify's player…".into());
    APP.with(|a| *a.borrow_mut() = Some(App { window, ui, scale: density, now: NowPlaying::default(), shown_second: -1 }));
    std::thread::spawn(|| {
        // Opened at launch, Spotify may not have called its player yet: retry briefly until the bridge has it.
        let mut tries = 0;
        let id = loop {
            let id = subscribe_player();
            match &id {
                Err(e) if e.contains("isn't running yet") && tries < 40 => {
                    tries += 1;
                    std::thread::sleep(Duration::from_millis(250));
                }
                _ => break id,
            }
        };
        match id {
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
                let rows: Vec<Row> = now.up_next.iter().map(|(t, a)| Row { title: t.as_str().into(), artist: a.as_str().into() }).collect();
                app.ui.set_up_next(ModelRc::new(VecModel::from(rows)));
                app.now = now;
                app.shown_second = -1;
            }
            if let Some(s) = STATUS.lock().unwrap().take() {
                app.ui.set_status(s.into());
            }
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
