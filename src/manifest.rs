//! Adds Spatch's home-screen widget to Spotify's compiled (binary XML) manifest, beside Spotify's own widget:
//!
//! ```xml
//! <receiver android:name="spatch.SpatchWidget" android:label="Spatch" android:exported="true">
//!   <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter>
//!   <meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_info"/>
//! </receiver>
//! ```
//!
//! It reuses Spotify's own provider description and layout (found by name), so no resource changes are needed.
//! The string pool is rebuilt with the new strings appended; every existing string keeps its index.

use crate::Result;

const XML: u16 = 0x0003;
const POOL: u16 = 0x0001;
const START: u16 = 0x0102;
const END: u16 = 0x0103;
const UTF8: u32 = 0x100;
const REFERENCE: u8 = 0x01;
const STRING: u8 = 0x03;
const BOOLEAN: u8 = 0x12;
const NONE: u32 = u32::MAX;

fn u16_at(b: &[u8], at: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(b.get(at..at + 2).ok_or("manifest is cut off")?.try_into()?))
}

fn u32_at(b: &[u8], at: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(b.get(at..at + 4).ok_or("manifest is cut off")?.try_into()?))
}

struct Pool {
    flags: u32,
    /// Each string as stored (length prefix, content, terminator).
    strings: Vec<Vec<u8>>,
}

impl Pool {
    fn parse(b: &[u8], at: usize) -> Result<(Pool, usize)> {
        if u16_at(b, at)? != POOL {
            return Err("the manifest does not start with a string pool".into());
        }
        let size = u32_at(b, at + 4)? as usize;
        let (count, styles, flags, start) = (u32_at(b, at + 8)? as usize, u32_at(b, at + 12)?, u32_at(b, at + 16)?, u32_at(b, at + 20)? as usize);
        if styles != 0 {
            return Err("the manifest string pool has styles".into());
        }
        let header = u16_at(b, at + 2)? as usize;
        let offsets: Vec<usize> = (0..count).map(|i| u32_at(b, at + header + i * 4).map(|o| o as usize)).collect::<Result<_>>()?;
        let data = at + start;
        let end = at + size;
        let mut strings = Vec::new();
        for (i, &o) in offsets.iter().enumerate() {
            let from = data + o;
            let to = offsets.get(i + 1).map_or(end, |n| data + n);
            strings.push(b.get(from..to).ok_or("string pool entry is cut off")?.to_vec());
        }
        // The last string runs to the chunk's end, which may include padding; trim it to its own length.
        if let Some(last) = strings.last_mut() {
            let n = Self::stored_len(last, flags & UTF8 != 0)?;
            last.truncate(n);
        }
        Ok((Pool { flags, strings }, end))
    }

    fn stored_len(s: &[u8], utf8: bool) -> Result<usize> {
        if utf8 {
            let skip = if s.first().ok_or("empty string")? & 0x80 != 0 { 2 } else { 1 };
            let long = s.get(skip).ok_or("string is cut off")? & 0x80 != 0;
            let bytes = if long { (usize::from(s[skip] & 0x7f) << 8) | usize::from(*s.get(skip + 1).ok_or("string is cut off")?) } else { usize::from(s[skip]) };
            Ok(skip + if long { 2 } else { 1 } + bytes + 1)
        } else {
            let first = u16_at(s, 0)?;
            let (head, units) = if first & 0x8000 != 0 { (4, ((usize::from(first & 0x7fff)) << 16) | usize::from(u16_at(s, 2)?)) } else { (2, usize::from(first)) };
            Ok(head + units * 2 + 2)
        }
    }

