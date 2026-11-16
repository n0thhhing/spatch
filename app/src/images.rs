//! Artwork: fetched and decoded by Android on four worker threads, turned into Slint images on the UI
//! thread (slint::Image isn't Send) and kept for the session.

use crate::core;
use std::collections::{HashMap, HashSet};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Mutex, OnceLock};

const WORKERS: usize = 4;

type Decoded = (String, Option<(u32, u32, Vec<u8>)>);

struct Loader {
    jobs: Mutex<Sender<(String, i32)>>,
    done: Mutex<Vec<Decoded>>,
    pending: Mutex<HashSet<String>>,
}

fn loader() -> &'static Loader {
    static LOADER: OnceLock<Loader> = OnceLock::new();
    LOADER.get_or_init(|| {
        let (tx, rx) = channel::<(String, i32)>();
        let rx = std::sync::Arc::new(Mutex::new(rx));
        for _ in 0..WORKERS {
            let rx = rx.clone();
            std::thread::spawn(move || work(&rx));
        }
        Loader { jobs: Mutex::new(tx), done: Mutex::new(Vec::new()), pending: Mutex::new(HashSet::new()) }
    })
}

fn work(rx: &Mutex<Receiver<(String, i32)>>) {
    loop {
        let Ok((url, size)) = rx.lock().unwrap().recv() else { return };
        let image = core::image(&url, size);
        loader().done.lock().unwrap().push((url, image));
        core::wake();
    }
}

thread_local! {
    static CACHE: std::cell::RefCell<HashMap<String, slint::Image>> = Default::default();
    /// Each loaded image's seed colour (colors::seed), for art-coloured screens.
    static SEEDS: std::cell::RefCell<HashMap<String, Option<u32>>> = Default::default();
    /// A 12x12 copy of each image: stretched across the screen it is the blurred backdrop
    /// (Spatch's player trick: a tiny raster, scaled up smoothly, is the blur).
    static TINY: std::cell::RefCell<HashMap<String, slint::Image>> = Default::default();
}

const TINY_SIZE: u32 = 12;
/// The backdrop is the 12x12 copy smoothed up to this size here, so it looks blurred on any renderer.
const BACKDROP_SIZE: u32 = 48;

/// Bilinear upscale of an `n` x `n` RGBA image to `m` x `m`.
pub fn smooth(rgba: &[u8], n: u32, m: u32) -> Vec<u8> {
    let mut out = vec![0u8; (m * m * 4) as usize];
    let at = |x: u32, y: u32, c: usize| rgba[((y.min(n - 1) * n + x.min(n - 1)) * 4) as usize + c] as f32;
    for y in 0..m {
        let fy = (y as f32 + 0.5) * n as f32 / m as f32 - 0.5;
        let (y0, ty) = (fy.max(0.0).floor() as u32, fy.max(0.0).fract());
        for x in 0..m {
            let fx = (x as f32 + 0.5) * n as f32 / m as f32 - 0.5;
            let (x0, tx) = (fx.max(0.0).floor() as u32, fx.max(0.0).fract());
            for c in 0..4 {
                let top = at(x0, y0, c) * (1.0 - tx) + at(x0 + 1, y0, c) * tx;
                let bottom = at(x0, y0 + 1, c) * (1.0 - tx) + at(x0 + 1, y0 + 1, c) * tx;
                out[((y * m + x) * 4) as usize + c] = (top * (1.0 - ty) + bottom * ty).round() as u8;
            }
        }
    }
    out
}

/// A cover's blurred backdrop pixels: shrunk to 12x12, then smoothed up.
pub fn backdrop(rgba: &[u8], w: u32, h: u32) -> (Vec<u8>, u32) {
    (smooth(&shrink(rgba, w, h, TINY_SIZE), TINY_SIZE, BACKDROP_SIZE), BACKDROP_SIZE)
}

/// The image's blurred backdrop, once it has loaded.
pub fn blurred(url: &str) -> Option<slint::Image> {
    TINY.with(|t| t.borrow().get(url).cloned())
}

