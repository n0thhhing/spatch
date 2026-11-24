//! GPU shader sources (AGSL) for spatch.GpuFx. Android compiles AGSL on the phone from source text, so each shader
//! lives as its own .agsl file here and is baked into the library; Java asks for one by name.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

pub fn source(name: &str) -> Option<&'static str> {
    match name {
        "field" => Some(include_str!("shaders/field.agsl")),
        "ring" => Some(include_str!("shaders/ring.agsl")),
        "grain" => Some(include_str!("shaders/grain.agsl")),
        _ => None,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_spatch_GpuFx_nativeShader(mut env: JNIEnv, _: JClass, name: JString) -> jstring {
    let Ok(name) = env.get_string(&name).map(String::from) else { return std::ptr::null_mut() };
    match source(&name).map(|text| env.new_string(text)) {
        Some(Ok(text)) => text.into_raw(),
        _ => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn every_shader_has_an_entry_point() {
        for name in ["field", "ring", "grain"] {
            assert!(super::source(name).unwrap().contains("half4 main(float2 p)"), "{name}");
        }
        assert!(super::source("missing").is_none());
    }
}
