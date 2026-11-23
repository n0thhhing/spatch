//! Direct PCM tap for the visualizer without `RECORD_AUDIO`: hooks `liborbit-jni-spotify.so`'s `dlsym`
//! to intercept Oboe's `AAudio` callbacks and feeds the shared `Analyzer` via a wait-free ring buffer.

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray};
use jni::sys::{JNI_FALSE, jboolean, jfloat, jlong};
#[cfg(not(feature = "preview"))]
use jni::sys::JNI_TRUE;
use std::f32::consts::PI;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, AtomicUsize, Ordering};
#[cfg(not(feature = "preview"))]
use std::sync::Mutex;

pub const FFT_N: usize = 1024;
pub const RING_N: usize = 4096;

/// Wait-free circular buffer of mono `f32` PCM samples written by the audio thread and read by the worker thread.
pub struct PcmRing {
    samples: [AtomicU32; RING_N],
    write_pos: AtomicUsize,
    seq: AtomicU64,
    sample_rate: AtomicI32,
}

impl Default for PcmRing {
    fn default() -> Self {
        Self::new()
    }
}

impl PcmRing {
    pub const fn new() -> Self {
        Self {
            samples: [const { AtomicU32::new(0) }; RING_N],
            write_pos: AtomicUsize::new(0),
            seq: AtomicU64::new(0),
            sample_rate: AtomicI32::new(44_100),
        }
    }

    fn push_frames(&self, frames: usize, rate: i32, mut mono_at: impl FnMut(usize) -> f32) {
        if frames == 0 {
            return;
        }
        let skip = frames.saturating_sub(RING_N);
        let mut pos = self.write_pos.load(Ordering::Relaxed);
        for frame in skip..frames {
            self.samples[pos].store(mono_at(frame).to_bits(), Ordering::Relaxed);
            pos = (pos + 1) & (RING_N - 1);
        }
        self.write_pos.store(pos, Ordering::Release);
        if rate > 0 {
            self.sample_rate.store(rate, Ordering::Relaxed);
        }
        self.seq.fetch_add(1, Ordering::Release);
    }

    pub fn push_i16(&self, pcm: &[i16], channels: usize, rate: i32) {
        let ch = channels.max(1);
        let frames = pcm.len() / ch;
        self.push_frames(frames, rate, |f| {
            let base = f * ch;
            pcm[base..base + ch].iter().map(|&s| s as f32 / 32_768.0).sum::<f32>() / ch as f32
        });
    }

    pub fn push_f32(&self, pcm: &[f32], channels: usize, rate: i32) {
        let ch = channels.max(1);
        let frames = pcm.len() / ch;
        self.push_frames(frames, rate, |f| {
            let base = f * ch;
            pcm[base..base + ch].iter().copied().sum::<f32>() / ch as f32
        });
    }

    pub fn push_i32(&self, pcm: &[i32], channels: usize, rate: i32) {
        let ch = channels.max(1);
        let frames = pcm.len() / ch;
        self.push_frames(frames, rate, |f| {
            let base = f * ch;
            pcm[base..base + ch].iter().map(|&s| s as f32 / 2_147_483_648.0).sum::<f32>() / ch as f32
        });
    }

    pub fn seq(&self) -> u64 {
        self.seq.load(Ordering::Acquire)
    }

    pub fn snapshot(&self, out: &mut [f32; FFT_N]) -> (u64, i32) {
        let seq = self.seq.load(Ordering::Acquire);
        let wp = self.write_pos.load(Ordering::Acquire);
        let rate = self.sample_rate.load(Ordering::Relaxed);
        let start = (wp + RING_N - FFT_N) & (RING_N - 1);
        for (i, slot) in out.iter_mut().enumerate() {
            *slot = f32::from_bits(self.samples[(start + i) & (RING_N - 1)].load(Ordering::Relaxed));
        }
        (seq, rate)
    }
}

struct FftTables {
    hann: [f32; FFT_N],
    cos: [f32; FFT_N / 2],
    sin: [f32; FFT_N / 2],
}

