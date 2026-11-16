//! Artwork: fetched and decoded by Android on four worker threads; the UI takes the pixels and makes
//! GPU textures of them (shaders cut the shapes). Also the pure pixel work the UI uses: a blurred
//! backdrop from a cover, and cutting an image to a circle or rounded square.

use crate::core;
use std::collections::HashSet;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Mutex, OnceLock};

const WORKERS: usize = 4;

/// The shape an image is cut to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Shape {
    /// Corners rounded by 18% of the shorter side, as the app's tiles and cards are.
    Rounded,
    /// A circle (the image is centre-cropped to a square first): artists, the player's cover.
    Circle,
}

/// (url, the decoded pixels or None when it couldn't be had).
pub type Decoded = (String, Option<(u32, u32, Vec<u8>)>);

struct Job {
    url: String,
    size: i32,
}

struct Loader {
    jobs: Mutex<Sender<Job>>,
    done: Mutex<Vec<Decoded>>,
    pending: Mutex<HashSet<String>>,
}

fn loader() -> &'static Loader {
    static LOADER: OnceLock<Loader> = OnceLock::new();
    LOADER.get_or_init(|| {
        let (tx, rx) = channel::<Job>();
        let rx = std::sync::Arc::new(Mutex::new(rx));
        for _ in 0..WORKERS {
            let rx = rx.clone();
            std::thread::spawn(move || work(&rx));
        }
        Loader { jobs: Mutex::new(tx), done: Mutex::new(Vec::new()), pending: Mutex::new(HashSet::new()) }
    })
}

fn work(rx: &Mutex<Receiver<Job>>) {
    loop {
        let Ok(job) = rx.lock().unwrap().recv() else { return };
        let image = core::image(&job.url, job.size);
        loader().done.lock().unwrap().push((job.url, image));
        core::wake();
    }
}

/// Starts loading an image at about `size` pixels (once per url).
pub fn request(url: &str, size: i32) {
    if url.is_empty() || !url.starts_with("https://") {
        return;
    }
    let l = loader();
    if l.pending.lock().unwrap().insert(url.to_string()) {
        let _ = l.jobs.lock().unwrap().send(Job { url: url.to_string(), size });
    }
}

/// Finished downloads since the last call.
pub fn take_ready() -> Vec<Decoded> {
    std::mem::take(&mut *loader().done.lock().unwrap())
}

/// The image cut to `shape`, with anti-aliased edges (pixels outside become transparent).
pub fn cut(rgba: &[u8], w: u32, h: u32, shape: Shape) -> (u32, u32, Vec<u8>) {
    let (rgba, w, h) = match shape {
        Shape::Circle if w != h => {
            let s = w.min(h);
            let (ox, oy) = ((w - s) / 2, (h - s) / 2);
            let mut sq = Vec::with_capacity((s * s * 4) as usize);
            for y in 0..s {
                let row = (((y + oy) * w + ox) * 4) as usize;
                sq.extend_from_slice(&rgba[row..row + (s * 4) as usize]);
            }
            (sq, s, s)
        }
        _ => (rgba.to_vec(), w, h),
    };
    let mut out = rgba;
    let r = match shape {
        Shape::Circle => w.min(h) as f32 / 2.0,
        Shape::Rounded => w.min(h) as f32 * 0.18,
    };
    let (fw, fh) = (w as f32, h as f32);
    for y in 0..h {
        for x in 0..w {
            // Distance outside the rounded rectangle whose corners are radius r.
            let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
            let dx = (r - px).max(px - (fw - r)).max(0.0);
            let dy = (r - py).max(py - (fh - r)).max(0.0);
            let coverage = (r - (dx * dx + dy * dy).sqrt() + 0.5).clamp(0.0, 1.0);
            if coverage < 1.0 {
                let a = &mut out[((y * w + x) * 4 + 3) as usize];
                *a = (*a as f32 * coverage).round() as u8;
            }
        }
    }
    (w, h, out)
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

#[cfg(test)]
mod tests {
    use super::{Shape, cut, shrink, smooth};

    #[test]
    fn cut_makes_circles_and_rounded_corners() {
        let rgba = [255u8; 40 * 40 * 4];
        let alpha = |img: &(u32, u32, Vec<u8>), x: u32, y: u32| img.2[((y * img.0 + x) * 4 + 3) as usize];
        let circle = cut(&rgba, 40, 40, Shape::Circle);
        assert_eq!(alpha(&circle, 0, 0), 0, "a circle's corner is clear");
        assert_eq!(alpha(&circle, 20, 20), 255, "its middle is solid");
        assert!(alpha(&circle, 20, 0) > 240, "its top edge midpoint is inside (anti-aliased edge)");
        let rounded = cut(&rgba, 40, 40, Shape::Rounded);
        assert_eq!(alpha(&rounded, 0, 0), 0);
        assert_eq!(alpha(&rounded, 20, 0), 255);
        // A wide image becomes a centred square circle.
        let wide = cut(&[255u8; 60 * 40 * 4], 60, 40, Shape::Circle);
        assert_eq!((wide.0, wide.1), (40, 40));
    }

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
