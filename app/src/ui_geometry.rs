//! Normalized paths for the Android Canvas renderer. Shape coordinates are generated once.
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jfloatArray;
use std::f32::consts::TAU;

fn polar(angle: f32, radius: f32, center: f32) -> [f32; 2] {
    [center + angle.cos() * radius, center + angle.sin() * radius]
}

fn geometry(kind: &str) -> Vec<f32> {
    match kind {
        "cover" | "circle-cover" | "player-cover" | "button5" | "button9" | "wordmark" => (0..120)
            .flat_map(|i| {
                let angle = i as f32 / 120.0 * TAU;
                let (radius, center) = if kind == "cover" {
                    (0.461 + 0.021 * (angle * 8.0).cos(), 0.5)
                } else if kind == "circle-cover" {
                    (0.5, 0.5)
                } else if kind == "player-cover" {
                    let (x, y) = (angle.cos().abs(), angle.sin().abs());
                    let (mut low, mut high) = (0.0, 0.71);
                    for _ in 0..14 {
                        let r = (low + high) * 0.5;
                        if (x * r - 0.42).max(0.0).hypot((y * r - 0.42).max(0.0)) <= 0.08 {
                            low = r;
                        } else {
                            high = r;
                        }
                    }
                    (low, 0.5)
                } else if kind == "wordmark" {
                    (0.80 + 0.19 * (angle * 5.0).cos(), 0.0)
                } else {
                    let points = if kind == "button5" { 5.0 } else { 9.0 };
                    (0.91 + 0.055 * (angle * points).cos(), 0.0)
                };
                polar(angle, radius, center)
            })
            .collect(),
        "wave" => (0..128).map(|i| (i as f32 / 128.0 * TAU).sin()).collect(),
        // Eight-tooth gear outline plus its hub, stroked.
        "settings" => {
            let teeth: Vec<f32> = (0..=64)
                .flat_map(|i| {
                    let angle = i as f32 / 64.0 * TAU;
                    let tooth = ((angle / TAU * 8.0 + 0.25).fract() < 0.5) as u8 as f32;
                    polar(angle, 7.0 + 2.2 * tooth, 12.0)
                })
                .collect();
            let hub: Vec<f32> = (0..=24).flat_map(|i| polar(i as f32 / 24.0 * TAU, 3.0, 12.0)).collect();
            [vec![65.0], teeth, vec![25.0], hub].concat()
        }
        // Stopwatch: dial, hands and the crown on top.
        "timer" => [
            vec![33.0],
            (0..=32).flat_map(|i| polar(i as f32 / 32.0 * TAU, 8.0, 0.0)).collect::<Vec<_>>()
                .chunks(2).flat_map(|p| [p[0] + 12.0, p[1] + 13.5]).collect(),
            vec![3.0, 12.0, 9.0, 12.0, 13.5, 15.0, 15.5],
            vec![2.0, 10.0, 2.5, 14.0, 2.5],
        ]
        .concat(),
        "search" => [
            vec![25.0],
            (0..=24).flat_map(|i| polar(i as f32 / 24.0 * TAU, 6.5, 0.0)).collect::<Vec<_>>()
                .chunks(2).flat_map(|p| [p[0] + 10.5, p[1] + 10.5]).collect(),
            vec![2.0, 15.5, 15.5, 20.5, 20.5],
        ]
        .concat(),
        // Filled heart from the classic parametric curve, fitted to the 24 box; the outline repeats its start.
        "heart" | "heart-outline" => [vec![if kind == "heart" { -48.0 } else { 49.0 }], (0..if kind == "heart" { 48 } else { 49 })
            .flat_map(|i| {
                let t = (i % 48) as f32 / 48.0 * TAU;
                let x = 16.0 * t.sin().powi(3);
                let y = 13.0 * t.cos() - 5.0 * (2.0 * t).cos() - 2.0 * (3.0 * t).cos() - (4.0 * t).cos();
                [12.0 + x * 0.56, 11.2 - y * 0.56]
            })
            .collect()]
        .concat(),
        _ => glyph(kind).to_vec(),
    }
}

