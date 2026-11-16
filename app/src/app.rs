use makepad_widgets::*;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(target_os = "android")]
use crate::core;
use crate::player::NowPlaying;
use crate::{colors, images, library, player};

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

    THEME_FONT_REGULAR = {
        font_family: {
            latin = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            chinese = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            emoji = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
        }
        line_spacing: 1.2
    }

    THEME_FONT_BOLD = {
        font_family: {
            latin = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
            chinese = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
            emoji = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
        }
        line_spacing: 1.15
    }

    THEME_FONT_LABEL = {
        font_family: {
            latin = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            chinese = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            emoji = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
        }
        line_spacing: 1.2
    }

    THEME_FONT_ITALIC = {
        font_family: {
            latin = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            chinese = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
            emoji = font("crate://self/resources/Outfit-Bold.ttf", 0.0, 0.0),
        }
        line_spacing: 1.2
    }

    THEME_FONT_BOLD_ITALIC = {
        font_family: {
            latin = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
            chinese = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
            emoji = font("crate://self/resources/Outfit-ExtraBold.ttf", 0.0, 0.0),
        }
        line_spacing: 1.15
    }

    FONT_OUTFIT_BOLD = <THEME_FONT_REGULAR> {}

    FONT_OUTFIT_EXTRABOLD = <THEME_FONT_BOLD> {}

    IconBtn = <Button> {
        width: 48,
        height: 48,
        text: "",
        padding: 0,
        margin: 0,
        align: {x: 0.5, y: 0.5},
        icon_walk: {width: 26, height: 26},
        label_walk: {width: 0, height: 0},
        spacing: 0,
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
            uniform accent: #a8c7fa,
            uniform on_accent: #0c1929,

            fn pixel(self) -> vec4 {
                let p = (self.pos - vec2(0.5, 0.5)) * self.rect_size;
                let dist = length(p);
                let angle = atan(p.y, p.x) + self.morph * self.time * 0.45;
                let base_r = min(self.rect_size.x, self.rect_size.y) * 0.43;
                let depth = base_r * 0.075;
                let wave = (1.0 - self.morph) * cos(5.0 * angle) + self.morph * cos(9.0 * angle);
                let r = base_r + depth * wave - self.down * 1.5;

                let aa = 1.25;
                let body_alpha = clamp((r - dist) / aa + 0.5, 0.0, 1.0);
                let edge = min(self.rect_size.x, self.rect_size.y) * 0.5;
                let glow_alpha = exp(-max(dist - r, 0.0) * 0.22)
                    * (1.0 - smoothstep(edge - 3.5, edge, dist))
                    * 0.23 * (0.6 + 0.4 * self.morph);

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

    WaveProgress = <View> {
        width: Fill,
        height: 32,
        show_bg: true,
        draw_bg: {
            instance progress: 0.0,
            instance playing: 0.0,
            instance phase: 0.0,
            uniform accent: #a8c7fa,
            uniform scale: 1.0,
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
                let amp = 3.2 * self.playing * self.scale;
                let wave_y = cy + sin(px * 0.185 + self.phase) * amp;
                let in_played = step(pad, px) * step(px, split_x);
                let played_d = abs(py - wave_y) - 1.9 * self.scale;
                let played_a = clamp(-played_d / 1.2 + 0.5, 0.0, 1.0) * in_played;

                // Straight unplayed track.
                let in_rest = step(split_x, px) * step(px, w - pad);
                let rest_d = abs(py - cy) - 1.5 * self.scale;
                let rest_a = clamp(-rest_d / 1.2 + 0.5, 0.0, 1.0) * in_rest;

                // Playhead thumb.
                let thumb_y = cy + sin(split_x * 0.185 + self.phase) * amp;
                let thumb_d = length(vec2(px - split_x, py - thumb_y)) - 6.0 * self.scale;
                let thumb_a = clamp(-thumb_d / 1.2 + 0.5, 0.0, 1.0);

                let played = max(played_a, thumb_a);
                let rest = rest_a * self.track_color.a * (1.0 - played);
                return vec4(self.accent.rgb * played + self.track_color.rgb * rest, played + rest);
            }
        }
    }

    Heading = <Label> {
        width: Fill, height: Fit, padding: 0,
        draw_text: { color: #f7f5ff, text_style: <FONT_OUTFIT_EXTRABOLD> { font_size: 26 } }
    }
    Caption = <Label> {
        width: Fill, height: Fit, padding: 0, flow: Right,
        draw_text: { color: #c0bcd4, text_style: <FONT_OUTFIT_BOLD> { font_size: 12 } }
    }
    TrackText = <Caption> {
        draw_text: { color: #f7f5ff, text_style: { font_size: 14 } }
    }
    Pill = <Button> {
        width: Fit, height: 44, padding: {left: 12, right: 12},
        draw_text: {
            text_style: <FONT_OUTFIT_BOLD> {font_size: 12}
            fn get_color(self) -> vec4 { return #f7f5ff; }
        }
        draw_bg: {
            border_radius: 11.0,
            color: #36394f, color_hover: #484b67, color_down: #565174,
            border_size: 0.0,
        }
    }
    Art = <Image> {
        width: 48, height: 48, fit: Biggest,
        draw_bg: {
            uniform ready: 0.0,
            uniform round: 0.0,
            fn pixel(self) -> vec4 {
                let sdf = Sdf2d::viewport(self.pos * self.rect_size);
                if self.round > 0.5 {
                    let center = self.rect_size * 0.5;
                    sdf.circle(center.x, center.y, min(center.x, center.y));
                } else {
                    sdf.box(0.0, 0.0, self.rect_size.x, self.rect_size.y, 10.0);
                }
                let art = self.get_color();
                sdf.fill(mix(mix(#45365f, #24203f, self.pos.y), vec4(art.rgb, 1.0), self.ready));
                return sdf.result;
            }
        }
    }
    ItemRow = <View> {
        width: Fill, height: 66, flow: Overlay, padding: {left: 20, right: 20},
        <View> {
            width: Fill, height: Fill, flow: Right, spacing: 12, align: {y: 0.5},
            art = <Art> {}
            <View> {
                width: Fill, height: Fit, flow: Down, spacing: 3,
                name = <TrackText> {}
                detail = <Caption> {}
            }
        }
        pick = <Button> { width: Fill, height: Fill, text: "", draw_bg: {fn pixel(self) -> vec4 {return vec4(0.)}} }
    }
    CarouselCard = <View> {
        width: 120, height: 175, flow: Overlay, margin: {right: 12},
        <View> {
            width: Fill, height: Fill, flow: Down, spacing: 6,
            art = <Art> {width: 120, height: 120}
            name = <TrackText> {flow: RightWrap, draw_text: {text_style: {font_size: 12}}}
        }
        pick = <Button> { width: Fill, height: Fill, text: "", draw_bg: {fn pixel(self) -> vec4 {return vec4(0.)}} }
    }
    Shelf = <View> {
        width: Fill, height: 234, flow: Down, spacing: 8,
        <View> {
            width: Fill, height: 38, flow: Right, spacing: 10, align: {y: 0.5},
            padding: {left: 20, right: 20},
            section_title = <Heading> {width: Fit, draw_text: {text_style: {font_size: 18}}}
            <View> {
                width: Fill, height: 12, show_bg: true,
                draw_bg: {
                    fn pixel(self) -> vec4 {
                        let p = self.pos * self.rect_size;
                        let d = abs(p.y - 6.0 - sin(p.x * 0.55) * 1.3);
                        let alpha = (1.0 - smoothstep(0.4, 1.2, d)) * 0.47;
                        return vec4(#b5aecf.rgb * alpha, alpha);
                    }
                }
            }
            see_all = <Pill> { text: "See all", height: 36 }
        }
        cards = <PortalList> {
            width: Fill, height: 184, flow: Right, padding: {left: 20},
            Card = <CarouselCard> {}
        }
    }
    ShortcutTile = <RoundedView> {
        width: Fill, height: 64, flow: Overlay,
        draw_bg: {color: #36394f, border_radius: 15.0}
        tile_content = <View> {
            width: Fill, height: Fill, flow: Right, align: {y: 0.5}, spacing: 8,
            padding: {left: 9, right: 8},
            emblem = <IconBtn> {
                width: 32, height: 32, enabled: false,
                icon_walk: {width: 17, height: 17},
                draw_bg: {bg_color: #553f6e},
                draw_icon: {color: #e0bcfa}
            }
            tile_text = <View> {
                width: Fill, height: Fit, flow: Down, spacing: 3,
                shortcut_title = <TrackText> {draw_text: {text_style: {font_size: 11.5}}}
                shortcut_detail = <Caption> {draw_text: {text_style: {font_size: 9.5}}}
            }
        }
        shortcut_press = <Button> {
            width: Fill, height: Fill, text: "",
            draw_bg: {
                instance hover: 0.0,
                instance down: 0.0,
                fn pixel(self) -> vec4 {
                    let sdf = Sdf2d::viewport(self.pos * self.rect_size);
                    sdf.box(0.0, 0.0, self.rect_size.x, self.rect_size.y, 15.0);
                    sdf.fill(vec4(1.0, 1.0, 1.0, self.hover * 0.05 + self.down * 0.08));
                    return sdf.result;
                }
            }
        }
    }
    HomeHero = <View> {
        width: Fill, height: Fit, flow: Down, spacing: 12,
        padding: {left: 20, right: 20, top: 6, bottom: 16},
        <View> {
            width: Fill, height: 170, flow: Overlay,
            hero_backdrop = <Image> {
                width: Fill, height: Fill, fit: Stretch,
                draw_bg: {
                    uniform ready: 0.0,
                    fn pixel(self) -> vec4 {
                        let sdf = Sdf2d::viewport(self.pos * self.rect_size);
                        sdf.box(0.0, 0.0, self.rect_size.x, self.rect_size.y, 23.0);
                        let art = self.get_color().rgb;
                        let base = mix(#282a3e, #353953, self.pos.x);
                        let light = smoothstep(0.0, 1.0, self.pos.x);
                        sdf.fill(mix(base, vec4(art * 0.40 + base.rgb * 0.70, 1.0), self.ready * light));
                        return sdf.result;
                    }
                }
            }
            <View> {
                width: Fill, height: Fill, flow: Right, align: {y: 0.5}, spacing: 14,
                padding: 16,
                art = <Art> {width: 104, height: 104}
                <View> {
                    width: Fill, height: Fit, flow: Down, spacing: 4,
                    <Caption> {
                        text: "NOW PLAYING"
                        draw_text: {color: #dfb7f8, text_style: {font_size: 9}}
                    }
                    name = <TrackText> {flow: RightWrap, draw_text: {text_style: {font_size: 17}}}
                    detail = <Caption> {}
                    <View> {
                        width: Fill, height: 44, flow: Right, align: {y: 0.5}, spacing: 8,
                        hero_play = <ScallopPlayBtn> {
                            width: 42, height: 42,
                            draw_bg: {accent: #d8abf6, on_accent: #35253f}
                        }
                        open = <Pill> {text: "Open", height: 36}
                    }
                    hero_wave = <WaveProgress> {
                        width: Fill, height: 15,
                        draw_bg: {accent: #d8abf6, track_color: #e9c3ff38, scale: 0.55}
                    }
                }
            }
        }
        <View> {
            width: Fill, height: Fit, flow: Right, spacing: 8,
            liked_tile = <ShortcutTile> {
                tile_content = {
                    emblem = {draw_icon: {svg_file: dep("crate://self/resources/icons/favorite.svg")}}
                    tile_text = {
                        shortcut_title = {text: "Liked Songs"}
                        shortcut_detail = {text: "Your favourites"}
                    }
                }
            }
            saved_tile = <ShortcutTile> {
                tile_content = {
                    emblem = {draw_icon: {svg_file: dep("crate://self/resources/icons/library.svg")}}
                    tile_text = {
                        shortcut_title = {text: "Your Library"}
                        shortcut_detail = {text: "Saved music & shows"}
                    }
                }
            }
        }
        home_status = <View> {
            width: Fill, height: Fit,
            home_message = <Caption> {text: "Loading your library…"}
        }
    }

    pub App = {{App}} {
        ui: <Root> {
            main_window = <Window> {
                window: { title: "Spatch", inner_size: vec2(412, 915) }
                show_bg: true,
                draw_bg: { color: #202232 }
                body = <View> {
                    width: Fill,
                    height: Fill,
                    flow: Overlay,

                    browse = <View> {
                        width: Fill, height: Fill, flow: Down,
                        padding: {top: 36, bottom: 24}, spacing: 8,
                        <View> {
                            width: Fill, height: 54, flow: Right, align: {y: 0.5},
                            padding: {left: 20, right: 20},
                            browse_title = <Heading> {
                                text: "Spatch"
                                draw_text: {color: #e6dfff, text_style: {font_size: 32}}
                            }
                            exit_btn = <IconBtn> {
                                width: 44, height: 44,
                                icon_walk: {width: 20, height: 20},
                                draw_icon: {svg_file: dep("crate://self/resources/icons/close.svg")}
                            }
                        }
                        home_page = <View> {
                            width: Fill, height: Fill,
                            home_list = <PortalList> {
                                width: Fill, height: Fill,
                                Hero = <HomeHero> {}
                                Section = <Shelf> {}
                            }
                        }
                        library_page = <View> {
                            visible: false, width: Fill, height: Fill, flow: Down, spacing: 8,
                            <View> {
                                width: Fill, height: Fit, flow: RightWrap, spacing: 6,
                                padding: {left: 20, right: 20},
                                all_btn = <Pill> {text: "All"}
                                playlists_btn = <Pill> {text: "Playlists"}
                                albums_btn = <Pill> {text: "Albums"}
                                artists_btn = <Pill> {text: "Artists"}
                                podcasts_btn = <Pill> {text: "Podcasts"}
                            }
                            <View> {
                                width: Fill, height: Fit, padding: {left: 20, right: 20},
                                library_message = <Caption> {text: "Loading your library…"}
                            }
                            library_list = <PortalList> { width: Fill, height: Fill, Row = <ItemRow> {} }
                        }
                        <RoundedView> {
                            width: Fill, height: 66, flow: Right, align: {y: 0.5}, spacing: 10,
                            margin: {left: 16, right: 16}, padding: {left: 10, right: 10},
                            draw_bg: {color: #36394f, border_radius: 12.}
                            mini_art = <Art> {width: 41, height: 41}
                            <View> {
                                width: Fill, height: Fill, flow: Overlay,
                                <View> {
                                    width: Fill, height: Fill, flow: Down, align: {y: 0.5},
                                    mini_title = <TrackText> {text: "Nothing playing"}
                                    mini_artist = <Caption> {}
                                    mini_wave = <WaveProgress> {
                                        width: Fill, height: 8, draw_bg: {accent: #d8abf6, scale: 0.42}
                                    }
                                }
                                mini_open = <Button> {width: Fill, height: Fill, text: "", draw_bg: {fn pixel(self) -> vec4 {return vec4(0.)}}}
                            }
                            <View> {
                                width: Fit, height: Fit, flow: Right, align: {y: 0.5},
                                mini_previous = <IconBtn> {
                                    width: 43, height: 48, icon_walk: {width: 20, height: 20},
                                    draw_bg: {bg_color: #ffffff00},
                                    draw_icon: {svg_file: dep("crate://self/resources/icons/skip-previous.svg")}
                                }
                                mini_play = <ScallopPlayBtn> {
                                    width: 46, height: 48, draw_bg: {accent: #d8abf6, on_accent: #35253f}
                                }
                                mini_next = <IconBtn> {
                                    width: 43, height: 48, icon_walk: {width: 20, height: 20},
                                    draw_bg: {bg_color: #ffffff00},
                                    draw_icon: {svg_file: dep("crate://self/resources/icons/skip-next.svg")}
                                }
                            }
                        }
                        <View> {
                            width: Fill, height: 48, flow: Right, spacing: 8,
                            padding: {left: 16, right: 16},
                            home_btn = <Pill> {width: Fill, text: "Home"}
                            library_btn = <Pill> {width: Fill, text: "Library"}
                        }
                    }
                    queue_page = <View> {
                        visible: false, width: Fill, height: Fill, flow: Down, spacing: 10,
                        padding: {top: 36, bottom: 24},
                        <View> {
                            width: Fill, height: 54, flow: Right, align: {y: 0.5},
                            padding: {left: 20, right: 20},
                            queue_back = <IconBtn> {draw_icon: {svg_file: dep("crate://self/resources/icons/back.svg")}}
                            <Heading> {text: "Up Next", margin: {left: 12}}
                        }
                        queue_status = <View> {
                            width: Fill, height: Fit, padding: {left: 20, right: 20},
                            queue_message = <Caption> {text: "The queue is empty"}
                        }
                        queue_list = <PortalList> {
                            width: Fill, height: Fill,
                            Row = <ItemRow> {pick = {visible: false}}
                        }
                    }
                    player_page = <View> {
                        visible: false, width: Fill, height: Fill, flow: Overlay,

                        backdrop = <Image> {
                            width: Fill,
                            height: Fill,
                            fit: Stretch,
                            draw_bg: {
                                uniform has_tex: 0.0,
                                uniform immersive: 0.0,
                                uniform tint: #0c101b,
                                fn pixel(self) -> vec4 {
                                    if self.immersive < 0.5 {
                                        return mix(#272942, #191a29, self.pos.y);
                                    }
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
                                    let darkened = mix(c * 0.48, self.tint.rgb * 0.50, 0.30);
                                    let vignette = 1.0 - 0.35 * length(self.pos - vec2(0.5, 0.45));
                                    let glow = 0.07 * (1.0 - smoothstep(0.05, 0.72, length(self.pos - vec2(0.5, 0.36))));
                                    return vec4(darkened * vignette + c * glow, 1.0);
                                }
                            }
                        }

                        content = <View> {
                            width: Fill,
                            height: Fill,
                            flow: Down,
                            align: {x: 0.5, y: 0.5},
                            padding: {left: 20, right: 20, top: 36, bottom: 24},
                            spacing: 12,

                            cover_wrap = <View> {
                                width: 264,
                                height: 264,
                                flow: Overlay,
                                align: {x: 0.5, y: 0.5},

                                cover_ring = <View> {
                                    visible: false,
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
                                            let base = min(self.rect_size.x, self.rect_size.y) * 0.435;
                                            let wave = sin(a * 12.0 - self.phase * 2.2) * 2.6 * self.playing;
                                            let ring_d = abs(d - (base + wave)) - 1.3;
                                            let ring_a = clamp(-ring_d / 1.4 + 0.5, 0.0, 1.0) * 0.72;
                                            let glow = exp(-abs(d - base) * 0.25) * 0.16 * (0.4 + 0.6 * self.playing);
                                            let glow_a = glow * (1.0 - smoothstep(base + 4.0, base + 7.0, d));
                                            let tick_start = base + 5.5;
                                            let sector = abs(fract(a / 6.283185 * 64.0 + 0.5) - 0.5);
                                            let strength = 0.55 + 0.45 * sin(a * 5.0 + self.phase * 2.0);
                                            let tick_end = tick_start + mix(3.0, 5.0 + 7.0 * strength, self.playing);
                                            let tick = (1.0 - smoothstep(0.12, 0.25, sector))
                                                * smoothstep(tick_start, tick_start + 1.7, d)
                                                * (1.0 - smoothstep(tick_end - 1.7, tick_end, d));
                                            let alpha = max(max(ring_a, glow_a), tick * 0.62);
                                            return vec4(self.accent.rgb * alpha, alpha);
                                        }
                                    }
                                }

                                cover = <Image> {
                                    width: 234,
                                    height: 234,
                                    fit: Biggest,
                                    draw_bg: {
                                        uniform has_tex: 0.0,
                                        uniform immersive: 0.0,
                                        uniform accent: #a8c7fa,
                                        fn pixel(self) -> vec4 {
                                            let p = (self.pos - vec2(0.5, 0.5)) * self.rect_size;
                                            let angle = atan(p.y, p.x);
                                            let r = min(self.rect_size.x, self.rect_size.y) * 0.465;
                                            let edge = r + cos(angle * 12.0) * 3.0;
                                            let tex_col = mix(
                                                vec4(self.accent.rgb * 0.25, 1.0),
                                                self.get_color(),
                                                self.has_tex
                                            );
                                            if self.immersive < 0.5 {
                                                let sdf = Sdf2d::viewport(self.pos * self.rect_size);
                                                sdf.box(0.0, 0.0, self.rect_size.x, self.rect_size.y, 20.0);
                                                sdf.fill(tex_col);
                                                return sdf.result;
                                            }
                                            let alpha = clamp((edge - length(p)) / 1.2 + 0.5, 0.0, 1.0);
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
                                    padding: 0,
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
                                    padding: 0,
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
                                album_label = <Caption> {
                                    align: {x: 0.5},
                                    draw_text: {color: #e2e6f399, text_style: {font_size: 11}}
                                }
                            }

                            progress_section = <View> {
                                width: Fill, height: 44, flow: Right,
                                align: {y: 0.5}, spacing: 5,
                                elapsed_label = <Caption> {width: Fit, text: "0:00"}
                                progress_bar = <WaveProgress> { width: Fill }
                                duration_label = <Caption> {width: Fit, text: "−0:00"}
                            }

                            controls = <View> {
                                width: Fill,
                                height: Fit,
                                flow: Right,
                                align: {x: 0.5, y: 0.5},
                                spacing: 8,

                                shuffle_btn = <IconBtn> {
                                    draw_bg: {bg_color: #ffffff00}
                                    width: 42,
                                    height: 42,
                                    icon_walk: {width: 20, height: 20},
                                    draw_icon: {
                                        svg_file: dep("crate://self/resources/icons/shuffle.svg"),
                                        color: #ffffff99,
                                    }
                                }

                                prev_btn = <IconBtn> {
                                    draw_bg: {bg_color: #ffffff00}
                                    width: 54,
                                    height: 54,
                                    icon_walk: {width: 28, height: 28},
                                    draw_icon: {
                                        svg_file: dep("crate://self/resources/icons/skip-previous.svg"),
                                    }
                                }

                                play_btn = <ScallopPlayBtn> {
                                    draw_bg: {accent: #f4f3ff, on_accent: #202538}
                                }

                                next_btn = <IconBtn> {
                                    draw_bg: {bg_color: #ffffff00}
                                    width: 54,
                                    height: 54,
                                    icon_walk: {width: 28, height: 28},
                                    draw_icon: {
                                        svg_file: dep("crate://self/resources/icons/skip-next.svg"),
                                    }
                                }

                                repeat_btn = <IconBtn> {
                                    draw_bg: {bg_color: #ffffff00}
                                    width: 42,
                                    height: 42,
                                    icon_walk: {width: 20, height: 20},
                                    draw_icon: {
                                        svg_file: dep("crate://self/resources/icons/repeat.svg"),
                                        color: #ffffff99,
                                    }
                                }
                            }
                            immersive_hint = <View> {
                                width: Fill, height: Fit,
                                <Caption> {
                                    text: "Double-tap artwork for immersive mode"
                                    align: {x: 0.5}
                                }
                            }
                        }
                        top_bar = <View> {
                            width: Fill, height: 48, flow: Right, align: {y: 0.5},
                            margin: {top: 36}, padding: {left: 20, right: 20},
                            close_btn = <IconBtn> {
                                draw_icon: {svg_file: dep("crate://self/resources/icons/back.svg")}
                            }
                            status_label = <Caption> {
                                text: "NOW PLAYING", align: {x: 0.5}, flow: RightWrap,
                            }
                            queue_btn = <IconBtn> {
                                draw_icon: {svg_file: dep("crate://self/resources/icons/queue.svg")}
                            }
                        }
                    }
                }
            }
        }
    }
}

app_main!(App);

#[derive(Clone, Copy, Default, PartialEq, Eq)]
enum Page {
    #[default]
    Home,
    Library,
    Player,
    Immersive,
    Queue,
}

impl Page {
    fn is_player(self) -> bool {
        matches!(self, Self::Player | Self::Immersive)
    }
}

#[cfg(target_os = "android")]
static SAVED: Mutex<Option<Result<Vec<library::Item>, String>>> = Mutex::new(None);

struct Artwork {
    cover: Texture,
    backdrop: Texture,
    seed: Option<u32>,
}

impl Artwork {
    fn new(cx: &mut Cx, rgba: &[u8], width: u32, height: u32) -> Self {
        let (blurred, size) = images::backdrop(rgba, width, height);
        Self {
            cover: rgba_to_texture(cx, rgba, width, height),
            backdrop: rgba_to_texture(cx, &blurred, size, size),
            seed: colors::seed(rgba),
        }
    }
}

#[derive(Live, LiveHook)]
pub struct App {
    #[live]
    ui: WidgetRef,
    #[rust]
    now: NowPlaying,
    #[rust]
    page: Page,
    #[rust]
    browse_page: Page,
    #[rust]
    queue_return: Page,
    #[rust]
    saved: Vec<library::Item>,
    #[rust]
    filtered: Vec<usize>,
    #[rust]
    shelves: Vec<(&'static str, Vec<usize>)>,
    #[rust]
    library_message: String,
    #[rust]
    art: HashMap<String, Artwork>,
    #[rust]
    loaded_art_url: String,
    #[cfg(target_os = "android")]
    #[rust]
    subscribed: bool,
    #[rust]
    morph: f32,
    #[rust]
    phase: f32,
    #[rust]
    next_frame: NextFrame,
    #[rust]
    last_tick: f64,
    #[rust]
    clock: Timer,
}

impl LiveRegister for App {
    fn live_register(cx: &mut Cx) {
        makepad_widgets::live_design(cx);
    }
}

impl MatchEvent for App {
    fn handle_startup(&mut self, cx: &mut Cx) {
        self.morph = 1.0;
        self.library_message = "Loading your library…".into();
        self.show_page(cx, Page::Home);
        self.refresh_mini(cx);
        self.last_tick = 0.0;

        #[cfg(target_os = "android")]
        {
            self.start_subscription();
            std::thread::spawn(|| {
                let request = library::request(300);
                let result = loop {
                    match core::call(library::SERVICE, "All", &request) {
                        Err(error) if error.contains("isn't running yet") => {
                            std::thread::sleep(std::time::Duration::from_millis(300));
                        }
                        result => break result.and_then(|bytes| library::decode(&bytes)),
                    }
                };
                *SAVED.lock().unwrap() = Some(result);
                core::wake();
            });
        }

        #[cfg(not(target_os = "android"))]
        {
            self.load_demo(cx);
        }

        self.next_frame = cx.new_next_frame();
        self.clock = cx.start_interval(0.5);
    }

    fn handle_next_frame(&mut self, cx: &mut Cx, e: &NextFrameEvent) {
        if !e.set.contains(&self.next_frame) || self.page == Page::Queue {
            return;
        }
        let dt = if self.last_tick > 0.0 {
            ((e.time - self.last_tick) as f32).clamp(0.0, 0.1)
        } else {
            1.0 / 60.0
        };
        self.last_tick = e.time;

        let target = f32::from(self.now.playing);
        let morphing = (self.morph - target).abs() > 0.005;
        if morphing {
            self.morph += (target - self.morph) * (dt * 10.0).min(1.0);
            if (self.morph - target).abs() <= 0.005 {
                self.morph = target;
            }
            if self.page.is_player() {
                self.ui
                    .button(id!(play_btn))
                    .apply_over(cx, live! {draw_bg: {morph: (self.morph)}});
            }
        }
        if self.now.playing {
            self.phase = (self.phase + dt * 3.9) % std::f32::consts::TAU;
        }
        let progress = self.current_progress();
        if self.page.is_player() {
            Self::paint_wave(
                cx,
                &self.ui.view(id!(progress_bar)),
                progress,
                target,
                self.phase,
            );
            if self.page == Page::Immersive {
                Self::paint_shader(
                    cx,
                    &self.ui.view(id!(cover_ring)),
                    &[(live_id!(phase), self.phase), (live_id!(playing), target)],
                );
            }
        } else {
            Self::paint_wave(
                cx,
                &self.ui.view(id!(mini_wave)),
                progress,
                target,
                self.phase,
            );
            if self.page == Page::Home {
                if let Some((_, hero)) = self.ui.portal_list(id!(home_list)).get_item(0) {
                    Self::paint_wave(cx, &hero.view(id!(hero_wave)), progress, target, self.phase);
                }
            }
        }

        let animating = self.now.playing || morphing;
        if animating {
            self.next_frame = cx.new_next_frame();
        }
    }

    fn handle_timer(&mut self, cx: &mut Cx, event: &TimerEvent) {
        if self.clock.is_timer(event).is_none() {
            return;
        }
        if self.page.is_player() {
            self.refresh_time_labels(cx);
        }
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
        #[cfg(target_os = "android")]
        if let Some(result) = SAVED.lock().unwrap().take() {
            self.receive_library(cx, result);
        }
        for (url, decoded) in images::take_ready() {
            if let Some((w, h, rgba)) = decoded {
                self.art.insert(url.clone(), Artwork::new(cx, &rgba, w, h));
                if url == self.now.image {
                    self.refresh_player_art(cx, &url);
                    self.refresh_mini(cx);
                }
                self.ui.redraw(cx);
            }
        }
    }

    fn handle_actions(&mut self, cx: &mut Cx, actions: &Actions) {
        if actions.iter().any(|action| {
            action.as_widget_action().is_some_and(|action| {
                matches!(action.cast::<PortalListAction>(), PortalListAction::Scroll)
            })
        }) {
            self.ui.redraw(cx);
        }
        let hero_play = self
            .ui
            .portal_list(id!(home_list))
            .get_item(0)
            .is_some_and(|(_, hero)| hero.button(id!(hero_play)).clicked(actions));
        if hero_play
            || self.ui.button(id!(play_btn)).clicked(actions)
            || self.ui.button(id!(mini_play)).clicked(actions)
        {
            let cmd = if self.now.playing {
                player::Command::Pause
            } else {
                player::Command::Resume
            };
            let now = now_ms();
            self.now.position_ms = self.now.position_at(now);
            self.now.timestamp = now;
            self.now.playing = !self.now.playing;
            self.send_command(cmd);
            self.refresh_mini(cx);
            self.next_frame = cx.new_next_frame();
        }
        if self.ui.button(id!(prev_btn)).clicked(actions)
            || self.ui.button(id!(mini_previous)).clicked(actions)
        {
            self.send_command(player::Command::Previous);
        }
        if self.ui.button(id!(next_btn)).clicked(actions)
            || self.ui.button(id!(mini_next)).clicked(actions)
        {
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
        if self.ui.button(id!(home_btn)).clicked(actions) {
            self.show_page(cx, Page::Home);
        }
        if self.ui.button(id!(library_btn)).clicked(actions) {
            self.show_page(cx, Page::Library);
        }
        if self.ui.button(id!(mini_open)).clicked(actions) {
            self.show_page(cx, Page::Player);
        }
        if self.ui.button(id!(queue_back)).clicked(actions) {
            self.show_page(cx, self.queue_return);
        }
        if self.ui.button(id!(queue_btn)).clicked(actions) {
            self.queue_return = self.page;
            self.ui
                .portal_list(id!(queue_list))
                .set_first_id_and_scroll(0, 0.0);
            self.show_page(cx, Page::Queue);
        }
        if self.ui.button(id!(close_btn)).clicked(actions) {
            self.show_page(
                cx,
                if self.page == Page::Immersive {
                    Page::Player
                } else {
                    self.browse_page
                },
            );
        }
        if self.ui.button(id!(exit_btn)).clicked(actions) {
            #[cfg(target_os = "android")]
            core::close_app();
            #[cfg(not(target_os = "android"))]
            cx.quit();
        }

        if self.page == Page::Home {
            let list = self.ui.portal_list(id!(home_list));
            if let Some((_, hero)) = list.get_item(0) {
                if hero.button(id!(open)).clicked(actions) {
                    self.show_page(cx, Page::Player);
                }
                if hero.button(id!(saved_tile.shortcut_press)).clicked(actions) {
                    self.show_page(cx, Page::Library);
                }
                if hero.button(id!(liked_tile.shortcut_press)).clicked(actions) {
                    if let Some(uri) = self.liked_song_uri() {
                        self.send_command(player::Command::Play(uri.into()));
                    }
                }
            }
            let mut open_filter = None;
            for (shelf_index, (_, entries)) in self.shelves.iter().enumerate() {
                if let Some((_, shelf)) = list.get_item(shelf_index + 1) {
                    if shelf.button(id!(see_all)).clicked(actions) {
                        open_filter = entries.first().and_then(|&i| self.saved[i].kind.chip());
                        break;
                    }
                    for (i, row) in shelf.portal_list(id!(cards)).items_with_actions(actions) {
                        if row.button(id!(pick)).clicked(actions) {
                            if let Some(&index) = entries.get(i) {
                                self.play_saved(index);
                            }
                        }
                    }
                }
            }
            if let Some(filter) = open_filter {
                self.set_filter(cx, Some(filter));
                self.show_page(cx, Page::Library);
            }
        }
        if self.page == Page::Library {
            for (button, filter) in [
                (id!(all_btn), None),
                (id!(playlists_btn), Some(0)),
                (id!(albums_btn), Some(1)),
                (id!(artists_btn), Some(2)),
                (id!(podcasts_btn), Some(3)),
            ] {
                if self.ui.button(button).clicked(actions) {
                    self.set_filter(cx, filter);
                }
            }
            for (i, row) in self
                .ui
                .portal_list(id!(library_list))
                .items_with_actions(actions)
            {
                if row.button(id!(pick)).clicked(actions) {
                    if let Some(&index) = self.filtered.get(i) {
                        self.play_saved(index);
                    }
                }
            }
        }
    }
}

impl AppMain for App {
    fn handle_event(&mut self, cx: &mut Cx, event: &Event) {
        if let Event::Draw(event) = event {
            self.draw_lists(cx, event);
            return;
        }
        self.match_event(cx, event);
        if self.page == Page::Player {
            let artwork = self.ui.view(id!(cover_wrap)).area();
            if let Hit::FingerUp(e) = event.hits(cx, artwork) {
                if e.is_primary_hit() && e.is_over && e.tap_count == 2 {
                    self.show_page(cx, Page::Immersive);
                }
            }
        }
        if self.page.is_player() && self.now.duration_ms > 0 {
            let area = self.ui.view(id!(progress_bar)).area();
            if let Hit::FingerUp(e) = event.hits(cx, area) {
                if e.is_primary_hit() {
                    let ms =
                        seek_position(e.abs.x - e.rect.pos.x, e.rect.size.x, self.now.duration_ms);
                    self.now.position_ms = ms;
                    self.now.timestamp = now_ms();
                    self.send_command(player::Command::SeekTo(ms));
                    self.refresh_time_labels(cx);
                    self.next_frame = cx.new_next_frame();
                }
            }
        }
        self.ui.handle_event(cx, event, &mut Scope::empty());
    }
}

impl App {
    fn draw_lists(&mut self, cx: &mut Cx, event: &DrawEvent) {
        let home = self.ui.portal_list(id!(home_list));
        let library = self.ui.portal_list(id!(library_list));
        let queue = self.ui.portal_list(id!(queue_list));
        let mut draw = CxDraw::new(cx, event);
        let mut cx = Cx2d::new(&mut draw);
        while let Some(step) = self.ui.draw(&mut cx, &mut Scope::empty()).step() {
            if step.widget_uid() == home.widget_uid() {
                let Some(mut list) = home.borrow_mut() else {
                    continue;
                };
                list.set_item_range(&mut cx, 0, 1 + self.shelves.len());
                while let Some(index) = list.next_visible_item(&mut cx) {
                    if index == 0 {
                        let hero = list.item(&mut cx, index, live_id!(Hero));
                        self.fill_row(
                            &mut cx,
                            &hero,
                            self.track_title(),
                            &self.now.artist,
                            &self.now.image,
                            false,
                        );
                        let status = hero.view(id!(home_status));
                        let has_message = !self.library_message.is_empty();
                        if status.visible() != has_message {
                            status.set_visible(&mut cx, has_message);
                        }
                        let label = hero.label(id!(home_message));
                        if label.text() != self.library_message {
                            label.set_text(&mut cx, &self.library_message);
                        }
                        let liked = hero.button(id!(liked_tile.shortcut_press));
                        let available = self.liked_song_uri().is_some();
                        if liked.disabled(&cx) == available {
                            liked.set_enabled(&mut cx, available);
                        }
                        let hero_play = hero.button(id!(hero_play));
                        let playing_available = !self.now.uri.is_empty();
                        if hero_play.disabled(&cx) == playing_available {
                            hero_play.set_enabled(&mut cx, playing_available);
                        }
                        let backdrop = hero.image(id!(hero_backdrop));
                        let texture = self
                            .art
                            .get(&self.now.image)
                            .map(|art| art.backdrop.clone());
                        let binding = (self.now.image.clone(), texture.is_some());
                        if hero_play
                            .action_data()
                            .as_ref()
                            .and_then(|data| data.downcast_ref())
                            != Some(&binding)
                        {
                            let ready = f32::from(binding.1);
                            backdrop.set_texture(&mut cx, texture);
                            backdrop.apply_over(&mut cx, live! {draw_bg: {ready: (ready)}});
                            hero_play.set_action_data(binding);
                            self.refresh_home_progress(&mut cx, &hero);
                        }
                        hero.draw_all_unscoped(&mut cx);
                        continue;
                    }
                    let Some((title, entries)) = self.shelves.get(index - 1) else {
                        continue;
                    };
                    let mut shelf = list.item(&mut cx, index, live_id!(Section));
                    let heading = shelf.label(id!(section_title));
                    if heading.text() != *title {
                        heading.set_text(&mut cx, title);
                    }
                    while let Some(step) = shelf.draw(&mut cx, &mut Scope::empty()).step() {
                        let cards = shelf.portal_list(id!(cards));
                        if step.widget_uid() == cards.widget_uid() {
                            self.draw_rows(&mut cx, &cards, entries.len(), live_id!(Card), |i| {
                                self.saved_row(entries[i])
                            });
                        }
                    }
                }
            } else if step.widget_uid() == library.widget_uid() {
                self.draw_rows(&mut cx, &library, self.filtered.len(), live_id!(Row), |i| {
                    self.saved_row(self.filtered[i])
                });
            } else if step.widget_uid() == queue.widget_uid() {
                self.draw_rows(
                    &mut cx,
                    &queue,
                    self.now.up_next.len(),
                    live_id!(Row),
                    |i| {
                        let (name, detail, image) = &self.now.up_next[i];
                        (name, detail, image, false)
                    },
                );
            }
        }
    }

    fn draw_rows<'a>(
        &self,
        cx: &mut Cx2d,
        list: &PortalListRef,
        count: usize,
        template: LiveId,
        row: impl Fn(usize) -> (&'a str, &'a str, &'a str, bool),
    ) {
        let Some(mut list) = list.borrow_mut() else {
            return;
        };
        list.set_item_range(cx, 0, count);
        while let Some(index) = list.next_visible_item(cx) {
            if index < count {
                let item = list.item(cx, index, template);
                let (name, detail, image, round) = row(index);
                self.fill_row(cx, &item, name, detail, image, round);
                item.draw_all_unscoped(cx);
            }
        }
    }

    fn saved_row(&self, index: usize) -> (&str, &str, &str, bool) {
        let item = &self.saved[index];
        (
            &item.name,
            &item.subtitle,
            &item.image,
            item.kind == library::Kind::Artist,
        )
    }

    fn fill_row(
        &self,
        cx: &mut Cx,
        widget: &WidgetRef,
        name: &str,
        detail: &str,
        url: &str,
        round: bool,
    ) {
        for (id, text) in [(id!(name), name), (id!(detail), detail)] {
            let label = widget.label(id);
            let text = if text.is_empty() { " " } else { text };
            if label.text() != text {
                label.set_text(cx, text);
            }
        }
        let binding = (url.to_owned(), round, self.art.contains_key(url));
        let key = widget.button(id!(pick));
        let key = if key.is_empty() {
            widget.button(id!(open))
        } else {
            key
        };
        if key
            .action_data()
            .as_ref()
            .and_then(|data| data.downcast_ref())
            != Some(&binding)
        {
            self.set_tile_art(cx, &widget.image(id!(art)), url, round);
            key.set_action_data(binding);
        }
    }

    fn set_tile_art(&self, cx: &mut Cx, image: &ImageRef, url: &str, round: bool) {
        let texture = self.art.get(url).map(|art| art.cover.clone());
        if texture.is_none() {
            images::request(url, 256);
        }
        let ready = f32::from(texture.is_some());
        let round = f32::from(round);
        image.set_texture(cx, texture);
        image.apply_over(cx, live! {draw_bg: {ready: (ready), round: (round)}});
    }

    fn paint_wave(cx: &mut Cx, view: &ViewRef, progress: f32, playing: f32, phase: f32) {
        Self::paint_shader(
            cx,
            view,
            &[
                (live_id!(progress), progress),
                (live_id!(playing), playing),
                (live_id!(phase), phase),
            ],
        );
    }

    fn paint_shader(cx: &mut Cx, view: &ViewRef, values: &[(LiveId, f32)]) {
        let Some(view) = view.borrow() else { return };
        let area = view.draw_bg.area();
        // Updating the existing draw call keeps finger capture stable while the wave moves.
        for &(id, value) in values {
            if let Some(write) = area.get_write_ref(cx, id, ShaderTy::Float, "player animation") {
                for index in 0..write.repeat {
                    write.buffer[index * write.stride] = value;
                }
            }
        }
    }

    fn refresh_home_progress(&self, cx: &mut Cx, hero: &WidgetRef) {
        let playing = f32::from(self.now.playing);
        let progress = self.current_progress();
        let phase = self.phase;
        hero.button(id!(hero_play))
            .apply_over(cx, live! {draw_bg: {morph: (playing)}});
        hero.view(id!(hero_wave)).apply_over(
            cx,
            live! {
                draw_bg: {progress: (progress), playing: (playing), phase: (phase)}
            },
        );
    }

    fn track_title(&self) -> &str {
        if self.now.title.is_empty() {
            "Nothing playing"
        } else {
            &self.now.title
        }
    }

    fn liked_song_uri(&self) -> Option<&str> {
        self.saved
            .iter()
            .find(|item| item.uri.ends_with(":collection"))
            .map(|item| item.uri.as_str())
    }

    fn receive_library(&mut self, cx: &mut Cx, result: Result<Vec<library::Item>, String>) {
        self.library_message = match result {
            Ok(items) => {
                self.saved = items;
                self.shelves = [
                    (library::Kind::Playlist, "Playlists"),
                    (library::Kind::Album, "Albums"),
                    (library::Kind::Artist, "Artists"),
                    (library::Kind::Podcast, "Podcasts"),
                ]
                .into_iter()
                .filter_map(|(kind, title)| {
                    let entries: Vec<_> = self
                        .saved
                        .iter()
                        .enumerate()
                        .filter_map(|(i, item)| (item.kind == kind).then_some(i))
                        .collect();
                    (!entries.is_empty()).then_some((title, entries))
                })
                .collect();
                if self.saved.is_empty() {
                    "Your library is empty".into()
                } else {
                    String::new()
                }
            }
            Err(error) => {
                ::log::warn!("YourLibrary.All: {error}");
                "Couldn't load your library".into()
            }
        };
        self.set_filter(cx, None);
        self.ui.redraw(cx);
    }

    fn set_filter(&mut self, cx: &mut Cx, filter: Option<i32>) {
        self.filtered = self
            .saved
            .iter()
            .enumerate()
            .filter_map(|(i, item)| (filter.is_none() || item.kind.chip() == filter).then_some(i))
            .collect();
        let message = if !self.library_message.is_empty() {
            self.library_message.clone()
        } else if self.filtered.is_empty() {
            "No saved items in this category".into()
        } else {
            format!("{} saved", self.filtered.len())
        };
        self.ui.label(id!(library_message)).set_text(cx, &message);
        self.ui
            .portal_list(id!(library_list))
            .set_first_id_and_scroll(0, 0.0);
        for (button, selected) in [
            (id!(all_btn), filter.is_none()),
            (id!(playlists_btn), filter == Some(0)),
            (id!(albums_btn), filter == Some(1)),
            (id!(artists_btn), filter == Some(2)),
            (id!(podcasts_btn), filter == Some(3)),
        ] {
            self.select_button(cx, button, selected);
        }
        self.ui.redraw(cx);
    }

    fn select_button(&self, cx: &mut Cx, button: &[LiveId], selected: bool) {
        let color = if selected {
            vec4(0.40, 0.31, 0.49, 1.)
        } else {
            vec4(0.21, 0.22, 0.31, 1.)
        };
        self.ui
            .button(button)
            .apply_over(cx, live! {draw_bg: {color: (color)}});
    }

    fn show_page(&mut self, cx: &mut Cx, page: Page) {
        self.page = page;
        self.ui
            .view(id!(browse))
            .set_visible(cx, matches!(page, Page::Home | Page::Library));
        self.ui
            .view(id!(home_page))
            .set_visible(cx, page == Page::Home);
        self.ui
            .view(id!(library_page))
            .set_visible(cx, page == Page::Library);
        self.ui
            .view(id!(player_page))
            .set_visible(cx, page.is_player());
        let immersive = page == Page::Immersive;
        self.ui.view(id!(cover_ring)).set_visible(cx, immersive);
        self.ui
            .view(id!(immersive_hint))
            .set_visible(cx, page == Page::Player);
        let mode = f32::from(immersive);
        for id in [id!(backdrop), id!(cover)] {
            self.ui
                .image(id)
                .apply_over(cx, live! {draw_bg: {immersive: (mode)}});
        }
        self.ui
            .view(id!(queue_page))
            .set_visible(cx, page == Page::Queue);
        if matches!(page, Page::Home | Page::Library) {
            self.browse_page = page;
            self.ui.label(id!(browse_title)).set_text(
                cx,
                if page == Page::Home {
                    "Spatch"
                } else {
                    "Your Library"
                },
            );
            self.select_button(cx, id!(home_btn), page == Page::Home);
            self.select_button(cx, id!(library_btn), page == Page::Library);
        }
        if page.is_player() {
            let morph = self.morph;
            self.ui
                .button(id!(play_btn))
                .apply_over(cx, live! {draw_bg: {morph: (morph)}});
            self.refresh_time_labels(cx);
            let progress = self.current_progress();
            let playing = f32::from(self.now.playing);
            let phase = self.phase;
            self.ui.view(id!(progress_bar)).apply_over(
                cx,
                live! {
                    draw_bg: {progress: (progress), playing: (playing), phase: (phase)}
                },
            );
        }
        self.last_tick = 0.0;
        self.next_frame = cx.new_next_frame();
        self.ui.redraw(cx);
    }

    fn refresh_mini(&self, cx: &mut Cx) {
        let available = !self.now.uri.is_empty();
        for button in [id!(mini_play), id!(mini_previous), id!(mini_next)] {
            self.ui.button(button).set_enabled(cx, available);
        }
        let playing = f32::from(self.now.playing);
        self.ui
            .button(id!(mini_play))
            .apply_over(cx, live! {draw_bg: {morph: (playing)}});
        let progress = self.current_progress();
        let phase = self.phase;
        self.ui.view(id!(mini_wave)).apply_over(
            cx,
            live! {
                draw_bg: {progress: (progress), playing: (playing), phase: (phase)}
            },
        );
        if let Some((_, hero)) = self.ui.portal_list(id!(home_list)).get_item(0) {
            self.refresh_home_progress(cx, &hero);
        }
        self.ui
            .label(id!(mini_title))
            .set_text(cx, self.track_title());
        self.ui
            .label(id!(mini_artist))
            .set_text(cx, &self.now.artist);
        self.set_tile_art(cx, &self.ui.image(id!(mini_art)), &self.now.image, false);
    }

    fn play_saved(&self, index: usize) {
        let uri = &self.saved[index].uri;
        if !uri.is_empty() {
            self.send_command(player::Command::Play(uri.clone()));
        }
    }

    fn apply_now_playing(&mut self, cx: &mut Cx, state: player::NowPlaying) {
        if state.image != self.loaded_art_url {
            self.loaded_art_url = state.image.clone();
            if !self.art.contains_key(&state.image) {
                images::request(&state.image, 512);
            }
            self.refresh_player_art(cx, &state.image);
        }
        self.now = state;
        self.ui.label(id!(title_label)).set_text(
            cx,
            if self.now.title.is_empty() {
                "Nothing playing"
            } else {
                &self.now.title
            },
        );
        self.ui
            .label(id!(artist_label))
            .set_text(cx, &self.now.artist);
        self.ui
            .label(id!(album_label))
            .set_text(cx, &self.now.album);
        self.ui.view(id!(queue_page)).redraw(cx);
        self.ui
            .view(id!(queue_status))
            .set_visible(cx, self.now.up_next.is_empty());
        self.refresh_mini(cx);
        self.ui.redraw(cx);
        self.refresh_time_labels(cx);
        self.update_toggle_colors(cx);
        self.next_frame = cx.new_next_frame();
    }

    fn refresh_player_art(&self, cx: &mut Cx, url: &str) {
        let art = self.art.get(url);
        for (id, texture) in [
            (id!(cover), art.map(|art| art.cover.clone())),
            (id!(backdrop), art.map(|art| art.backdrop.clone())),
        ] {
            let image = self.ui.image(id);
            let ready = f32::from(texture.is_some());
            image.set_texture(cx, texture);
            image.apply_over(cx, live! {draw_bg: {has_tex: (ready)}});
        }

        let (accent, tint) = if let Some(seed) = art.and_then(|art| art.seed) {
            let roles = colors::roles(&colors::from_seed(seed));
            (argb_to_vec4(roles.primary), argb_to_vec4(roles.background))
        } else {
            (argb_to_vec4(0xffa8c7fa), argb_to_vec4(0xff0c101b))
        };
        self.ui
            .view(id!(cover_ring))
            .apply_over(cx, live! { draw_bg: { accent: (accent) } });
        self.ui
            .view(id!(progress_bar))
            .apply_over(cx, live! { draw_bg: { accent: (accent) } });
        self.ui
            .image(id!(backdrop))
            .apply_over(cx, live! { draw_bg: { tint: (tint) } });
    }

    fn update_toggle_colors(&mut self, cx: &mut Cx) {
        let shuf_col = if self.now.shuffle {
            vec4(0.66, 0.78, 0.98, 1.0)
        } else {
            vec4(1.0, 1.0, 1.0, 0.55)
        };
        let rep_col = if self.now.repeat > 0 {
            vec4(0.66, 0.78, 0.98, 1.0)
        } else {
            vec4(1.0, 1.0, 1.0, 0.55)
        };
        self.ui
            .button(id!(shuffle_btn))
            .apply_over(cx, live! { draw_icon: { color: (shuf_col) } });
        self.ui
            .button(id!(repeat_btn))
            .apply_over(cx, live! { draw_icon: { color: (rep_col) } });
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
        self.ui
            .label(id!(elapsed_label))
            .set_text(cx, &format_ms(pos));
        let remaining = self.now.duration_ms.saturating_sub(pos);
        self.ui
            .label(id!(duration_label))
            .set_text(cx, &format!("−{}", format_ms(remaining)));
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
        std::thread::spawn(|| loop {
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
        });
    }

    #[cfg(not(target_os = "android"))]
    fn load_demo(&mut self, cx: &mut Cx) {
        let demo = player::NowPlaying {
            uri: "spotify:track:demo".into(),
            title: "Midnight Echoes".into(),
            artist: "Glass Harbor".into(),
            album: "Neon Drift".into(),
            image: "demo:0".into(),
            playing: true,
            shuffle: true,
            repeat: 1,
            position_ms: 78_000,
            duration_ms: 222_000,
            timestamp: now_ms(),
            up_next: vec![
                (
                    "Night Swim".into(),
                    "The Violet Hours".into(),
                    "demo:2".into(),
                ),
                ("First Light".into(), "Soft Circuit".into(), "demo:3".into()),
                ("Open Water".into(), "Glass Harbor".into(), "demo:0".into()),
            ],
        };
        let records = [
            (
                library::Kind::Playlist,
                [
                    "Liked Songs",
                    "Late Night Drives",
                    "After Hours",
                    "Slow Mornings",
                    "Weekend Mix",
                ],
            ),
            (
                library::Kind::Album,
                [
                    "Neon Drift",
                    "First Light",
                    "Open Water",
                    "Parallel Lines",
                    "Night Swim",
                ],
            ),
            (
                library::Kind::Artist,
                [
                    "Glass Harbor",
                    "Soft Circuit",
                    "The Violet Hours",
                    "Fading Cities",
                    "Blue Static",
                ],
            ),
            (
                library::Kind::Podcast,
                [
                    "The Listening Room",
                    "Deep Tracks",
                    "In the Studio",
                    "Sound Check",
                    "On Record",
                ],
            ),
        ];
        let mut items = Vec::new();
        for (kind, names) in records {
            for name in names {
                let i = items.len();
                let (w, h, rgba) = demo_artwork(256, i);
                let image = format!("demo:{i}");
                self.art
                    .insert(image.clone(), Artwork::new(cx, &rgba, w, h));
                items.push(library::Item {
                    name: name.into(),
                    uri: if i == 0 {
                        "spotify:collection".into()
                    } else {
                        format!("spotify:demo:{i}")
                    },
                    subtitle: format!("{kind:?}"),
                    image,
                    kind,
                });
            }
        }
        self.receive_library(cx, Ok(items));
        self.apply_now_playing(cx, demo);
    }
}

fn rgba_to_texture(cx: &mut Cx, rgba: &[u8], w: u32, h: u32) -> Texture {
    let data: Vec<u32> = rgba
        .chunks_exact(4)
        .map(|p| {
            ((p[3] as u32) << 24) | ((p[0] as u32) << 16) | ((p[1] as u32) << 8) | (p[2] as u32)
        })
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
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn seek_position(x: f64, width: f64, duration: i64) -> i64 {
    let pad = 8.0;
    let fraction = ((x - pad) / (width - 2.0 * pad).max(1.0)).clamp(0.0, 1.0);
    (fraction * duration as f64).round() as i64
}

#[cfg(test)]
#[test]
fn seek_uses_the_drawn_track_and_clamps_to_its_ends() {
    assert_eq!(seek_position(0.0, 200.0, 90_000), 0);
    assert_eq!(seek_position(100.0, 200.0, 90_000), 45_000);
    assert_eq!(seek_position(200.0, 200.0, 90_000), 90_000);
}

fn format_ms(ms: i64) -> String {
    let s = (ms.max(0) / 1000) as u64;
    format!("{}:{:02}", s / 60, s % 60)
}

#[cfg(not(target_os = "android"))]
fn demo_artwork(size: u32, variant: usize) -> (u32, u32, Vec<u8>) {
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
            let hues = [[r, g, b], [g, b, r], [b, r, g], [b, g, r], [r, b, g]];
            let [r, g, b] = hues[variant % hues.len()];
            rgba.extend_from_slice(&[r, g, b, 255]);
        }
    }
    (size, size, rgba)
}