    fn text(&self, i: usize) -> String {
        let s = &self.strings[i];
        if self.flags & UTF8 != 0 {
            let skip = if s[0] & 0x80 != 0 { 2 } else { 1 };
            let long = s[skip] & 0x80 != 0;
            let (head, n) = if long { (skip + 2, (usize::from(s[skip] & 0x7f) << 8) | usize::from(s[skip + 1])) } else { (skip + 1, usize::from(s[skip])) };
            String::from_utf8_lossy(&s[head..head + n]).into_owned()
        } else {
            let units: Vec<u16> = (0..(s.len() - 4) / 2).map(|k| u16::from_le_bytes([s[2 + k * 2], s[3 + k * 2]])).collect();
            String::from_utf16_lossy(&units)
        }
    }

    /// The index of `text`, optionally only among the first `below` strings (the ones with resource ids).
    fn find(&self, text: &str, below: usize) -> Option<usize> {
        (0..self.strings.len().min(below)).find(|&i| self.text(i) == text)
    }

    fn add(&mut self, text: &str) -> usize {
        if let Some(i) = self.find(text, usize::MAX) {
            return i;
        }
        let mut s = Vec::new();
        if self.flags & UTF8 != 0 {
            assert!(text.len() < 0x80, "short strings only");
            s.extend([text.chars().count() as u8, text.len() as u8]);
            s.extend(text.as_bytes());
            s.push(0);
        } else {
            let units: Vec<u16> = text.encode_utf16().collect();
            assert!(units.len() < 0x8000, "short strings only");
            s.extend((units.len() as u16).to_le_bytes());
            for u in units {
                s.extend(u.to_le_bytes());
            }
            s.extend([0, 0]);
        }
        self.strings.push(s);
        self.strings.len() - 1
    }

    fn write(&self, out: &mut Vec<u8>) {
        let count = self.strings.len();
        let start = 28 + count * 4;
        let mut data: Vec<u8> = self.strings.concat();
        while !data.len().is_multiple_of(4) {
            data.push(0);
        }
        out.extend(POOL.to_le_bytes());
        out.extend(28u16.to_le_bytes());
        out.extend(((start + data.len()) as u32).to_le_bytes());
        out.extend((count as u32).to_le_bytes());
        out.extend(0u32.to_le_bytes());
        out.extend(self.flags.to_le_bytes());
        out.extend((start as u32).to_le_bytes());
        out.extend(0u32.to_le_bytes());
        let mut at = 0u32;
        for s in &self.strings {
            out.extend(at.to_le_bytes());
            at += s.len() as u32;
        }
        out.extend(data);
    }
}

/// One attribute (a node's attributes must be sorted by resource id, as the platform looks them up that way): (namespace string or NONE, name string, raw string or NONE, type, data).
type Attribute = (u32, u32, u32, u8, u32);

fn element(out: &mut Vec<u8>, kind: u16, name: usize, attributes: &[Attribute]) {
    let size = if kind == START { 36 + attributes.len() * 20 } else { 24 };
    out.extend(kind.to_le_bytes());
    out.extend(16u16.to_le_bytes());
    out.extend((size as u32).to_le_bytes());
    out.extend(0u32.to_le_bytes()); // line number
    out.extend(NONE.to_le_bytes()); // comment
    out.extend(NONE.to_le_bytes()); // namespace
    out.extend((name as u32).to_le_bytes());
    if kind == START {
        out.extend(0x14u16.to_le_bytes());
        out.extend(0x14u16.to_le_bytes());
        out.extend((attributes.len() as u16).to_le_bytes());
        out.extend([0u8; 6]); // id, class and style indexes: none
        for &(ns, name, raw, kind, data) in attributes {
            out.extend(ns.to_le_bytes());
            out.extend(name.to_le_bytes());
            out.extend(raw.to_le_bytes());
            out.extend(8u16.to_le_bytes());
            out.extend([0, kind]);
            out.extend(data.to_le_bytes());
        }
    }
}

