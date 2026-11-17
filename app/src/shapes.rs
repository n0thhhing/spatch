//! Icon and button geometry for the Android Canvas renderer.
//!
//! An icon is a list of subpaths on a 24-unit grid: a vertex count (negative for a closed fill),
//! then that many x, y pairs. Round shapes are unit-sized polygons centred on the origin.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jfloatArray;
use std::f32::consts::TAU;

/// An open stroke through the points.
fn stroke(points: &[(f32, f32)]) -> Vec<f32> {
    std::iter::once(points.len() as f32).chain(points.iter().flat_map(|&(x, y)| [x, y])).collect()
}

/// A closed, filled polygon.
fn fill(points: &[(f32, f32)]) -> Vec<f32> {
    std::iter::once(-(points.len() as f32)).chain(points.iter().flat_map(|&(x, y)| [x, y])).collect()
}

/// A circle of `radius` around (cx, cy) as a closed stroke.
fn ring(cx: f32, cy: f32, radius: f32, steps: usize) -> Vec<f32> {
    let points: Vec<_> = (0..=steps)
        .map(|i| {
            let a = i as f32 / steps as f32 * TAU;
            (cx + a.cos() * radius, cy + a.sin() * radius)
        })
        .collect();
    stroke(&points)
}

fn polygon(count: usize, radius: impl Fn(f32) -> f32) -> Vec<f32> {
    (0..count)
        .flat_map(|i| {
            let a = i as f32 / count as f32 * TAU;
            [a.cos() * radius(a), a.sin() * radius(a)]
        })
        .collect()
}

fn heart(closed_fill: bool) -> Vec<f32> {
    let points: Vec<_> = (0..48)
        .map(|i| {
            let t = i as f32 / 48.0 * TAU;
            let x = 16.0 * t.sin().powi(3);
            let y = 13.0 * t.cos() - 5.0 * (2.0 * t).cos() - 2.0 * (3.0 * t).cos() - (4.0 * t).cos();
            (12.0 + x * 0.56, 11.2 - y * 0.56)
        })
        .collect();
    if closed_fill {
        fill(&points)
    } else {
        stroke(&[points.as_slice(), &points[..1]].concat())
    }
}

fn gear() -> Vec<f32> {
    let teeth: Vec<_> = (0..=64)
        .map(|i| {
            let a = i as f32 / 64.0 * TAU;
            let r = if (a / TAU * 8.0 + 0.25).fract() < 0.5 { 9.2 } else { 7.0 };
            (12.0 + a.cos() * r, 12.0 + a.sin() * r)
        })
        .collect();
    [stroke(&teeth), ring(12.0, 12.0, 3.0, 24)].concat()
}

