//! The app's Slint window. On Android it renders with Skia on the GPU (OpenGL ES through EGL) into the
//! SurfaceView's ANativeWindow, and only when Slint has asked for a redraw, so an idle screen costs
//! nothing and the display can drop its refresh rate. Host builds (tests) carry a software renderer that
//! never draws.

use slint::platform::{Renderer, WindowAdapter, WindowEvent};
use slint::{PhysicalSize, Window};
use std::cell::Cell;
use std::rc::{Rc, Weak};

pub struct SpatchWindow {
    window: Window,
    #[cfg(target_os = "android")]
    renderer: i_slint_renderer_skia::SkiaRenderer,
    #[cfg(not(target_os = "android"))]
    renderer: slint::platform::software_renderer::SoftwareRenderer,
    size: Cell<PhysicalSize>,
    redraw: Cell<bool>,
    attached: Cell<bool>,
}

impl WindowAdapter for SpatchWindow {
    fn window(&self) -> &Window {
        &self.window
    }

    fn size(&self) -> PhysicalSize {
        self.size.get()
    }

    fn renderer(&self) -> &dyn Renderer {
        &self.renderer
    }

    fn request_redraw(&self) {
        self.redraw.set(true);
    }
}

impl SpatchWindow {
    pub fn new() -> Rc<Self> {
        Rc::new_cyclic(|me: &Weak<Self>| SpatchWindow {
            window: Window::new(me.clone()),
            #[cfg(target_os = "android")]
            renderer: i_slint_renderer_skia::SkiaRenderer::default(&i_slint_renderer_skia::SkiaSharedContext::default()),
            #[cfg(not(target_os = "android"))]
            renderer: Default::default(),
            size: Cell::new(PhysicalSize::new(0, 0)),
            redraw: Cell::new(true),
            attached: Cell::new(false),
        })
    }

    /// Takes the window's new pixel size and lays the UI out for it.
    pub fn resize(&self, width: u32, height: u32) {
        let size = PhysicalSize::new(width, height);
        self.size.set(size);
        self.window.dispatch_event(WindowEvent::Resized { size: size.to_logical(self.window.scale_factor()) });
        self.redraw.set(true);
    }

    /// Whether a frame is wanted: something changed, or an animation is running.
    pub fn wants_frame(&self) -> bool {
        self.attached.get() && (self.redraw.get() || self.window.has_active_animations())
    }

    pub fn animating(&self) -> bool {
        self.window.has_active_animations()
    }

    /// Renders if Slint asked for it; false when there was nothing to draw.
    pub fn draw_if_needed(&self) -> bool {
        if !self.attached.get() || !self.redraw.replace(false) {
            return false;
        }
        #[cfg(target_os = "android")]
        if let Err(e) = self.renderer.render() {
            log::error!("render: {e}");
            return false;
        }
        true
    }

    /// Detaches from the surface: its EGL surface goes before Android takes the window back.
    pub fn detach(&self) {
        self.attached.set(false);
        #[cfg(target_os = "android")]
        let _ = self.renderer.suspend();
    }
}

#[cfg(target_os = "android")]
mod android {
    use super::SpatchWindow;
    use raw_window_handle::{AndroidDisplayHandle, AndroidNdkWindowHandle, DisplayHandle, HandleError, HasDisplayHandle, HasWindowHandle, RawDisplayHandle, RawWindowHandle, WindowHandle};
    use std::ptr::NonNull;
    use std::sync::Arc;

    /// The SurfaceView's ANativeWindow, as the handle Skia's EGL surface is made from.
    pub struct NativeWindow(pub NonNull<std::ffi::c_void>);
    // The pointer is only used on the main thread; the renderer wants Send + Sync handles.
    unsafe impl Send for NativeWindow {}
    unsafe impl Sync for NativeWindow {}

    impl HasWindowHandle for NativeWindow {
        fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
            let raw = RawWindowHandle::AndroidNdk(AndroidNdkWindowHandle::new(self.0));
            Ok(unsafe { WindowHandle::borrow_raw(raw) })
        }
    }

    pub struct Display;

    impl HasDisplayHandle for Display {
        fn display_handle(&self) -> Result<DisplayHandle<'_>, HandleError> {
            Ok(unsafe { DisplayHandle::borrow_raw(RawDisplayHandle::Android(AndroidDisplayHandle::new())) })
        }
    }

    impl SpatchWindow {
        /// Attaches to a surface (held by the caller until `detach`).
        pub fn attach(&self, window: NonNull<std::ffi::c_void>, width: u32, height: u32) -> Result<(), slint::PlatformError> {
            self.renderer.set_window_handle(Arc::new(NativeWindow(window)), Arc::new(Display), slint::PhysicalSize::new(width, height), None, false)?;
            self.attached.set(true);
            self.resize(width, height);
            Ok(())
        }
    }
}