/// Box-averages RGBA down to `n` x `n`.
pub fn shrink(rgba: &[u8], w: u32, h: u32, n: u32) -> Vec<u8> {
    let mut out = vec![0u8; (n * n * 4) as usize];
    for ty in 0..n {
        for tx in 0..n {
            let (x0, x1) = (tx * w / n, ((tx + 1) * w / n).max(tx * w / n + 1));
            let (y0, y1) = (ty * h / n, ((ty + 1) * h / n).max(ty * h / n + 1));
            let mut sum = [0u32; 4];
            let mut count = 0;
            for y in (y0..y1.min(h)).step_by(2) {
                for x in (x0..x1.min(w)).step_by(2) {
                    let i = ((y * w + x) * 4) as usize;
                    for c in 0..4 {
                        sum[c] += rgba[i + c] as u32;
                    }
                    count += 1;
                }
            }
            let o = ((ty * n + tx) * 4) as usize;
            for c in 0..4 {
                out[o + c] = (sum[c] / count.max(1)) as u8;
            }
        }
    }
    out
}

/// The seed colour of a loaded image: Some(None) for a grey one, None if it hasn't loaded.
pub fn seed(url: &str) -> Option<Option<u32>> {
    SEEDS.with(|s| s.borrow().get(url).copied())
}

/// The image if it's loaded; otherwise starts loading it (once) and returns None.
pub fn get(url: &str, size: i32) -> Option<slint::Image> {
    if url.is_empty() || !url.starts_with("https://") {
        return None;
    }
    if let Some(image) = CACHE.with(|c| c.borrow().get(url).cloned()) {
        return Some(image);
    }
    let l = loader();
    if l.pending.lock().unwrap().insert(url.to_string()) {
        let _ = l.jobs.lock().unwrap().send((url.to_string(), size));
    }
    None
}

/// Takes in finished downloads (UI thread); true when any arrived, so views can pick them up.
pub fn collect() -> bool {
    let done = std::mem::take(&mut *loader().done.lock().unwrap());
    if done.is_empty() {
        return false;
    }
    CACHE.with(|c| {
        let mut c = c.borrow_mut();
        for (url, image) in done {
            loader().pending.lock().unwrap().remove(&url);
            if let Some((w, h, rgba)) = image {
                SEEDS.with(|s| s.borrow_mut().insert(url.clone(), crate::colors::seed(&rgba)));
                let (tiny, size) = backdrop(&rgba, w, h);
                let tiny = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(&tiny, size, size);
                TINY.with(|t| t.borrow_mut().insert(url.clone(), slint::Image::from_rgba8(tiny)));
                let buffer = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(&rgba, w, h);
                c.insert(url, slint::Image::from_rgba8(buffer));
            } else {
                c.insert(url, slint::Image::default()); // failed: don't ask again this session
            }
        }
    });
    true
}

#[cfg(test)]
mod tests {
    use super::{shrink, smooth};

    #[test]
    fn smooth_blends_between_pixels() {
        // 2x1 black to white, smoothed to 4x4: the middle columns are greys, the ends keep their colours.
        let rgba = [0u8, 0, 0, 255, 255, 255, 255, 255, 0, 0, 0, 255, 255, 255, 255, 255];
        let out = smooth(&rgba, 2, 4);
        assert_eq!(out[0], 0);
        assert!(out[4] > 0 && out[4] < 255);
        assert_eq!(out[12], 255);
    }

    #[test]
    fn shrink_averages_blocks() {
        // 4x2: left half red, right half blue -> 2x1 gives one red and one blue pixel.
        let mut rgba = Vec::new();
        for _ in 0..2 {
            rgba.extend([255, 0, 0, 255, 255, 0, 0, 255, 0, 0, 255, 255, 0, 0, 255, 255]);
        }
        assert_eq!(shrink(&rgba, 4, 2, 2)[..4], [255, 0, 0, 255]);
        assert_eq!(shrink(&rgba, 4, 2, 2)[4..8], [0, 0, 255, 255]);
    }
}