/// The geometry for `kind`, or an empty list for an unknown name.
pub fn geometry(kind: &str) -> Vec<f32> {
    match kind {
        // Scallops for the play button: five lobes at rest, nine while playing.
        "button5" => polygon(120, |a| 0.91 + 0.055 * (a * 5.0).cos()),
        "button9" => polygon(120, |a| 0.91 + 0.055 * (a * 9.0).cos()),
        "play" => fill(&[(7., 4.), (20., 12.), (7., 20.)]),
        "pause" => [fill(&[(6., 5.), (10., 5.), (10., 19.), (6., 19.)]), fill(&[(14., 5.), (18., 5.), (18., 19.), (14., 19.)])].concat(),
        "previous" => [fill(&[(19., 4.), (7., 12.), (19., 20.)]), stroke(&[(4., 5.), (4., 19.)])].concat(),
        "next" => [fill(&[(5., 4.), (17., 12.), (5., 20.)]), stroke(&[(20., 5.), (20., 19.)])].concat(),
        "back" => [stroke(&[(13., 5.), (6., 12.), (13., 19.)]), stroke(&[(6., 12.), (20., 12.)])].concat(),
        "down" => stroke(&[(5., 9.), (12., 16.), (19., 9.)]),
        "chevron" => stroke(&[(9., 5.), (16., 12.), (9., 19.)]),
        "check" => stroke(&[(5., 12.5), (10., 17.5), (19., 7.)]),
        "close" => [stroke(&[(6., 6.), (18., 18.)]), stroke(&[(18., 6.), (6., 18.)])].concat(),
        // Two arrows crossing: one rises left to right, the other falls, with a gap where they pass.
        "shuffle" => [
            stroke(&[(3., 17.), (8., 17.), (16., 7.), (21., 7.)]),
            stroke(&[(18., 4.), (21., 7.), (18., 10.)]),
            stroke(&[(3., 7.), (8., 7.), (10.5, 10.5)]),
            stroke(&[(13.5, 13.5), (16., 17.), (21., 17.)]),
            stroke(&[(18., 14.), (21., 17.), (18., 20.)]),
        ]
        .concat(),
        "repeat" | "repeat-one" => {
            let arrows = [
                stroke(&[(4., 11.), (4., 7.), (7., 7.), (19., 7.)]),
                stroke(&[(16., 4.), (19., 7.), (16., 10.)]),
                stroke(&[(20., 13.), (20., 17.), (17., 17.), (5., 17.)]),
                stroke(&[(8., 14.), (5., 17.), (8., 20.)]),
            ]
            .concat();
            if kind == "repeat" { arrows } else { [arrows, stroke(&[(11., 11.), (12.5, 10.), (12.5, 14.)])].concat() }
        }
        "volume" => [fill(&[(4., 9.), (8., 9.), (13., 5.), (13., 19.), (8., 15.), (4., 15.)]), stroke(&[(16., 8.), (18., 11.), (18., 13.), (16., 16.)])].concat(),
        "queue" => [stroke(&[(4., 6.), (20., 6.)]), stroke(&[(4., 11.), (20., 11.)]), stroke(&[(4., 16.), (14., 16.)])].concat(),
        "home" => stroke(&[(3., 12.), (12., 3.), (21., 12.), (19., 12.), (19., 20.), (14., 20.), (14., 14.), (10., 14.), (10., 20.), (5., 20.), (5., 12.), (3., 12.)]),
        "library" => [stroke(&[(5., 4.), (5., 20.)]), stroke(&[(9., 4.), (9., 20.)]), stroke(&[(14., 5.), (19., 19.)])].concat(),
        "settings" => gear(),
        // A speaker box with a small phone beside it: "play on another device".
        "devices" => [
            stroke(&[(3., 5.), (16., 5.), (16., 15.), (3., 15.), (3., 5.)]),
            stroke(&[(7., 19.), (12., 19.)]),
            stroke(&[(9.5, 15.), (9.5, 19.)]),
            stroke(&[(19., 9.), (22., 9.), (22., 19.), (19., 19.), (19., 9.)]),
        ]
        .concat(),
        "heart" => heart(true),
        "heart-outline" => heart(false),
        "search" => [ring(10.5, 10.5, 6.5, 24), stroke(&[(15.5, 15.5), (20.5, 20.5)])].concat(),
        "timer" => [ring(12.0, 13.5, 8.0, 32), stroke(&[(12., 9.), (12., 13.5), (15., 15.5)]), stroke(&[(10., 2.5), (14., 2.5)])].concat(),
        // A record: the disc and its centre.
        "album" => [ring(12.0, 12.0, 9.0, 36), ring(12.0, 12.0, 2.5, 16)].concat(),
        // A head and shoulders.
        "artist" => [ring(12.0, 8.0, 4.0, 24), stroke(&[(4., 21.), (5., 17.5), (8., 15.), (12., 14.2), (16., 15.), (19., 17.5), (20., 21.)])].concat(),
        _ => Vec::new(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_SpatchUi_nativeGeometry(mut env: JNIEnv, _: JClass, kind: JString) -> jfloatArray {
    let Ok(kind) = env.get_string(&kind).map(String::from) else { return std::ptr::null_mut() };
    let data = geometry(&kind);
    let Ok(array) = env.new_float_array(data.len() as i32) else { return std::ptr::null_mut() };
    if env.set_float_array_region(&array, 0, &data).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    const ICONS: [&str; 24] = [
        "play", "pause", "previous", "next", "back", "down", "chevron", "check", "close", "shuffle", "repeat", "repeat-one", "volume", "queue",
        "home", "library", "settings", "heart", "heart-outline", "search", "timer", "devices", "album", "artist",
    ];

    #[test]
    fn every_icon_is_well_formed_and_inside_its_box() {
        for kind in ICONS {
            let data = geometry(kind);
            let mut rest = data.as_slice();
            assert!(!rest.is_empty(), "{kind} is empty");
            while let [count, tail @ ..] = rest {
                let length = count.abs() as usize * 2;
                assert!(length >= 4 && tail.len() >= length, "{kind}: bad subpath");
                assert!(tail[..length].iter().all(|v| (0.0..=24.0).contains(v)), "{kind}: outside the grid");
                rest = &tail[length..];
            }
        }
    }

    #[test]
    fn the_play_button_has_five_and_nine_lobes() {
        for (name, lobes) in [("button5", 5), ("button9", 9)] {
            let radii: Vec<f32> = geometry(name).chunks_exact(2).map(|p| p[0].hypot(p[1])).collect();
            let n = radii.len();
            let peaks = (0..n).filter(|&i| radii[i] > radii[(i + n - 1) % n] && radii[i] >= radii[(i + 1) % n]).count();
            assert_eq!(peaks, lobes);
        }
    }
}
