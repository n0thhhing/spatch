//! A tiny protobuf reader and writer for the small core messages that aren't worth generating types for
//! (search, shuffle state, downloads, storage stats). Fields are read by number; anything unknown is skipped and
//! bytes that aren't valid end the message quietly instead of failing.

pub fn varint(out: &mut Vec<u8>, mut v: u64) {
    loop {
        let byte = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

/// A length-delimited field: a string or a nested message.
pub fn bytes_field(out: &mut Vec<u8>, field: u32, bytes: &[u8]) {
    varint(out, u64::from(field) << 3 | 2);
    varint(out, bytes.len() as u64);
    out.extend_from_slice(bytes);
}

pub fn string_field(out: &mut Vec<u8>, field: u32, text: &str) {
    bytes_field(out, field, text.as_bytes());
}

pub fn number_field(out: &mut Vec<u8>, field: u32, value: u64) {
    varint(out, u64::from(field) << 3);
    varint(out, value);
}

/// A protobuf field's value: an integer, a 32-bit float's bits, or the bytes of a nested message or string.
#[derive(Clone, Copy, Debug)]
pub enum Value<'a> {
    Number(u64),
    Fixed32(u32),
    Bytes(&'a [u8]),
}

impl<'a> Value<'a> {
    pub fn text(&self) -> String {
        match self {
            Value::Bytes(b) => String::from_utf8_lossy(b).into_owned(),
            _ => String::new(),
        }
    }

    pub fn number(&self) -> u64 {
        match self {
            Value::Number(n) => *n,
            _ => 0,
        }
    }

    /// A `float` field.
    pub fn float(&self) -> f32 {
        match self {
            Value::Fixed32(bits) => f32::from_bits(*bits),
            _ => 0.0,
        }
    }

    pub fn bytes(&self) -> &'a [u8] {
        match self {
            Value::Bytes(b) => b,
            _ => &[],
        }
    }
}

fn read_varint(bytes: &mut &[u8]) -> Option<u64> {
    let (mut v, mut shift) = (0u64, 0);
    loop {
        let (&b, rest) = bytes.split_first()?;
        *bytes = rest;
        v |= u64::from(b & 0x7f) << shift;
        if b < 0x80 {
            return Some(v);
        }
        shift += 7;
        if shift > 63 {
            return None;
        }
    }
}

/// The fields of one message, in order; stops quietly at bytes that aren't valid.
pub fn fields(mut bytes: &[u8]) -> Vec<(u32, Value<'_>)> {
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let Some(tag) = read_varint(&mut bytes) else { break };
        let value = match tag & 7 {
            0 => read_varint(&mut bytes).map(Value::Number),
            2 => read_varint(&mut bytes).and_then(|n| {
                let n = n as usize;
                (n <= bytes.len()).then(|| {
                    let (head, rest) = bytes.split_at(n);
                    bytes = rest;
                    Value::Bytes(head)
                })
            }),
            5 if bytes.len() >= 4 => {
                let bits = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
                bytes = &bytes[4..];
                Some(Value::Fixed32(bits))
            }
            1 if bytes.len() >= 8 => {
                bytes = &bytes[8..];
                Some(Value::Number(0))
            }
            _ => None,
        };
        let Some(value) = value else { break };
        out.push(((tag >> 3) as u32, value));
    }
    out
}

/// The first value of a field, if present.
pub fn first<'a>(fields: &[(u32, Value<'a>)], number: u32) -> Option<Value<'a>> {
    fields.iter().find(|(n, _)| *n == number).map(|(_, v)| *v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_and_reads_every_kind_of_field() {
        let mut out = Vec::new();
        string_field(&mut out, 1, "hi");
        number_field(&mut out, 2, 300);
        out.extend_from_slice(&[0x1d]); // field 3, fixed32
        out.extend_from_slice(&0.5f32.to_le_bytes());
        let parsed = fields(&out);
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0].1.text(), "hi");
        assert_eq!(parsed[1].1.number(), 300);
        assert_eq!(parsed[2].1.float(), 0.5);
        assert_eq!(first(&parsed, 2).map(|v| v.number()), Some(300));
        assert!(first(&parsed, 9).is_none());
    }

    #[test]
    fn cut_off_bytes_stop_the_message_without_failing() {
        assert!(fields(&[0x0a, 5, b'a']).is_empty());
        assert!(fields(&[0x80]).is_empty());
    }
}
