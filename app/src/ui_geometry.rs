//! Normalized paths for the Android Canvas renderer. Shape coordinates are generated once.
use jni::objects::{JClass, JString};
use jni::sys::jfloatArray;
use jni::JNIEnv;
use std::f32::consts::TAU;

fn polar(angle: f32, radius: f32, center: f32) -> [f32; 2] {
    [center + angle.cos() * radius, center + angle.sin() * radius]
}

fn geometry(kind: &str) -> Vec<f32> {
    match kind {
        "cover" | "button" => (0..120)
            .flat_map(|i| {
                let angle = i as f32 / 120.0 * TAU;
                let (radius, center) = if kind == "cover" {
                    (0.397 + 0.013 * (angle * 10.0).cos(), 0.5)
                } else {
                    (0.90 + 0.08 * (angle * 7.0).cos(), 0.0)
                };
                polar(angle, radius, center)
            })
            .collect(),
        "radial" => (0..60)
            .flat_map(|i| {
                let angle = i as f32 / 60.0 * TAU;
                let length = (0.25 + 0.75 * (angle * 3.0).cos().powi(2)) * 0.038;
                polar(angle, 0.439, 0.5)
                    .into_iter()
                    .chain(polar(angle, 0.439 + length, 0.5))
            })
            .collect(),
        "wave" => (0..128).map(|i| (i as f32 / 128.0 * TAU).sin()).collect(),
        _ => glyph(kind).to_vec(),
    }
}

// Each subpath starts with the vertex count; a negative count means a closed fill.
fn glyph(kind: &str) -> &'static [f32] {
    match kind {
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
        "queue" => &[
            2., 4., 6., 20., 6., 2., 4., 11., 20., 11., 2., 4., 16., 14., 16.,
        ],
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
    fn transport_glyphs_are_complete_paths_inside_the_icon_box() {
        for kind in [
            "back",
            "play",
            "pause",
            "previous",
            "next",
            "shuffle",
            "repeat",
            "repeat-one",
            "queue",
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