/// `manifest` with Spatch's widget receiver added to `<application>`. `provider` is the resource id of Spotify's
/// own widget description, `label` what the widget picker shows.
pub fn add_widget(manifest: &[u8], class: &str, label: &str, provider: u32) -> Result<Vec<u8>> {
    if u16_at(manifest, 0)? != XML {
        return Err("not a compiled manifest".into());
    }
    let (mut pool, after_pool) = Pool::parse(manifest, u16_at(manifest, 2)? as usize)?;
    if pool.find(class, usize::MAX).is_some() {
        return Err("the manifest already has the Spatch widget".into());
    }
    // Resource map: one id per leading string; the attribute names live there.
    if u16_at(manifest, after_pool)? != 0x0180 {
        return Err("the manifest has no resource map".into());
    }
    let mapped = (u32_at(manifest, after_pool + 4)? as usize - 8) / 4;
    let attr = |name: &str| pool.find(name, mapped).ok_or_else(|| format!("the manifest has no attribute {name}"));
    let (name, exported, label_attr, resource) = (attr("name")? as u32, attr("exported")? as u32, attr("label")? as u32, attr("resource")? as u32);
    let ns = pool.find("http://schemas.android.com/apk/res/android", usize::MAX).ok_or("the manifest has no android namespace")? as u32;
    let find = |pool: &Pool, text: &str| pool.find(text, usize::MAX).ok_or_else(|| format!("the manifest has no {text}"));
    let (receiver, filter, action, meta) = (find(&pool, "receiver")?, find(&pool, "intent-filter")?, find(&pool, "action")?, find(&pool, "meta-data")?);
    let update = pool.add("android.appwidget.action.APPWIDGET_UPDATE") as u32;
    let provider_name = pool.add("android.appwidget.provider") as u32;
    let (class, label) = (pool.add(class) as u32, pool.add(label) as u32);

    // The end of <application> is where the receiver goes.
    let application = pool.find("application", usize::MAX).ok_or("the manifest has no application")?;
    let mut at = after_pool;
    at += u32_at(manifest, at + 4)? as usize;
    let mut insert = None;
    while at < manifest.len() {
        let size = u32_at(manifest, at + 4)? as usize;
        if u16_at(manifest, at)? == END && u32_at(manifest, at + 20)? as usize == application {
            insert = Some(at);
        }
        at += size.max(8);
    }
    let insert = insert.ok_or("the manifest has no application element")?;

    let mut nodes = Vec::new();
    element(&mut nodes, START, receiver, &[(ns, label_attr, label, STRING, label), (ns, name, class, STRING, class), (ns, exported, NONE, BOOLEAN, 0xffff_ffff)]);
    element(&mut nodes, START, filter, &[]);
    element(&mut nodes, START, action, &[(ns, name, update, STRING, update)]);
    element(&mut nodes, END, action, &[]);
    element(&mut nodes, END, filter, &[]);
    element(&mut nodes, START, meta, &[(ns, name, provider_name, STRING, provider_name), (ns, resource, NONE, REFERENCE, provider)]);
    element(&mut nodes, END, meta, &[]);
    element(&mut nodes, END, receiver, &[]);

    let mut out = Vec::with_capacity(manifest.len() + nodes.len() + 256);
    out.extend(XML.to_le_bytes());
    out.extend(8u16.to_le_bytes());
    out.extend(0u32.to_le_bytes());
    pool.write(&mut out);
    out.extend(&manifest[after_pool..insert]);
    out.extend(nodes);
    out.extend(&manifest[insert..]);
    let total = out.len() as u32;
    out[4..8].copy_from_slice(&total.to_le_bytes());
    Ok(out)
}