// Each subpath starts with the vertex count; a negative count means a closed fill.
fn glyph(kind: &str) -> &'static [f32] {
    match kind {
        "down" => &[3., 5., 9., 12., 16., 19., 9.],
        "back" => &[3., 13., 5., 6., 12., 13., 19., 2., 6., 12., 20., 12.],
        "play" => &[-3., 7., 4., 20., 12., 7., 20.],
        "pause" => &[
            -4., 6., 5., 10., 5., 10., 19., 6., 19., -4., 14., 5., 18., 5., 18., 19., 14., 19.,
        ],
        "previous" => &[-3., 19., 4., 7., 12., 19., 20., 2., 4., 5., 4., 19.],
        "next" => &[-3., 5., 4., 17., 12., 5., 20., 2., 20., 5., 20., 19.],
        "shuffle" => &[
            2., 4., 6., 8., 9., 2., 15., 15., 20., 19., 3., 15., 19., 20., 19., 20., 14., 2., 4.,
            18., 20., 5., 3., 15., 5., 20., 5., 20., 10.,
        ],
        "repeat" | "repeat-one" => {
            const REPEAT: &[f32] = &[
                4., 4., 11., 4., 7., 7., 7., 19., 7., 3., 16., 4., 19., 7., 16., 10., 4., 20., 13.,
                20., 17., 17., 17., 5., 17., 3., 8., 14., 5., 17., 8., 20.,
            ];
            const ONE: &[f32] = &[3., 11., 11., 12.5, 10., 12.5, 14.];
            if kind == "repeat" {
                REPEAT
            } else {
                // Same arrows plus the centered one. Kept static so the renderer can cache the path.
                const BOTH: &[f32] = &{
                    let mut output = [0.; 39];
                    let mut i = 0;
                    while i < REPEAT.len() {
                        output[i] = REPEAT[i];
                        i += 1;
                    }
                    let mut j = 0;
                    while j < ONE.len() {
                        output[i + j] = ONE[j];
                        j += 1;
                    }
                    output
                };
                BOTH
            }
        }
        "immersive" => &[
            3., 4., 10., 4., 4., 10., 4., 3., 14., 4., 20., 4., 20., 10., 3., 20., 14., 20., 20.,
            14., 20., 3., 10., 20., 4., 20., 4., 14.,
        ],
        "home" => &[
            3., 3., 11., 12., 4., 21., 11., 8., 5., 10., 5., 20., 10., 20., 10., 14., 14., 14.,
            14., 20., 19., 20., 19., 10.,
        ],
        "library" => &[
            2., 4., 5., 4., 19., 2., 9., 5., 9., 19., 2., 14., 5., 19., 19.,
        ],
        "volume" => &[
            -6., 4., 9., 8., 9., 13., 5., 13., 19., 8., 15., 4., 15., 4., 16., 8., 18., 11., 18.,
            13., 16., 16.,
        ],
        "queue" => &[
            2., 4., 6., 20., 6., 2., 4., 11., 20., 11., 2., 4., 16., 14., 16.,
        ],
        "grid" => &[
            5., 4., 4., 10., 4., 10., 10., 4., 10., 4., 4., 5., 14., 4., 20., 4., 20., 10., 14., 10., 14., 4.,
            5., 4., 14., 10., 14., 10., 20., 4., 20., 4., 14., 5., 14., 14., 20., 14., 20., 20., 14., 20., 14., 14.,
        ],
        "check" => &[3., 5., 12.5, 10., 17.5, 19., 7.],
        "chevron" => &[3., 9., 5., 16., 12., 9., 19.],
        "close" => &[2., 6., 6., 18., 18., 2., 18., 6., 6., 18.],
        "sparkle" => &[-8., 12., 3., 14., 10., 21., 12., 14., 14., 12., 21., 10., 14., 3., 12., 10., 10.],
        _ => &[],
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeGeometry(
    mut env: JNIEnv,
    _: JClass,
    kind: JString,
) -> jfloatArray {
    let Ok(kind) = env.get_string(&kind) else {
        return std::ptr::null_mut();
    };
    let data = geometry(&String::from(kind));
    let Ok(array) = env.new_float_array(data.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_float_array_region(&array, 0, &data).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playback_shapes_have_five_and_nine_peaks() {
        for (name, expected) in [("button5", 5), ("button9", 9)] {
            let radii: Vec<_> = geometry(name)
                .chunks_exact(2)
                .map(|point| point[0].hypot(point[1]))
                .collect();
            let count = (0..radii.len())
                .filter(|&i| {
                    radii[i] > radii[(i + radii.len() - 1) % radii.len()]
                        && radii[i] >= radii[(i + 1) % radii.len()]
                })
                .count();
            assert_eq!(count, expected);
        }
    }

    #[test]
    fn computed_glyphs_stay_in_the_icon_box() {
        for kind in ["settings", "heart", "heart-outline", "timer", "search"] {
            let data = geometry(kind);
            let mut points = data.as_slice();
            while let [count, rest @ ..] = points {
                let length = count.abs() as usize * 2;
                assert!(rest[..length].iter().all(|v| (0.0..=24.0).contains(v)), "{kind}");
                points = &rest[length..];
            }
            assert!(points.is_empty());
        }
    }

    #[test]
    fn transport_glyphs_are_complete_paths_inside_the_icon_box() {
        for kind in [
            "back",
            "down",
            "play",
            "pause",
            "previous",
            "next",
            "shuffle",
            "repeat",
            "repeat-one",
            "queue",
            "immersive",
            "home",
            "library",
            "close",
            "sparkle",
            "chevron",
            "check",
            "grid",
        ] {
            let mut points = glyph(kind);
            assert!(!points.is_empty());
            while let [count, rest @ ..] = points {
                let length = count.abs() as usize * 2;
                assert!(length >= 4, "{kind}: {count}");
                assert!(rest[..length].iter().all(|v| (0.0..=24.0).contains(v)));
                points = &rest[length..];
            }
        }
    }
}
