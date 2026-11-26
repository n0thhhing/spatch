//! Length-prefixed UTF-8 string packing for JNI and disk cache payloads, mirroring `android/ui/bridge/PackedStrings.java`.

/// Strings as a 4-byte little-endian length and the UTF-8 bytes, one after another.
pub fn encode(fields: &[String]) -> Vec<u8> {
    let total: usize = fields.iter().map(|f| 4 + f.len()).sum();
    let mut out = Vec::with_capacity(total);
    for f in fields {
        out.extend_from_slice(&(f.len() as u32).to_le_bytes());
        out.extend_from_slice(f.as_bytes());
    }
    out
}

/// Decodes one length-prefixed UTF-8 string from the front of `bytes`, returning the string and byte count consumed.
pub fn decode_one(bytes: &[u8]) -> Option<(String, usize)> {
    let len = u32::from_le_bytes(bytes.get(..4)?.try_into().ok()?) as usize;
    Some((String::from_utf8(bytes.get(4..4 + len)?.to_vec()).ok()?, 4 + len))
}

/// Decodes a sequence of length-prefixed UTF-8 strings produced by `encode`.
pub fn decode(mut bytes: &[u8]) -> Option<Vec<String>> {
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let (field, used) = decode_one(bytes)?;
        out.push(field);
        bytes = &bytes[used..];
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrips_strings_and_rejects_truncated() {
        let fields = vec!["hello".to_string(), String::new(), "spatch 🎵".to_string()];
        let bytes = encode(&fields);
        assert_eq!(decode(&bytes), Some(fields));
        assert_eq!(decode(&[1, 0, 0]), None);
    }
}