fn fft_tables() -> &'static FftTables {
    static TABLES: OnceLock<FftTables> = OnceLock::new();
    TABLES.get_or_init(|| {
        let mut hann = [0.0f32; FFT_N];
        let mut cos = [0.0f32; FFT_N / 2];
        let mut sin = [0.0f32; FFT_N / 2];
        for (i, w) in hann.iter_mut().enumerate() {
            *w = 0.5 - 0.5 * (2.0 * PI * i as f32 / FFT_N as f32).cos();
        }
        for i in 0..FFT_N / 2 {
            let a = 2.0 * PI * i as f32 / FFT_N as f32;
            cos[i] = a.cos();
            sin[i] = -a.sin();
        }
        FftTables { hann, cos, sin }
    })
}

/// Converts `FFT_N` mono samples into Android's `Visualizer.getFft` byte layout.
pub fn fft_bytes(samples: &[f32; FFT_N]) -> [i8; FFT_N] {
    let tables = fft_tables();
    let mut re = [0.0f32; FFT_N];
    let mut im = [0.0f32; FFT_N];
    for i in 0..FFT_N {
        re[i] = samples[i] * tables.hann[i];
    }
    let mut j = 0usize;
    for i in 1..FFT_N {
        let mut bit = FFT_N >> 1;
        while (j & bit) != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2usize;
    while len <= FFT_N {
        let step = FFT_N / len;
        let half = len / 2;
        for start in (0..FFT_N).step_by(len) {
            for k in 0..half {
                let wr = tables.cos[k * step];
                let wi = tables.sin[k * step];
                let a = start + k;
                let b = a + half;
                let tr = re[b] * wr - im[b] * wi;
                let ti = re[b] * wi + im[b] * wr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
            }
        }
        len <<= 1;
    }
    let scale = 127.0 * 4.0 / FFT_N as f32;
    let clamp = |v: f32| (v * scale).round().clamp(-127.0, 127.0) as i8;
    let mut out = [0i8; FFT_N];
    out[0] = clamp(re[0]);
    out[1] = clamp(re[FFT_N / 2]);
    for b in 1..FFT_N / 2 {
        out[2 * b] = clamp(re[b]);
        out[2 * b + 1] = clamp(im[b]);
    }
    out
}

#[cfg(not(feature = "preview"))]
static RING: PcmRing = PcmRing::new();
static DIRECT_ENABLED: AtomicBool = AtomicBool::new(false);
#[cfg(not(feature = "preview"))]
static WORKER_STATE: Mutex<(u64, i64)> = Mutex::new((0, 0));

pub fn install() {
    #[cfg(all(target_os = "android", not(feature = "preview")))]
    native_hook::install();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_Audio_nativeSetDirect(_: JNIEnv, _: JClass, on: jboolean) {
    let enabled = on != 0;
    if enabled {
        install();
    }
    DIRECT_ENABLED.store(enabled, Ordering::Relaxed);
    #[cfg(not(feature = "preview"))]
    {
        *WORKER_STATE.lock().unwrap() = (RING.seq(), 0);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_Audio_nativeSampleDirect(
    env: JNIEnv, _: JClass, dt_ms: jfloat, now_ms: jlong, out: JFloatArray,
) -> jboolean {
    #[cfg(feature = "preview")]
    {
        let _ = (env, dt_ms, now_ms, out);
        JNI_FALSE
    }
    #[cfg(not(feature = "preview"))]
    {
        if !DIRECT_ENABLED.load(Ordering::Relaxed) {
            return JNI_FALSE;
        }
        let mut samples = [0.0f32; FFT_N];
        let (seq, rate) = RING.snapshot(&mut samples);
        if seq == 0 {
            return JNI_FALSE;
        }
        {
            let mut guard = WORKER_STATE.lock().unwrap();
            if seq != guard.0 {
                guard.0 = seq;
                guard.1 = now_ms;
            } else if now_ms - guard.1 > 400 {
                return JNI_FALSE;
            }
        }
        let fft = fft_bytes(&samples);
        let values = crate::spectrum::analyse_into(&fft, rate.max(8_000) as f32, dt_ms, now_ms);
        if env.set_float_array_region(&out, 0, &values).is_err() {
            return JNI_FALSE;
        }
        JNI_TRUE
    }
}

#[cfg(all(target_os = "android", not(feature = "preview")))]
mod native_hook {
    use super::{DIRECT_ENABLED, RING};
    use std::ffi::{CStr, c_char, c_int, c_void};
    use std::sync::atomic::{AtomicBool, AtomicI32, AtomicUsize, Ordering};

    const PT_DYNAMIC: u32 = 2;
    const DT_NULL: i64 = 0;
    const DT_PLTRELSZ: i64 = 2;
    const DT_STRTAB: i64 = 5;
    const DT_SYMTAB: i64 = 6;
    const DT_RELA: i64 = 7;
    const DT_RELASZ: i64 = 8;
    const DT_JMPREL: i64 = 23;
    const R_AARCH64_GLOB_DAT: u32 = 1025;
    const R_AARCH64_JUMP_SLOT: u32 = 1026;
    const PROT_READ: c_int = 1;
    const PROT_WRITE: c_int = 2;
    const SC_PAGESIZE: c_int = 0x0027;
    const RTLD_DEFAULT: *mut c_void = std::ptr::null_mut();

    #[repr(C)]
    struct DlPhdrInfo {
        dlpi_addr: usize,
        dlpi_name: *const c_char,
        dlpi_phdr: *const Elf64Phdr,
        dlpi_phnum: u16,
    }

    #[repr(C)]
    struct Elf64Phdr {
        p_type: u32,
        p_flags: u32,
        p_offset: u64,
        p_vaddr: u64,
        p_paddr: u64,
        p_filesz: u64,
        p_memsz: u64,
        p_align: u64,
    }

    #[repr(C)]
    struct Elf64Dyn {
        d_tag: i64,
        d_val: u64,
    }

    #[repr(C)]
    struct Elf64Rela {
        r_offset: u64,
        r_info: u64,
        r_addend: i64,
    }

    #[repr(C)]
    struct Elf64Sym {
        st_name: u32,
        st_info: u8,
        st_other: u8,
        st_shndx: u16,
        st_value: u64,
        st_size: u64,
    }

    unsafe extern "C" {
        fn dl_iterate_phdr(
            callback: unsafe extern "C" fn(*mut DlPhdrInfo, usize, *mut c_void) -> c_int,
            data: *mut c_void,
        ) -> c_int;
        fn mprotect(addr: *mut c_void, len: usize, prot: c_int) -> c_int;
        fn sysconf(name: c_int) -> isize;
        fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    }

    type DlSymFn = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;
    type DataCallbackFn = unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_void, i32) -> i32;
    type SetDataCallbackFn = unsafe extern "C" fn(*mut c_void, Option<DataCallbackFn>, *mut c_void);
    type OpenStreamFn = unsafe extern "C" fn(*mut c_void, *mut *mut c_void) -> i32;
    type StreamWriteFn = unsafe extern "C" fn(*mut c_void, *const c_void, i32, i64) -> i32;
    type StreamCloseFn = unsafe extern "C" fn(*mut c_void) -> i32;
    type StreamIntFn = unsafe extern "C" fn(*mut c_void) -> i32;

    static HOOKED: AtomicBool = AtomicBool::new(false);
    static LOGGED_STREAM: AtomicBool = AtomicBool::new(false);
    static REAL_DLSYM: AtomicUsize = AtomicUsize::new(0);
    static REAL_SET_DATA_CB: AtomicUsize = AtomicUsize::new(0);
    static REAL_OPEN_STREAM: AtomicUsize = AtomicUsize::new(0);
    static REAL_STREAM_WRITE: AtomicUsize = AtomicUsize::new(0);
    static REAL_STREAM_CLOSE: AtomicUsize = AtomicUsize::new(0);
    static REAL_GET_RATE: AtomicUsize = AtomicUsize::new(0);
    static REAL_GET_CH: AtomicUsize = AtomicUsize::new(0);
    static REAL_GET_FMT: AtomicUsize = AtomicUsize::new(0);

    struct StreamSlot {
        builder: AtomicUsize,
        stream: AtomicUsize,
        real_cb: AtomicUsize,
        real_ud: AtomicUsize,
        rate: AtomicI32,
        channels: AtomicI32,
        format: AtomicI32,
    }

    impl StreamSlot {
        const fn new() -> Self {
            Self {
                builder: AtomicUsize::new(0),
                stream: AtomicUsize::new(0),
                real_cb: AtomicUsize::new(0),
                real_ud: AtomicUsize::new(0),
                rate: AtomicI32::new(48_000),
                channels: AtomicI32::new(2),
                format: AtomicI32::new(1),
            }
        }
    }

    static SLOTS: [StreamSlot; 8] = [const { StreamSlot::new() }; 8];
    static NEXT_SLOT: AtomicUsize = AtomicUsize::new(0);

    pub fn install() {
        if HOOKED.swap(true, Ordering::AcqRel) {
            return;
        }
        unsafe {
            dl_iterate_phdr(patch_phdr, std::ptr::null_mut());
        }
        if REAL_DLSYM.load(Ordering::Acquire) == 0 {
            HOOKED.store(false, Ordering::Release);
        }
    }

    fn reloc_ptr(base: usize, val: usize) -> usize {
        if val == 0 {
            0
        } else if val < base {
            base + val
        } else {
            val
        }
    }

    unsafe extern "C" fn patch_phdr(info: *mut DlPhdrInfo, _size: usize, _data: *mut c_void) -> c_int {
        if info.is_null() {
            return 0;
        }
        let info = unsafe { &*info };
        if info.dlpi_name.is_null() || info.dlpi_phdr.is_null() {
            return 0;
        }
        let name = unsafe { CStr::from_ptr(info.dlpi_name) };
        if !name.to_string_lossy().contains("liborbit-jni-spotify.so") {
            return 0;
        }
        let base = info.dlpi_addr;
        let phdrs = unsafe { std::slice::from_raw_parts(info.dlpi_phdr, info.dlpi_phnum as usize) };
        let Some(dyn_phdr) = phdrs.iter().find(|p| p.p_type == PT_DYNAMIC) else {
            return 0;
        };
        let mut dyn_ptr = (base + dyn_phdr.p_vaddr as usize) as *const Elf64Dyn;
        let (mut strtab, mut symtab, mut jmprel, mut pltrelsz, mut rela, mut relasz) = (0usize, 0usize, 0usize, 0usize, 0usize, 0usize);
        unsafe {
            while (*dyn_ptr).d_tag != DT_NULL {
                let d = &*dyn_ptr;
                match d.d_tag {
                    DT_STRTAB => strtab = reloc_ptr(base, d.d_val as usize),
                    DT_SYMTAB => symtab = reloc_ptr(base, d.d_val as usize),
                    DT_JMPREL => jmprel = reloc_ptr(base, d.d_val as usize),
                    DT_PLTRELSZ => pltrelsz = d.d_val as usize,
                    DT_RELA => rela = reloc_ptr(base, d.d_val as usize),
                    DT_RELASZ => relasz = d.d_val as usize,
                    _ => {}
                }
                dyn_ptr = dyn_ptr.add(1);
            }
        }
        if strtab == 0 || symtab == 0 {
            return 0;
        }
        for (table, bytes) in [(jmprel, pltrelsz), (rela, relasz)] {
            if table == 0 || bytes == 0 {
                continue;
            }
            let count = bytes / std::mem::size_of::<Elf64Rela>();
            let entries = unsafe { std::slice::from_raw_parts(table as *const Elf64Rela, count) };
            for entry in entries {
                let r_type = (entry.r_info & 0xffff_ffff) as u32;
                if r_type != R_AARCH64_JUMP_SLOT && r_type != R_AARCH64_GLOB_DAT {
                    continue;
                }
                let sym_idx = (entry.r_info >> 32) as usize;
                let sym = unsafe { &*(symtab as *const Elf64Sym).add(sym_idx) };
                let sym_name = unsafe { CStr::from_ptr((strtab as *const c_char).add(sym.st_name as usize)) };
                if sym_name.to_bytes() != b"dlsym" {
                    continue;
                }
                let got_slot = (base + entry.r_offset as usize) as *mut usize;
                let page = unsafe { sysconf(SC_PAGESIZE) }.max(4096) as usize;
                let page_addr = (got_slot as usize) & !(page - 1);
                if unsafe { mprotect(page_addr as *mut c_void, page, PROT_READ | PROT_WRITE) } != 0 {
                    continue;
                }
                let orig = unsafe { got_slot.read_volatile() };
                let hook = hooked_dlsym as *const () as usize;
                if orig != 0 && orig != hook {
                    let _ = REAL_DLSYM.compare_exchange(0, orig, Ordering::AcqRel, Ordering::Acquire);
                }
                unsafe { got_slot.write_volatile(hook) };
                log::info!("audio_tap: hooked dlsym in liborbit-jni-spotify.so");
                return 1;
            }
        }
        0
    }

    fn real_dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void {
        match REAL_DLSYM.load(Ordering::Acquire) {
            0 => unsafe { dlsym(handle, symbol) },
            ptr => unsafe {
                let f: DlSymFn = std::mem::transmute(ptr);
                f(handle, symbol)
            },
        }
    }

    unsafe extern "C" fn hooked_dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void {
        let res = real_dlsym(handle, symbol);
        if res.is_null() || symbol.is_null() {
            return res;
        }
        let name = unsafe { CStr::from_ptr(symbol) }.to_bytes();
        match name {
            b"AAudioStreamBuilder_setDataCallback" => {
                REAL_SET_DATA_CB.store(res as usize, Ordering::Release);
                hooked_set_data_callback as *const () as *mut c_void
            }
            b"AAudioStreamBuilder_openStream" => {
                REAL_OPEN_STREAM.store(res as usize, Ordering::Release);
                hooked_open_stream as *const () as *mut c_void
            }
            b"AAudioStream_write" => {
                REAL_STREAM_WRITE.store(res as usize, Ordering::Release);
                hooked_stream_write as *const () as *mut c_void
            }
            b"AAudioStream_close" => {
                REAL_STREAM_CLOSE.store(res as usize, Ordering::Release);
                hooked_stream_close as *const () as *mut c_void
            }
            b"AAudioStream_getSampleRate" => {
                REAL_GET_RATE.store(res as usize, Ordering::Release);
                res
            }
            b"AAudioStream_getChannelCount" | b"AAudioStream_getSamplesPerFrame" => {
                REAL_GET_CH.store(res as usize, Ordering::Release);
                res
            }
            b"AAudioStream_getFormat" => {
                REAL_GET_FMT.store(res as usize, Ordering::Release);
                res
            }
            _ => res,
        }
    }

    fn slot_for_builder(builder: usize) -> &'static StreamSlot {
        if let Some(slot) = SLOTS.iter().find(|s| s.builder.load(Ordering::Acquire) == builder) {
            return slot;
        }
        if let Some(slot) = SLOTS.iter().find(|s| s.builder.load(Ordering::Acquire) == 0 && s.stream.load(Ordering::Acquire) == 0) {
            slot.builder.store(builder, Ordering::Release);
            return slot;
        }
        let slot = &SLOTS[NEXT_SLOT.fetch_add(1, Ordering::Relaxed) % SLOTS.len()];
        slot.builder.store(builder, Ordering::Release);
        slot.stream.store(0, Ordering::Release);
        slot
    }

    fn slot_for_stream(stream: usize) -> Option<&'static StreamSlot> {
        SLOTS.iter().find(|s| s.stream.load(Ordering::Acquire) == stream)
    }

    fn query_stream_int(cache: &AtomicUsize, name: &CStr, stream: *mut c_void) -> i32 {
        let ptr = match cache.load(Ordering::Acquire) {
            0 => {
                let resolved = unsafe { dlsym(RTLD_DEFAULT, name.as_ptr()) } as usize;
                if resolved != 0 {
                    cache.store(resolved, Ordering::Release);
                }
                resolved
            }
            p => p,
        };
        if ptr == 0 {
            return 0;
        }
        let f: StreamIntFn = unsafe { std::mem::transmute(ptr) };
        unsafe { f(stream) }
    }

    fn record_stream_metadata(slot: &StreamSlot, stream: *mut c_void) {
        let rate = query_stream_int(&REAL_GET_RATE, c"AAudioStream_getSampleRate", stream);
        let ch = query_stream_int(&REAL_GET_CH, c"AAudioStream_getChannelCount", stream);
        let fmt = query_stream_int(&REAL_GET_FMT, c"AAudioStream_getFormat", stream);
        if rate > 0 {
            slot.rate.store(rate, Ordering::Release);
        }
        if ch > 0 {
            slot.channels.store(ch, Ordering::Release);
        }
        if fmt > 0 {
            slot.format.store(fmt, Ordering::Release);
        }
        slot.stream.store(stream as usize, Ordering::Release);
        if !LOGGED_STREAM.swap(true, Ordering::Relaxed) {
            log::info!("audio_tap: AAudio stream rate={rate} ch={ch} fmt={fmt}");
        }
    }

    fn feed_from_slot(slot: &StreamSlot, audio_data: *const c_void, num_frames: i32) {
        if !DIRECT_ENABLED.load(Ordering::Relaxed) || audio_data.is_null() || num_frames <= 0 {
            return;
        }
        let frames = num_frames as usize;
        let ch = slot.channels.load(Ordering::Acquire).max(1) as usize;
        let rate = slot.rate.load(Ordering::Acquire).max(8_000);
        let fmt = slot.format.load(Ordering::Acquire);
        let Some(total) = frames.checked_mul(ch) else { return };
        match fmt {
            2 => RING.push_f32(unsafe { std::slice::from_raw_parts(audio_data as *const f32, total) }, ch, rate),
            4 => RING.push_i32(unsafe { std::slice::from_raw_parts(audio_data as *const i32, total) }, ch, rate),
            _ => RING.push_i16(unsafe { std::slice::from_raw_parts(audio_data as *const i16, total) }, ch, rate),
        }
    }

    unsafe extern "C" fn hooked_set_data_callback(builder: *mut c_void, callback: Option<DataCallbackFn>, user_data: *mut c_void) {
        let real_ptr = REAL_SET_DATA_CB.load(Ordering::Acquire);
        if real_ptr == 0 {
            return;
        }
        let real: SetDataCallbackFn = unsafe { std::mem::transmute(real_ptr) };
        let Some(cb) = callback else {
            unsafe { real(builder, None, user_data) };
            return;
        };
        let slot = slot_for_builder(builder as usize);
        slot.real_cb.store(cb as usize, Ordering::Release);
        slot.real_ud.store(user_data as usize, Ordering::Release);
        unsafe { real(builder, Some(trampoline_data_callback), slot as *const StreamSlot as *mut c_void) };
    }

    unsafe extern "C" fn trampoline_data_callback(
        stream: *mut c_void, slot_ptr: *mut c_void, audio_data: *mut c_void, num_frames: i32,
    ) -> i32 {
        if slot_ptr.is_null() {
            return 1;
        }
        let slot = unsafe { &*(slot_ptr as *const StreamSlot) };
        let cb_ptr = slot.real_cb.load(Ordering::Acquire);
        if cb_ptr == 0 {
            return 1;
        }
        let real_cb: DataCallbackFn = unsafe { std::mem::transmute(cb_ptr) };
        let real_ud = slot.real_ud.load(Ordering::Acquire) as *mut c_void;
        let res = unsafe { real_cb(stream, real_ud, audio_data, num_frames) };
        if res == 0 {
            feed_from_slot(slot, audio_data, num_frames);
        }
        res
    }

    unsafe extern "C" fn hooked_open_stream(builder: *mut c_void, stream_out: *mut *mut c_void) -> i32 {
        let real_ptr = REAL_OPEN_STREAM.load(Ordering::Acquire);
        if real_ptr == 0 {
            return -1;
        }
        let real: OpenStreamFn = unsafe { std::mem::transmute(real_ptr) };
        let rc = unsafe { real(builder, stream_out) };
        if rc == 0 && !stream_out.is_null() {
            let stream = unsafe { *stream_out };
            if !stream.is_null() {
                let slot = slot_for_builder(builder as usize);
                record_stream_metadata(slot, stream);
            }
        }
        rc
    }

    unsafe extern "C" fn hooked_stream_write(stream: *mut c_void, buffer: *const c_void, num_frames: i32, timeout_ns: i64) -> i32 {
        let real_ptr = REAL_STREAM_WRITE.load(Ordering::Acquire);
        if real_ptr == 0 {
            return -1;
        }
        let real: StreamWriteFn = unsafe { std::mem::transmute(real_ptr) };
        let written = unsafe { real(stream, buffer, num_frames, timeout_ns) };
        if written > 0 {
            if let Some(slot) = slot_for_stream(stream as usize) {
                feed_from_slot(slot, buffer, written);
            }
        }
        written
    }

    unsafe extern "C" fn hooked_stream_close(stream: *mut c_void) -> i32 {
        let real_ptr = REAL_STREAM_CLOSE.load(Ordering::Acquire);
        let rc = if real_ptr != 0 {
            let real: StreamCloseFn = unsafe { std::mem::transmute(real_ptr) };
            unsafe { real(stream) }
        } else {
            0
        };
        if let Some(slot) = slot_for_stream(stream as usize) {
            slot.stream.store(0, Ordering::Release);
            slot.builder.store(0, Ordering::Release);
        }
        rc
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_downmixes_and_wraps_to_latest_window() {
        let ring = PcmRing::new();
        let mut stereo = Vec::with_capacity(6000 * 2);
        for i in 0..6000 {
            let v = if i >= 5000 { 16384i16 } else { -16384i16 };
            stereo.push(v);
            stereo.push(0);
        }
        ring.push_i16(&stereo, 2, 48_000);
        let mut out = [0.0f32; FFT_N];
        let (seq, rate) = ring.snapshot(&mut out);
        assert_eq!((seq, rate), (1, 48_000));
        // Tail 1000 frames are ~0.25 (average of 0.5 and 0), head 24 frames are ~-0.25.
        assert!((out[0] - -0.25).abs() < 0.001);
        assert!((out[FFT_N - 1] - 0.25).abs() < 0.001);
    }

    #[test]
    fn fft_peaks_at_input_tone_bin() {
        let mut samples = [0.0f32; FFT_N];
        let rate = 44_100.0;
        let hz = 1000.0;
        for (i, s) in samples.iter_mut().enumerate() {
            *s = (2.0 * PI * hz * i as f32 / rate).sin();
        }
        let fft = fft_bytes(&samples);
        let target_bin = (hz * FFT_N as f32 / rate).round() as usize;
        let mag = |b: usize| (fft[2 * b] as i32).pow(2) + (fft[2 * b + 1] as i32).pow(2);
        let peak_bin = (1..FFT_N / 2).max_by_key(|&b| mag(b)).unwrap();
        assert!((peak_bin as isize - target_bin as isize).abs() <= 1, "peak {peak_bin} vs {target_bin}");
        assert!(mag(peak_bin) > 10_000);
    }
}