/// Notification access is user-granted; the service reads media sessions, not notification contents.
pub fn add_official_playback(manifest: &[u8]) -> Result<Vec<u8>> {
    if u16_at(manifest, 0)? != XML {
        return Err("not a compiled manifest".into());
    }
    let (mut pool, after_pool) = Pool::parse(manifest, u16_at(manifest, 2)? as usize)?;
    let class = "spatch.OfficialPlayback";
    if pool.find(class, usize::MAX).is_some() {
        return Err("the manifest already has official playback".into());
    }
    if u16_at(manifest, after_pool)? != 0x0180 {
        return Err("the manifest has no resource map".into());
    }
    let mapped = (u32_at(manifest, after_pool + 4)? as usize - 8) / 4;
    let attr = |name: &str| {
        pool.find(name, mapped)
            .map(|i| i as u32)
            .ok_or_else(|| format!("the manifest has no attribute {name}"))
    };
    let (name, exported, permission) = (attr("name")?, attr("exported")?, attr("permission")?);
    let ns = pool
        .find("http://schemas.android.com/apk/res/android", usize::MAX)
        .ok_or("the manifest has no android namespace")? as u32;
    let mut application_end = None;
    let mut manifest_end = None;
    let mut at = after_pool + u32_at(manifest, after_pool + 4)? as usize;
    while at < manifest.len() {
        let size = u32_at(manifest, at + 4)? as usize;
        if size < 8 || at + size > manifest.len() {
            return Err("invalid manifest chunk".into());
        }
        if u16_at(manifest, at)? == END {
            match pool.text(u32_at(manifest, at + 20)? as usize).as_str() {
                "application" => application_end = Some(at),
                "manifest" => manifest_end = Some(at),
                _ => {}
            }
        }
        at += size;
    }
    let (app_end, root_end) = (
        application_end.ok_or("the manifest has no application")?,
        manifest_end.ok_or("the manifest has no root")?,
    );
    let service = pool.add("service");
    let filter = pool.add("intent-filter");
    let action = pool.add("action");
    let class = pool.add(class) as u32;
    let label_attr = pool
        .find("label", mapped)
        .ok_or("the manifest has no label attribute")? as u32;
    let label = pool.add("Spotify playback fallback") as u32;
    let bind = pool.add("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE") as u32;
    let listener = pool.add("android.service.notification.NotificationListenerService") as u32;
    let mut nodes = Vec::new();
    // Android attribute order: label, name, permission, exported.
    element(
        &mut nodes,
        START,
        service,
        &[
            (ns, label_attr, label, STRING, label),
            (ns, name, class, STRING, class),
            (ns, permission, bind, STRING, bind),
            (ns, exported, NONE, BOOLEAN, 0xffff_ffff),
        ],
    );
    element(&mut nodes, START, filter, &[]);
    element(
        &mut nodes,
        START,
        action,
        &[(ns, name, listener, STRING, listener)],
    );
    element(&mut nodes, END, action, &[]);
    element(&mut nodes, END, filter, &[]);
    element(&mut nodes, END, service, &[]);
    let queries = pool.add("queries");
    let package = pool.add("package");
    let spotify = pool.add("com.spotify.music") as u32;
    let mut query_nodes = Vec::new();
    element(&mut query_nodes, START, queries, &[]);
    element(
        &mut query_nodes,
        START,
        package,
        &[(ns, name, spotify, STRING, spotify)],
    );
    element(&mut query_nodes, END, package, &[]);
    element(&mut query_nodes, END, queries, &[]);
    let mut out = vec![3, 0, 8, 0, 0, 0, 0, 0];
    pool.write(&mut out);
    out.extend(&manifest[after_pool..app_end]);
    out.extend(nodes);
    out.extend(&manifest[app_end..root_end]);
    out.extend(query_nodes);
    out.extend(&manifest[root_end..]);
    let total = out.len() as u32;
    out[4..8].copy_from_slice(&total.to_le_bytes());
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A tiny manifest: `<manifest><application></application></manifest>` with the strings the editor needs.
    fn sample() -> Vec<u8> {
        let mut pool = Pool { flags: UTF8, strings: Vec::new() };
        for s in ["name", "label", "exported", "resource", "permission", "http://schemas.android.com/apk/res/android", "manifest", "application", "receiver", "intent-filter", "action", "meta-data"] {
            pool.add(s);
        }
        let mut out = vec![3, 0, 8, 0, 0, 0, 0, 0];
        pool.write(&mut out);
        out.extend([0x80, 1, 8, 0]);
        out.extend(28u32.to_le_bytes());
        for id in [0x0101_0003u32, 0x0101_0001, 0x0101_0010, 0x0101_0025, 0x0101_0006] {
            out.extend(id.to_le_bytes());
        }
        element(&mut out, START, 6, &[]);
        element(&mut out, START, 7, &[]);
        element(&mut out, END, 7, &[]);
        element(&mut out, END, 6, &[]);
        let total = out.len() as u32;
        out[4..8].copy_from_slice(&total.to_le_bytes());
        out
    }

    #[test]
    fn official_playback_is_permission_protected_and_queries_the_original_package() {
        let original = sample();
        let widget = add_widget(&original, "spatch.SpatchWidget", "Spatch", 0x7f16_0021).unwrap();
        let out = add_official_playback(&widget).unwrap();
        let (pool, mut at) = Pool::parse(&out, 8).unwrap();
        assert_eq!(u32_at(&out, 4).unwrap() as usize, out.len());
        at += u32_at(&out, at + 4).unwrap() as usize;
        let mut stack = Vec::new();
        let mut service = false;
        let mut query = false;
        while at < out.len() {
            let kind = u16_at(&out, at).unwrap();
            let tag = pool.text(u32_at(&out, at + 20).unwrap() as usize);
            if kind == START {
                if tag == "service" {
                    assert_eq!(stack.last().unwrap(), "application");
                    let count = u16_at(&out, at + 28).unwrap() as usize;
                    let attrs: Vec<_> = (0..count)
                        .map(|i| {
                            let a = at + 36 + i * 20;
                            (
                                pool.text(u32_at(&out, a + 4).unwrap() as usize),
                                u32_at(&out, a + 16).unwrap(),
                            )
                        })
                        .collect();
                    assert!(
                        attrs.iter().any(|(n, v)| n == "name"
                            && pool.text(*v as usize) == "spatch.OfficialPlayback")
                    );
                    assert!(attrs.iter().any(|(n, v)| n == "permission"
                        && pool.text(*v as usize)
                            == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"));
                    service = true;
                }
                if tag == "package" {
                    assert_eq!(stack.last().unwrap(), "queries");
                    assert_eq!(
                        pool.text(u32_at(&out, at + 52).unwrap() as usize),
                        "com.spotify.music"
                    );
                    query = true;
                }
                stack.push(tag);
            } else if kind == END {
                assert_eq!(stack.pop().unwrap(), tag);
            }
            at += u32_at(&out, at + 4).unwrap() as usize;
        }
        assert!(service && query && stack.is_empty());
        assert!(pool.find("spatch.SpatchWidget", usize::MAX).is_some());
        assert!(add_official_playback(&out).is_err());
    }

    #[test]
    fn the_widget_is_added_inside_the_application_once() {
        let manifest = sample();
        let out = add_widget(&manifest, "spatch.SpatchWidget", "Spatch", 0x7f16_0021).unwrap();
        assert_eq!(u32_at(&out, 4).unwrap() as usize, out.len());
        let (pool, mut at) = Pool::parse(&out, 8).unwrap();
        assert!(pool.find("spatch.SpatchWidget", usize::MAX).is_some());
        assert_eq!(pool.text(0), "name");
        at += u32_at(&out, at + 4).unwrap() as usize; // the resource map
        let mut kinds = Vec::new();
        while at < out.len() {
            kinds.push((u16_at(&out, at).unwrap(), pool.text(u32_at(&out, at + 20).unwrap() as usize)));
            at += u32_at(&out, at + 4).unwrap() as usize;
        }
        let names: Vec<_> = kinds.iter().map(|(k, n)| format!("{k:x}{n}")).collect();
        assert_eq!(names.first().unwrap(), "102manifest");
        assert_eq!(names[1], "102application");
        assert_eq!(names[2], "102receiver");
        assert_eq!(names[names.len() - 3], "103receiver");
        assert_eq!(names[names.len() - 2], "103application");
        assert!(add_widget(&out, "spatch.SpatchWidget", "Spatch", 0x7f16_0021).is_err());
    }
}
