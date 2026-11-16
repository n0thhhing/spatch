use makepad_widgets::*;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::player::NowPlaying;
#[cfg(target_os = "android")]
use crate::core;
use crate::{colors, images, player};

// Latest player state and status pushed from core's Rx callback thread.
static LATEST: OnceLock<Mutex<Option<player::NowPlaying>>> = OnceLock::new();
static STATUS: OnceLock<Mutex<String>> = OnceLock::new();

fn latest_slot() -> &'static Mutex<Option<player::NowPlaying>> {
    LATEST.get_or_init(Default::default)
}

fn status_slot() -> &'static Mutex<String> {
    STATUS.get_or_init(Default::default)
}

live_design! {
    use link::theme::*;
    use link::shaders::*;
    use link::widgets::*;

    FONT_OUTFIT_BOLD = {
        font_family: {
            latin = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            chinese = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            emoji = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
        }
        line_spacing: 1.2
    }

    FONT_OUTFIT_EXTRABOLD = {
        font_family: {
            latin = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
            chinese = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
            emoji = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
        }
        line_spacing: 1.15
    }

    IconBtn = <Button> {
        width: 48,
        height: 48,
        text: "",
        padding: 0,
        margin: 0,
        align: {x: 0.5, y: 0.5},
        icon_walk: {width: 26, height: 26},
        draw_bg: {
            instance hover: 0.0,
            instance down: 0.0,
            uniform bg_color: #ffffff14,
            fn pixel(self) -> vec4 {
                let sdf = Sdf2d::viewport(self.pos * self.rect_size);
                let c = self.rect_size * 0.5;
                let r = min(c.x, c.y) - 1.0;
                sdf.circle(c.x, c.y, r);
                let alpha = self.bg_color.a + self.hover * 0.08 + self.down * 0.14;
                sdf.fill(vec4(self.bg_color.rgb, alpha));
                return sdf.result;
            }
        }
        draw_icon: {
            uniform color: #eef2ff,
            uniform color_hover: #ffffff,
            uniform color_down: #cbd5e1,
            fn get_color(self) -> vec4 {
                return mix(mix(self.color, self.color_hover, self.hover), self.color_down, self.down);
            }
        }
    }

    ScallopPlayBtn = <Button> {
        width: 84,
        height: 84,
        text: "",
        padding: 0,
        margin: 0,
        align: {x: 0.5, y: 0.5},
        draw_bg: {
            instance hover: 0.0,
            instance down: 0.0,
            instance morph: 1.0,
            instance spin: 0.0,
            uniform accent: #a8c7fa,
            uniform on_accent: #0c1929,

            fn pixel(self) -> vec4 {
                let p = (self.pos - vec2(0.5, 0.5)) * self.rect_size;
                let dist = length(p);
                let angle = atan(p.y, p.x) + self.spin;
                let base_r = min(self.rect_size.x, self.rect_size.y) * 0.43;
                let depth = base_r * 0.075;
                let wave = (1.0 - self.morph) * cos(5.0 * angle) + self.morph * cos(9.0 * angle);
                let r = base_r + depth * wave - self.down * 1.5;

                let aa = 1.25;
                let body_alpha = clamp((r - dist) / aa + 0.5, 0.0, 1.0);
                let glow_alpha = exp(-max(dist - r, 0.0) * 0.16) * 0.28 * (0.6 + 0.4 * self.morph);

                let body_col = mix(self.accent, #ffffff, self.hover * 0.12);
                let col = body_col * body_alpha + self.accent * glow_alpha * (1.0 - body_alpha);
                let out_a = max(body_alpha, glow_alpha);

                // Play triangle (morph = 0) vs Pause bars (morph = 1) drawn in shader.
                let s = base_r * 0.34;
                // Play triangle centered slightly right for optical balance.
                let tp = p - vec2(s * 0.12, 0.0);
                let qx = tp.x;
                let qy = abs(tp.y);
                let tri_d = max(-qx - s * 0.55, (qy * 0.866025 + qx * 0.5) - s * 0.52);
                let tri_a = clamp(-tri_d / aa + 0.5, 0.0, 1.0);

                // Two pause bars.
                let bar_w = s * 0.15;
                let bar_h = s * 0.54;
                let bar_gap = s * 0.36;
                let bx = abs(abs(p.x) - bar_gap) - bar_w;
                let by = abs(p.y) - bar_h;
                let bar_d = length(max(vec2(bx, by), vec2(0.0, 0.0))) + min(max(bx, by), 0.0) - 1.2;
                let bar_a = clamp(-bar_d / aa + 0.5, 0.0, 1.0);

                let glyph_a = mix(tri_a, bar_a, self.morph) * body_alpha;
                let final_rgb = mix(col.rgb, self.on_accent.rgb, glyph_a);
                return vec4(final_rgb * out_a, out_a);
            }
        }
    }

    pub App = {{App}} {
        ui: <Root> {
            main_window = <Window> {
                window: { title: "Spatch", inner_size: vec2(412, 915) }
                show_bg: true,
                draw_bg: { color: #090b10 }
                body = <View> {
                    width: Fill,
                    height: Fill,
                    flow: Overlay,

                    backdrop = <Image> {
                        width: Fill,
                        height: Fill,
                        fit: Biggest,
                        draw_bg: {
                            uniform has_tex: 0.0,
                            uniform tint: #0c101b,
                            fn pixel(self) -> vec4 {
                                if self.has_tex < 0.5 {
                                    let grad = mix(self.tint, #05070b, self.pos.y);
                                    return vec4(grad.rgb, 1.0);
                                }
                                let uv = self.pos * self.image_scale + self.image_pan;
                                let o = 0.045;
                                let c = sample2d(self.image, uv).rgb * 0.24
                                    + sample2d(self.image, uv + vec2(o, 0.0)).rgb * 0.11
                                    + sample2d(self.image, uv - vec2(o, 0.0)).rgb * 0.11
                                    + sample2d(self.image, uv + vec2(0.0, o)).rgb * 0.11
                                    + sample2d(self.image, uv - vec2(0.0, o)).rgb * 0.11
                                    + sample2d(self.image, uv + vec2(o, o)).rgb * 0.08
                                    + sample2d(self.image, uv + vec2(-o, o)).rgb * 0.08
                                    + sample2d(self.image, uv + vec2(o, -o)).rgb * 0.08
                                    + sample2d(self.image, uv + vec2(-o, -o)).rgb * 0.08;
                                let darkened = mix(c * 0.36, self.tint.rgb * 0.45, 0.35);
                                let vignette = 1.0 - 0.35 * length(self.pos - vec2(0.5, 0.45));
                                return vec4(darkened * vignette, 1.0);
                            }
                        }
                    }

                    content = <View> {
                        width: Fill,
                        height: Fill,
                        flow: Down,
                        align: {x: 0.5, y: 0.5},
                        padding: {left: 28, right: 28, top: 44, bottom: 36},
                        spacing: 18,

                        top_bar = <View> {
                            width: Fill,
                            height: Fit,
                            flow: Right,
                            align: {x: 1.0, y: 0.5},

                            status_label = <Label> {
                                width: Fill,
                                height: Fit,
                                text: "NOW PLAYING",
                                draw_text: {
                                    color: #ffffff99,
                                    text_style: <FONT_OUTFIT_BOLD> { font_size: 11.5 }
                                }
                            }

                            close_btn = <IconBtn> {
                                width: 40,
                                height: 40,
                                icon_walk: {width: 20, height: 20},
                                draw_icon: {
                                    svg_file: dep("crate://self/resources/icons/close.svg"),
                                    color: #eef2ff,
                                }
                            }
                        }

                        <View> { width: Fill, height: 8 }

                        cover_wrap = <View> {
                            width: 296,
                            height: 296,
                            flow: Overlay,
                            align: {x: 0.5, y: 0.5},

                            cover_ring = <View> {
                                width: Fill,
                                height: Fill,
                                show_bg: true,
                                draw_bg: {
                                    instance phase: 0.0,
                                    instance playing: 1.0,
                                    uniform accent: #a8c7fa,
                                    fn pixel(self) -> vec4 {
                                        let p = (self.pos - vec2(0.5, 0.5)) * self.rect_size;
                                        let d = length(p);
                                        let a = atan(p.y, p.x);
                                        let base = min(self.rect_size.x, self.rect_size.y) * 0.468;
                                        let wave = sin(a * 12.0 - self.phase * 2.2) * 2.6 * self.playing;
                                        let ring_d = abs(d - (base + wave)) - 1.3;
                                        let ring_a = clamp(-ring_d / 1.4 + 0.5, 0.0, 1.0) * 0.72;
                                        let glow_a = exp(-abs(d - base) * 0.14) * 0.18 * (0.4 + 0.6 * self.playing);
                                        let alpha = max(ring_a, glow_a);
                                        return vec4(self.accent.rgb * alpha, alpha);
                                    }
                                }
                            }

                            cover = <Image> {
                                width: 260,
                                height: 260,
                                fit: Biggest,
                                draw_bg: {
                                    uniform has_tex: 0.0,
                                    uniform accent: #a8c7fa,
                                    fn pixel(self) -> vec4 {
                                        let p = (self.pos - vec2(0.5, 0.5)) * self.rect_size;
                                        let r = min(self.rect_size.x, self.rect_size.y) * 0.5 - 1.0;
                                        let alpha = clamp((r - length(p)) / 1.2 + 0.5, 0.0, 1.0);
                                        let tex_col = mix(
                                            vec4(self.accent.rgb * 0.25, 1.0),
                                            self.get_color(),
                                            self.has_tex
                                        );
                                        return vec4(tex_col.rgb * alpha, alpha);
                                    }
                                }
                            }
                        }

                        <View> { width: Fill, height: 6 }

                        meta_box = <View> {
                            width: Fill,
                            height: Fit,
                            flow: Down,
                            align: {x: 0.5, y: 0.5},
                            spacing: 6,

                            title_label = <Label> {
                                width: Fill,
                                height: Fit,
                                align: {x: 0.5},
                                text: "Midnight Echoes",
                                draw_text: {
                                    color: #f8fafc,
                                    wrap: Word,
                                    text_style: <FONT_OUTFIT_EXTRABOLD> { font_size: 22.0 }
                                }
                            }

                            artist_label = <Label> {
                                width: Fill,
                                height: Fit,
                                align: {x: 0.5},
                                text: "Glass Harbor",
                                draw_text: {
                                    color: #cbd5e1,
                                    wrap: Word,
                                    text_style: <FONT_OUTFIT_BOLD> { font_size: 15.0 }
                                }
                            }
                        }

                        progress_section = <View> {
                            width: Fill,
                            height: Fit,
                            flow: Down,
                            spacing: 4,

                            progress_bar = <View> {
                                width: Fill,
                                height: 32,
                                show_bg: true,
                                draw_bg: {
                                    instance progress: 0.35,
                                    instance playing: 1.0,
                                    instance phase: 0.0,
                                    uniform accent: #a8c7fa,
                                    uniform track_color: #ffffff38,

                                    fn pixel(self) -> vec4 {
                                        let w = self.rect_size.x;
                                        let h = self.rect_size.y;
                                        let px = self.pos.x * w;
                                        let py = self.pos.y * h;
                                        let cy = h * 0.5;
                                        let pad = 8.0;
                                        let usable = max(w - pad * 2.0, 1.0);
                                        let split_x = pad + usable * clamp(self.progress, 0.0, 1.0);

                                        // Wavy played segment.
                                        let amp = 3.2 * self.playing;
                                        let wave_y = cy + sin(px * 0.11 - self.phase * 4.5) * amp;
                                        let in_played = step(pad, px) * step(px, split_x);
                                        let played_d = abs(py - wave_y) - 1.9;
                                        let played_a = clamp(-played_d / 1.2 + 0.5, 0.0, 1.0) * in_played;

                                        // Straight unplayed track.
                                        let in_rest = step(split_x, px) * step(px, w - pad);
                                        let rest_d = abs(py - cy) - 1.5;
                                        let rest_a = clamp(-rest_d / 1.2 + 0.5, 0.0, 1.0) * in_rest;

                                        // Playhead thumb.
                                        let thumb_y = cy + sin(split_x * 0.11 - self.phase * 4.5) * amp;
                                        let thumb_d = length(vec2(px - split_x, py - thumb_y)) - 6.0;
                                        let thumb_a = clamp(-thumb_d / 1.2 + 0.5, 0.0, 1.0);

                                        let rest_col = self.track_color * rest_a;
                                        let played_col = vec4(self.accent.rgb, 1.0) * max(played_a, thumb_a);
                                        let out_rgb = mix(rest_col.rgb, played_col.rgb, max(played_a, thumb_a));
                                        let out_a = max(rest_a * self.track_color.a, max(played_a, thumb_a));
                                        return vec4(out_rgb * out_a, out_a);
                                    }
                                }
                            }

                            time_row = <View> {
                                width: Fill,
                                height: Fit,
                                flow: Right,

                                elapsed_label = <Label> {
                                    width: Fill,
                                    height: Fit,
                                    text: "1:18",
                                    draw_text: {
                                        color: #ffffff99,
                                        text_style: <FONT_OUTFIT_BOLD> { font_size: 12.0 }
                                    }
                                }

                                duration_label = <Label> {
                                    width: Fit,
                                    height: Fit,
                                    text: "3:42",
                                    draw_text: {
                                        color: #ffffff99,
                                        text_style: <FONT_OUTFIT_BOLD> { font_size: 12.0 }
                                    }
                                }
                            }
                        }

                        controls = <View> {
                            width: Fill,
                            height: Fit,
                            flow: Right,
                            align: {x: 0.5, y: 0.5},
                            spacing: 16,

                            shuffle_btn = <IconBtn> {
                                width: 42,
                                height: 42,
                                icon_walk: {width: 20, height: 20},
                                draw_icon: {
                                    svg_file: dep("crate://self/resources/icons/shuffle.svg"),
                                    color: #ffffff99,
                                }
                            }

                            prev_btn = <IconBtn> {
                                width: 54,
                                height: 54,
                                icon_walk: {width: 28, height: 28},
                                draw_icon: {
                                    svg_file: dep("crate://self/resources/icons/skip-previous.svg"),
                                }
                            }

                            play_btn = <ScallopPlayBtn> {}

                            next_btn = <IconBtn> {
                                width: 54,
                                height: 54,
                                icon_walk: {width: 28, height: 28},
                                draw_icon: {
                                    svg_file: dep("crate://self/resources/icons/skip-next.svg"),
                                }
                            }

                            repeat_btn = <IconBtn> {
                                width: 42,
                                height: 42,
                                icon_walk: {width: 20, height: 20},
                                draw_icon: {
                                    svg_file: dep("crate://self/resources/icons/repeat.svg"),
                                    color: #ffffff99,
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

app_main!(App);

#[derive(Live, LiveHook)]
pub struct App {
    #[live]
    ui: WidgetRef,
    #[rust]
    now: NowPlaying,
    #[rust]
    loaded_art_url: String,
    #[cfg(target_os = "android")]
    #[rust]
    subscribed: bool,
    #[rust]
    morph: f32,
    #[rust]
    spin: f32,
    #[rust]
    phase: f32,
    #[rust]
    next_frame: NextFrame,
    #[rust]
    last_tick: f64,
}

impl LiveRegister for App {
    fn live_register(cx: &mut Cx) {
        makepad_widgets::live_design(cx);
    }
}

impl MatchEvent for App {
    fn handle_startup(&mut self, cx: &mut Cx) {
        self.morph = 1.0;
        self.last_tick = 0.0;

        #[cfg(target_os = "android")]
        {
            self.start_subscription();
        }

        #[cfg(not(target_os = "android"))]
        {
            self.load_demo(cx);
        }

        self.next_frame = cx.new_next_frame();
        cx.start_interval(0.5);
    }

    fn handle_next_frame(&mut self, cx: &mut Cx, e: &NextFrameEvent) {
        let dt = if self.last_tick > 0.0 {
            ((e.time - self.last_tick) as f32).clamp(0.0, 0.1)
        } else {
            1.0 / 60.0
        };
        self.last_tick = e.time;

        let target_morph = if self.now.playing { 1.0 } else { 0.0 };
        self.morph += (target_morph - self.morph) * (dt * 10.0).min(1.0);
        if self.now.playing {
            self.phase += dt;
            self.spin = (self.spin + dt * 0.45) % std::f32::consts::TAU;
        }

        let playing_f = if self.now.playing { 1.0 } else { 0.0 };
        self.ui.button(id!(play_btn)).apply_over(
            cx,
            live! {
                draw_bg: { morph: (self.morph), spin: (self.spin) }
            },
        );
        self.ui.view(id!(cover_ring)).apply_over(
            cx,
            live! {
                draw_bg: { phase: (self.phase), playing: (playing_f) }
            },
        );

        let progress = self.current_progress();
        self.ui.view(id!(progress_bar)).apply_over(
            cx,
            live! {
                draw_bg: { progress: (progress), playing: (playing_f), phase: (self.phase) }
            },
        );

        let animating = self.now.playing || (self.morph - target_morph).abs() > 0.005;
        if animating {
            self.next_frame = cx.new_next_frame();
        }
    }

    fn handle_timer(&mut self, cx: &mut Cx, _e: &TimerEvent) {
        self.refresh_time_labels(cx);
        #[cfg(target_os = "android")]
        if !self.subscribed {
            self.start_subscription();
        }
    }

    fn handle_signal(&mut self, cx: &mut Cx) {
        if let Some(state) = latest_slot().lock().unwrap().take() {
            self.apply_now_playing(cx, state);
        }
        let status = status_slot().lock().unwrap().clone();
        if !status.is_empty() {
            self.ui.label(id!(status_label)).set_text(cx, &status);
        }
        for (url, decoded) in images::take_ready() {
            if url == self.now.image {
                if let Some((w, h, rgba)) = decoded {
                    self.apply_artwork(cx, &rgba, w, h);
                }
            }
        }
    }

    fn handle_actions(&mut self, cx: &mut Cx, actions: &Actions) {
        if self.ui.button(id!(play_btn)).clicked(actions) {
            let cmd = if self.now.playing { player::Command::Pause } else { player::Command::Resume };
            self.now.playing = !self.now.playing;
            self.send_command(cmd);
            self.next_frame = cx.new_next_frame();
        }
        if self.ui.button(id!(prev_btn)).clicked(actions) {
            self.send_command(player::Command::Previous);
        }
        if self.ui.button(id!(next_btn)).clicked(actions) {
            self.send_command(player::Command::Next);
        }
        if self.ui.button(id!(shuffle_btn)).clicked(actions) {
            let next = !self.now.shuffle;
            self.now.shuffle = next;
            self.send_command(player::Command::Shuffle(next));
            self.update_toggle_colors(cx);
        }
        if self.ui.button(id!(repeat_btn)).clicked(actions) {
            let next = (self.now.repeat + 1) % 3;
            self.now.repeat = next;
            self.send_command(player::Command::Repeat(next));
            self.update_toggle_colors(cx);
        }
        if self.ui.button(id!(close_btn)).clicked(actions) {
            #[cfg(target_os = "android")]
            core::close_app();
            #[cfg(not(target_os = "android"))]
            cx.quit();
        }
    }
}

impl AppMain for App {
    fn handle_event(&mut self, cx: &mut Cx, event: &Event) {
        self.match_event(cx, event);
        self.ui.handle_event(cx, event, &mut Scope::empty());

        // Allow tapping/scrubbing the progress bar.
        if let Event::MouseUp(e) = event {
            let rect = self.ui.view(id!(progress_bar)).area().rect(cx);
            if rect.contains(e.abs) && self.now.duration_ms > 0 {
                let pad = 8.0;
                let frac = ((e.abs.x - rect.pos.x - pad) / (rect.size.x - pad * 2.0).max(1.0)).clamp(0.0, 1.0);
                let ms = (frac * self.now.duration_ms as f64).round() as i64;
                self.now.position_ms = ms;
                self.now.timestamp = now_ms();
                self.send_command(player::Command::SeekTo(ms));
                self.refresh_time_labels(cx);
                self.next_frame = cx.new_next_frame();
            }
        }
    }
}

impl App {
    fn apply_now_playing(&mut self, cx: &mut Cx, state: player::NowPlaying) {
        let image_changed = !state.image.is_empty() && state.image != self.loaded_art_url;
        if image_changed {
            self.loaded_art_url = state.image.clone();
            images::request(&state.image, 512);
        }
        self.now = state;
        self.ui.label(id!(title_label)).set_text(
            cx,
            if self.now.title.is_empty() { "Nothing playing" } else { &self.now.title },
        );
        self.ui.label(id!(artist_label)).set_text(cx, &self.now.artist);
        self.refresh_time_labels(cx);
        self.update_toggle_colors(cx);
        self.next_frame = cx.new_next_frame();
    }

    fn apply_artwork(&mut self, cx: &mut Cx, rgba: &[u8], w: u32, h: u32) {
        let cover_tex = rgba_to_texture(cx, rgba, w, h);
        self.ui.image(id!(cover)).set_texture(cx, Some(cover_tex));
        self.ui.image(id!(cover)).apply_over(cx, live! { draw_bg: { has_tex: 1.0 } });

        let (bd_rgba, bd_size) = images::backdrop(rgba, w, h);
        let bd_tex = rgba_to_texture(cx, &bd_rgba, bd_size, bd_size);
        self.ui.image(id!(backdrop)).set_texture(cx, Some(bd_tex));
        self.ui.image(id!(backdrop)).apply_over(cx, live! { draw_bg: { has_tex: 1.0 } });

        if let Some(seed) = colors::seed(rgba) {
            let theme = colors::from_seed(seed);
            let roles = colors::roles(&theme);
            let accent = argb_to_vec4(roles.primary);
            let on_accent = argb_to_vec4(roles.on_primary);
            let tint = argb_to_vec4(roles.background);
            self.ui.button(id!(play_btn)).apply_over(
                cx,
                live! { draw_bg: { accent: (accent), on_accent: (on_accent) } },
            );
            self.ui.view(id!(cover_ring)).apply_over(cx, live! { draw_bg: { accent: (accent) } });
            self.ui.view(id!(progress_bar)).apply_over(cx, live! { draw_bg: { accent: (accent) } });
            self.ui.image(id!(backdrop)).apply_over(cx, live! { draw_bg: { tint: (tint) } });
        }
    }

    fn update_toggle_colors(&mut self, cx: &mut Cx) {
        let shuf_col = if self.now.shuffle { vec4(0.66, 0.78, 0.98, 1.0) } else { vec4(1.0, 1.0, 1.0, 0.55) };
        let rep_col = if self.now.repeat > 0 { vec4(0.66, 0.78, 0.98, 1.0) } else { vec4(1.0, 1.0, 1.0, 0.55) };
        self.ui.button(id!(shuffle_btn)).apply_over(cx, live! { draw_icon: { color: (shuf_col) } });
        self.ui.button(id!(repeat_btn)).apply_over(cx, live! { draw_icon: { color: (rep_col) } });
    }

    fn current_progress(&self) -> f32 {
        if self.now.duration_ms <= 0 {
            return 0.0;
        }
        let pos = self.now.position_at(now_ms());
        (pos as f32 / self.now.duration_ms as f32).clamp(0.0, 1.0)
    }

    fn refresh_time_labels(&mut self, cx: &mut Cx) {
        let pos = self.now.position_at(now_ms());
        self.ui.label(id!(elapsed_label)).set_text(cx, &format_ms(pos));
        self.ui.label(id!(duration_label)).set_text(cx, &format_ms(self.now.duration_ms));
    }

    fn send_command(&self, cmd: player::Command) {
        #[cfg(target_os = "android")]
        {
            let (method, body) = player::command(cmd);
            std::thread::spawn(move || {
                if let Err(e) = core::call(player::SERVICE, method, &body) {
                    ::log::warn!("ContextPlayer.{method}: {e}");
                }
            });
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = cmd;
        }
    }

    #[cfg(target_os = "android")]
    fn start_subscription(&mut self) {
        self.subscribed = true;
        std::thread::spawn(|| {
            loop {
                let req = player::state_request();
                let res = core::subscribe(player::SERVICE, "GetState", &req, |msg| match msg {
                    Ok(bytes) => match player::NowPlaying::decode(bytes) {
                        Ok(state) => {
                            *latest_slot().lock().unwrap() = Some(state);
                            *status_slot().lock().unwrap() = "NOW PLAYING".to_string();
                            core::wake();
                        }
                        Err(e) => ::log::warn!("GetState decode: {e}"),
                    },
                    Err(err) => {
                        *status_slot().lock().unwrap() = err.to_string();
                        core::wake();
                    }
                });
                match res {
                    Ok(_) => break,
                    Err(e) if e.contains("isn't running yet") => {
                        *status_slot().lock().unwrap() = "Connecting to Spotify...".to_string();
                        core::wake();
                        std::thread::sleep(std::time::Duration::from_millis(300));
                    }
                    Err(e) => {
                        *status_slot().lock().unwrap() = format!("Core: {e}");
                        core::wake();
                        break;
                    }
                }
            }
        });
    }

    #[cfg(not(target_os = "android"))]
    fn load_demo(&mut self, cx: &mut Cx) {
        let demo = player::NowPlaying {
            uri: "spotify:track:demo".into(),
            title: "Midnight Echoes".into(),
            artist: "Glass Harbor".into(),
            album: "Neon Drift".into(),
            image: String::new(),
            playing: true,
            shuffle: true,
            repeat: 1,
            position_ms: 78_000,
            duration_ms: 222_000,
            timestamp: now_ms(),
            up_next: vec![],
        };
        self.apply_now_playing(cx, demo);
        let (w, h, rgba) = demo_artwork(256);
        self.apply_artwork(cx, &rgba, w, h);
    }
}

fn rgba_to_texture(cx: &mut Cx, rgba: &[u8], w: u32, h: u32) -> Texture {
    let data: Vec<u32> = rgba
        .chunks_exact(4)
        .map(|p| ((p[3] as u32) << 24) | ((p[0] as u32) << 16) | ((p[1] as u32) << 8) | (p[2] as u32))
        .collect();
    Texture::new_with_format(
        cx,
        TextureFormat::VecBGRAu8_32 {
            width: w as usize,
            height: h as usize,
            data: Some(data),
            updated: TextureUpdated::Full,
        },
    )
}

fn argb_to_vec4(argb: u32) -> Vec4 {
    vec4(
        ((argb >> 16) & 0xff) as f32 / 255.0,
        ((argb >> 8) & 0xff) as f32 / 255.0,
        (argb & 0xff) as f32 / 255.0,
        ((argb >> 24) & 0xff) as f32 / 255.0,
    )
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn format_ms(ms: i64) -> String {
    let s = (ms.max(0) / 1000) as u64;
    format!("{}:{:02}", s / 60, s % 60)
}

#[cfg(not(target_os = "android"))]
fn demo_artwork(size: u32) -> (u32, u32, Vec<u8>) {
    let mut rgba = Vec::with_capacity((size * size * 4) as usize);
    for y in 0..size {
        for x in 0..size {
            let u = x as f32 / size as f32;
            let v = y as f32 / size as f32;
            let dx = u - 0.5;
            let dy = v - 0.5;
            let dist = (dx * dx + dy * dy).sqrt();
            let ring = ((dist * 18.0).sin() * 0.5 + 0.5) * (1.0 - dist * 1.4).max(0.0);
            let r = (36.0 + 185.0 * (1.0 - v) * 0.7 + 90.0 * ring).clamp(0.0, 255.0) as u8;
            let g = (48.0 + 115.0 * u + 110.0 * ring).clamp(0.0, 255.0) as u8;
            let b = (115.0 + 135.0 * v + 60.0 * (1.0 - dist)).clamp(0.0, 255.0) as u8;
            rgba.extend_from_slice(&[r, g, b, 255]);
        }
    }
    (size, size, rgba)
}
