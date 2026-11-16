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
                let buffer = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(&rgba, w, h);
                c.insert(url, slint::Image::from_rgba8(buffer));
            } else {
                c.insert(url, slint::Image::default()); // failed: don't ask again this session
            }
        }
    });
    true
}
