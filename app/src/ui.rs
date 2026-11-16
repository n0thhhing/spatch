//! The app's screens (Slint) on a software-rendered window that Java's surface shows. Slint is
//! single-threaded: everything here runs on Android's main thread, called from SpatchApp's natives.
//! Core replies arrive on other threads and wait in `LATEST` until the next frame picks them up.

use crate::core;
use crate::player::{self, Command, NowPlaying};
use slint::platform::software_renderer::{MinimalSoftwareWindow, PremultipliedRgbaColor, RepaintBufferType};
use slint::platform::{Platform, PointerEventButton, WindowAdapter, WindowEvent};
use slint::{LogicalPosition, ModelRc, PhysicalSize, SharedString, VecModel};
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
    static NEW_WINDOW: RefCell<Option<Rc<MinimalSoftwareWindow>>> = const { RefCell::new(None) };
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
        let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
        NEW_WINDOW.with(|w| *w.borrow_mut() = Some(window.clone()));
        Ok(window)
    }

    fn duration_since_start(&self) -> Duration {
        self.start.elapsed()
    }
}

struct App {
    window: Rc<MinimalSoftwareWindow>,
    ui: AppWindow,
    scale: f32,
    now: NowPlaying,
}

fn status(text: impl Into<String>) {
    *STATUS.lock().unwrap() = Some(text.into());
    DIRTY.store(true, Ordering::Release);
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
    window.dispatch_event(WindowEvent::ScaleFactorChanged { scale_factor: density });
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
    APP.with(|a| *a.borrow_mut() = Some(App { window, ui, scale: density, now: NowPlaying::default() }));
    std::thread::spawn(|| {
        let id = core::subscribe(player::SERVICE, "GetState", &player::state_request(), |reply| match reply {
            Ok(bytes) => match NowPlaying::decode(bytes) {
                Ok(now) => {
                    *LATEST.lock().unwrap() = Some(now);
                    *STATUS.lock().unwrap() = Some(String::new());
                    DIRTY.store(true, Ordering::Release);
                }
                Err(e) => status(format!("Couldn't read the player state: {e}")),
            },
            Err(e) => status(format!("The player stream ended: {e}")),
        });
        match id {
            Ok(id) => STREAM.lock().unwrap().replace(id),
            Err(e) => {
                status(format!("Couldn't reach Spotify's player: {e}"));
                None
            }
        };
    });
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
    APP.with(|a| {
        if let Some(app) = a.borrow().as_ref() {
            app.window.set_size(PhysicalSize::new(width, height));
        }
    });
}

/// Once per vsync: takes in core updates, moves the progress bar and runs Slint's timers.
pub fn frame() {
    APP.with(|a| {
        let mut a = a.borrow_mut();
        let Some(app) = a.as_mut() else { return };
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
            }
            if let Some(s) = STATUS.lock().unwrap().take() {
                app.ui.set_status(s.into());
            }
        }
        let at = app.now.position_at(epoch_ms());
        app.ui.set_position(clock(at).into());
        app.ui.set_progress(if app.now.duration_ms > 0 { at as f32 / app.now.duration_ms as f32 } else { 0.0 });
    });
    slint::platform::update_timers_and_animations();
}

/// Draws into a locked surface buffer; false when nothing changed since the last draw.
pub fn render(buffer: &mut [PremultipliedRgbaColor], stride: usize) -> bool {
    APP.with(|a| {
        let a = a.borrow();
        let Some(app) = a.as_ref() else { return false };
        app.window.draw_if_needed(|renderer| {
            renderer.render(buffer, stride);
        })
    })
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
        app.window.dispatch_event(event);
        if matches!(action, 1 | 3 | 6) {
            app.window.dispatch_event(WindowEvent::PointerExited); // no hover on a touch screen
        }
    });
}
