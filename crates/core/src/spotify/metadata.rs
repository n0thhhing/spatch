//! ClassicMetadataService: albums (their tracks) and artists (top tracks, releases) by link.

use super::playlist::Track;
use crate::bridge;
use super::wire;
use crate::protos::com::spotify::metadata::cosmos::proto as cosmos;
use crate::protos::com::spotify::metadata::proto as md;
use cosmos::metadata_item::Item as Entity;
use prost::Message;

pub const SERVICE: &str = "spotify.metadata_esperanto.proto.ClassicMetadataService";

const BASE62: &[u8; 62] = b"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

/// Spotify's 16-byte id as the 22-character base62 text used in links.
pub fn base62(gid: &[u8]) -> String {
    let mut value = gid.iter().fold(0u128, |acc, &b| (acc << 8) | b as u128);
    let mut text = [b'0'; 22];
    for slot in text.iter_mut().rev() {
        *slot = BASE62[(value % 62) as usize];
        value /= 62;
    }
    String::from_utf8_lossy(&text).into_owned()
}

fn link(kind: &str, gid: &[u8]) -> String {
    format!("spotify:{kind}:{}", base62(gid))
}

/// The largest image of a group as its public CDN URL; empty if there is none.
fn cover(group: &Option<md::ImageGroup>, loose: &[md::Image]) -> String {
    let images = group.as_ref().map_or(loose, |g| g.image.as_slice());
    let images = if images.is_empty() { loose } else { images };
    images
        .iter()
        .filter(|i| !i.file_id.is_empty())
        .max_by_key(|i| i.width.max(i.size))
        .map_or_else(String::new, |i| format!("https://i.scdn.co/image/{}", i.file_id.iter().map(|b| format!("{b:02x}")).collect::<String>()))
}

fn artists(list: &[md::Artist]) -> String {
    list.iter().map(|a| a.name.as_str()).filter(|n| !n.is_empty()).collect::<Vec<_>>().join(", ")
}

/// An album page: its heading and tracks in disc order.
#[derive(Debug, Default, PartialEq)]
pub struct AlbumPage {
    pub name: String,
    pub subtitle: String,
    pub uri: String,
    pub image: String,
    pub tracks: Vec<Track>,
}

/// An artist page: popular tracks and releases.
#[derive(Debug, Default, PartialEq)]
pub struct ArtistPage {
    pub name: String,
    pub image: String,
    /// Links to resolve into the popular tracks and the releases.
    pub top: Vec<String>,
    pub releases: Vec<String>,
}

/// A podcast: its heading and the episodes it lists (names may be empty until resolved).
#[derive(Debug, Default, PartialEq)]
pub struct ShowPage {
    pub name: String,
    pub subtitle: String,
    pub image: String,
    pub episodes: Vec<Track>,
}

/// What one entity of a reply turned out to be.
#[derive(Debug, PartialEq)]
pub enum Found {
    Album(AlbumPage),
    Artist(ArtistPage),
    Show(ShowPage),
    Track(Track),
    /// A release shown on a shelf: name, link, cover.
    Release { name: String, subtitle: String, uri: String, image: String },
}

pub fn request(uris: &[String]) -> Vec<u8> {
    cosmos::MultiRequest { uris: uris.to_vec() }.encode_to_vec()
}

fn album_page(a: &md::Album) -> AlbumPage {
    let by = artists(&a.artist);
    let year = a.date.as_ref().map_or(0, |d| d.year);
    let uri = if a.gid.is_empty() { String::new() } else { link("album", &a.gid) };
    let image = cover(&a.cover_group, &a.cover);
    let tracks = a
        .disc
        .iter()
        .flat_map(|d| d.track.iter())
        .map(|t| Track { title: t.name.clone(), artist: if t.artist.is_empty() { by.clone() } else { artists(&t.artist) }, uri: link("track", &t.gid), image: image.clone(),
            album: a.name.clone(), album_uri: uri.clone(),
            artist_uri: t.artist.first().or_else(|| a.artist.first()).map_or_else(String::new, |artist| link("artist", &artist.gid)),
            duration_ms: t.duration.max(0) as u32, explicit: t.explicit, ..Default::default() })
        .collect();
    AlbumPage { name: a.name.clone(), subtitle: [by, if year > 0 { year.to_string() } else { String::new() }].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" • "), uri, image, tracks }
}

/// The entities of a reply, in request order; links Spotify couldn't give are skipped.
pub fn decode(bytes: &[u8]) -> Result<Vec<Found>, String> {
    let reply = cosmos::MultiResponse::decode(bytes).map_err(|e| e.to_string())?;
    Ok(reply
        .items
        .into_iter()
        .filter_map(|i| match i.item? {
            Entity::Album(a) if !a.disc.is_empty() => Some(Found::Album(album_page(&a))),
            Entity::Album(a) => Some(Found::Release {
                subtitle: [artists(&a.artist), a.date.as_ref().map_or(String::new(), |d| if d.year > 0 { d.year.to_string() } else { String::new() })].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" • "),
                uri: link("album", &a.gid),
                image: cover(&a.cover_group, &a.cover),
                name: a.name,
            }),
            Entity::Artist(a) => Some(Found::Artist(ArtistPage {
                image: cover(&a.portrait_group, &a.portrait),
                top: a.top_track.first().map(|t| t.track.iter().map(|t| link("track", &t.gid)).collect()).unwrap_or_default(),
                releases: [&a.album_group, &a.single_group].into_iter().flatten().flat_map(|g| g.album.iter()).map(|al| link("album", &al.gid)).collect(),
                name: a.name,
            })),
            Entity::Show(s) => {
                let image = cover(&s.cover_image, &[]);
                let episodes = s
                    .episode
                    .iter()
                    .map(|e| Track {
                        title: e.name.clone(),
                        artist: e.publish_time.as_ref().map_or(String::new(), |d| if d.year > 0 { format!("{}-{:02}-{:02}", d.year, d.month, d.day) } else { String::new() }),
                        uri: link("episode", &e.gid),
                        image: if e.cover_image.is_some() { cover(&e.cover_image, &[]) } else { image.clone() },
                        duration_ms: e.duration.max(0) as u32, explicit: e.explicit,
                        ..Default::default()
                    })
                    .collect();
                Some(Found::Show(ShowPage { name: s.name, subtitle: s.publisher, image, episodes }))
            }
            Entity::Episode(e) => Some(Found::Track(Track {
                title: e.name.clone(),
                artist: e.show.as_ref().map_or(String::new(), |s| s.name.clone()),
                uri: link("episode", &e.gid),
                image: cover(&e.cover_image, &[]),
                duration_ms: e.duration.max(0) as u32, explicit: e.explicit,
                ..Default::default()
            })),
            Entity::Track(t) => Some(Found::Track(track_of(&t))),
            _ => None,
        })
        .collect())
}

/// A spotify.metadata.Track as Spatch's row.
pub fn track_of(t: &md::Track) -> Track {
    let image = t.album.as_ref().map_or_else(String::new, |al| cover(&al.cover_group, &al.cover));
    Track { title: t.name.clone(), artist: artists(&t.artist), uri: link("track", &t.gid), image,
        album: t.album.as_ref().map_or_else(String::new, |a| a.name.clone()),
        album_uri: t.album.as_ref().map_or_else(String::new, |a| link("album", &a.gid)),
        artist_uri: t.artist.first().map_or_else(String::new, |a| link("artist", &a.gid)),
        duration_ms: t.duration.max(0) as u32, explicit: t.explicit, ..Default::default() }
}

/// Fills in tracks that only have their links, from the core's metadata service.
pub fn resolve(tracks: &mut [Track]) {
    super::mdata::resolve_observe(tracks);
    resolve_tracks(tracks, fetch);
}

/// Fills in tracks still without a name from Spotify's extended-metadata service directly, naming the account's
/// country and catalogue, for accounts whose core answers nothing. Returns (filled, asked, refusals by status).
pub fn resolve_extended(tracks: &mut [Track], country: &str, catalogue: &str) -> (usize, usize, Vec<(u64, usize)>) {
    use super::extended;
    let wanted: Vec<usize> = (0..tracks.len()).filter(|&i| tracks[i].title.is_empty() && tracks[i].uri.starts_with("spotify:track:")).collect();
    let (mut filled, mut refused) = (0, std::collections::BTreeMap::new());
    for chunk in wanted.chunks(100) {
        let uris: Vec<String> = chunk.iter().map(|&i| tracks[i].uri.clone()).collect();
        let reply = match bridge::extended_metadata(&extended::request(&uris, extended::TRACK_V4, Some((country, catalogue)))) {
            Ok(reply) => reply,
            Err(e) => {
                log::warn!("Extended metadata: {e}");
                break;
            }
        };
        let mut by_uri = std::collections::HashMap::new();
        for (uri, status, payload) in extended::decode(&reply) {
            match payload.and_then(|p| md::Track::decode(p.as_slice()).ok()) {
                Some(t) => {
                    by_uri.insert(uri, track_of(&t));
                }
                None => *refused.entry(status).or_insert(0) += 1,
            }
        }
        for &i in chunk {
            if let Some(found) = by_uri.get(&tracks[i].uri) {
                let Track { ref uri, added, liked, recommended, .. } = tracks[i];
                tracks[i] = Track { uri: uri.clone(), added, liked, recommended, ..found.clone() };
                filled += 1;
            }
        }
    }
    (filled, wanted.len(), refused.into_iter().collect())
}

/// Fill catalog fields while preserving the playlist row's link, date, and flags.
pub fn resolve_public(tracks: &mut [Track]) {
    let wanted: Vec<String> = tracks.iter().filter(|t| t.title.is_empty() && t.uri.starts_with("spotify:track:")).map(|t| t.uri.clone()).collect();
    if wanted.is_empty() {
        return;
    }
    match bridge::public_track_metadata(&wanted) {
        Ok(fields) => merge_public(tracks, &fields),
        Err(error) => log::warn!("Public track details: {error}"),
    }
}

fn merge_public(tracks: &mut [Track], fields: &[String]) {
    let (rows, remainder) = fields.as_chunks::<8>();
    if !remainder.is_empty() {
        return;
    }
    let by_uri: std::collections::HashMap<_, _> = rows.iter().map(|f| (f[2].as_str(), f)).collect();
    for track in tracks.iter_mut().filter(|t| t.title.is_empty()) {
        if let Some(f) = by_uri.get(track.uri.as_str()).filter(|f| !f[0].is_empty()) {
            track.title.clone_from(&f[0]);
            for (value, detail) in [(&mut track.artist, &f[1]), (&mut track.image, &f[3]), (&mut track.album, &f[4]),
                (&mut track.album_uri, &f[5]), (&mut track.artist_uri, &f[6])] {
                if !detail.is_empty() {
                    value.clone_from(detail);
                }
            }
            track.duration_ms = f[7].parse::<u32>().ok().filter(|&ms| ms != 0).unwrap_or(track.duration_ms);
        }
    }
}

fn fetch(uris: &[String]) -> Result<Vec<Found>, String> {
    decode(&bridge::call(SERVICE, "MultigetEntity", &request(uris))?)
}

/// Everything an album or artist page shows, in one go: the heading, tracks and releases.
#[derive(Debug, Default, PartialEq)]
pub struct Page {
    pub name: String,
    pub subtitle: String,
    pub image: String,
    pub tracks: Vec<Track>,
    /// (name, subtitle, uri, image) per release.
    pub releases: Vec<(String, String, String, String)>,
}

fn resolve_tracks(tracks: &mut [Track], fetch_fn: impl Fn(&[String]) -> Result<Vec<Found>, String>) {
    let missing: Vec<String> = tracks.iter().filter(|t| (t.title.is_empty() || t.duration_ms == 0) && !t.uri.is_empty()).map(|t| t.uri.clone()).collect();
    if missing.is_empty() {
        return;
    }
    let mut by_uri = std::collections::HashMap::new();
    for chunk in missing.chunks(50) {
        for item in fetch_fn(chunk).unwrap_or_default() {
            if let Found::Track(t) = item {
                if !t.title.is_empty() {
                    by_uri.insert(t.uri.clone(), t);
                }
            }
        }
    }
    for t in tracks.iter_mut() {
        if let Some(resolved) = by_uri.get(&t.uri) {
            let image = if resolved.image.is_empty() { t.image.clone() } else { resolved.image.clone() };
            let artist = if resolved.artist.is_empty() { t.artist.clone() } else { resolved.artist.clone() };
            *t = Track { image, artist, added: t.added, liked: t.liked, recommended: t.recommended, ..resolved.clone() };
        }
    }
}

/// Loads the page for an album or artist link. Blocks: never on the UI thread.
pub fn page(uri: &str) -> Result<Page, String> {
    page_with(uri, fetch)
}

fn page_with(uri: &str, fetch_fn: impl Fn(&[String]) -> Result<Vec<Found>, String>) -> Result<Page, String> {
    let first = fetch_fn(&[uri.to_string()])?.into_iter().next().ok_or("Spotify has nothing for this link")?;
    match first {
        Found::Album(mut a) => {
            // Album replies sometimes omit track names (sending only track gids, like Artist/Show do): resolve them by link.
            resolve_tracks(&mut a.tracks, &fetch_fn);
            Ok(Page { name: a.name, subtitle: a.subtitle, image: a.image, tracks: a.tracks, releases: Vec::new() })
        }
        Found::Artist(artist) => {
            // The artist reply only names the tracks and releases by link: ask for the first few in one call.
            let top: Vec<_> = artist.top.iter().take(10).cloned().collect();
            let releases: Vec<_> = artist.releases.iter().take(16).cloned().collect();
            let found = fetch_fn(&[top.clone(), releases.clone()].concat()).unwrap_or_default();
            let mut page = Page { name: artist.name, subtitle: "Artist".into(), image: artist.image, ..Default::default() };
            for f in found {
                match f {
                    Found::Track(t) => page.tracks.push(t),
                    Found::Release { name, subtitle, uri, image } => page.releases.push((name, subtitle, uri, image)),
                    Found::Album(a) => page.releases.push((a.name, a.subtitle, a.uri, a.image)),
                    Found::Artist(_) | Found::Show(_) => {}
                }
            }
            Ok(page)
        }
        Found::Show(show) => {
            // Episodes come back with names when Spotify includes them; otherwise ask for the newest by link.
            let mut episodes = show.episodes;
            let n = episodes.len().min(30);
            resolve_tracks(&mut episodes[..n], &fetch_fn);
            episodes.retain(|e| !e.title.is_empty());
            Ok(Page { name: show.name, subtitle: if show.subtitle.is_empty() { "Podcast".into() } else { show.subtitle }, image: show.image, tracks: episodes, releases: Vec::new() })
        }
        _ => Err("That link isn’t an album, artist or podcast".into()),
    }
}

/// A track's audio files, taken from its own TRACK_V4 payload (field 12) rather than the gated AUDIO_FILES
/// extension: the ids are the same and TRACK_V4 is what already answers for this account.
pub fn audio_files(uri: &str, country: &str, catalogue: &str) -> Result<Vec<(u32, String, u64)>, String> {
    let reply = bridge::extended_metadata_as_web(&super::extended::request(&[uri.to_string()], super::extended::TRACK_V4, Some((country, catalogue))))?;
    let decoded = super::extended::decode(&reply);
    log::info!("audio files via TRACK_V4: {} bytes, statuses {:?}", reply.len(), decoded.iter().map(|(_, s, p)| (*s, p.as_ref().map_or(0, Vec::len))).collect::<Vec<_>>());
    for (_uri, status, payload) in decoded {
        let Some(payload) = payload.filter(|_| (200..300).contains(&status)) else { continue };
        let files = audio_files_of(&payload);
        if !files.is_empty() {
            return Ok(files);
        }
    }
    Ok(Vec::new())
}

/// spotify.metadata.Track.file (12) and .preview (15): each is {file_id = 1 (bytes), format = 2}.
fn audio_files_of(track: &[u8]) -> Vec<(u32, String, u64)> {
    let mut out = Vec::new();
    for (n, v) in wire::fields(track) {
        if n != 12 && n != 15 { continue; }
        let (mut format, mut id) = (0u32, String::new());
        for (m, w) in wire::fields(v.bytes()) {
            match m {
                // The id travels as the 16 raw bytes; storage-resolve wants the 22-char base62 text of them.
                1 => id = base62(w.bytes()),
                2 => format = w.number() as u32,
                _ => {}
            }
        }
        if !id.is_empty() { out.push((format, id, 0)); }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(e: Entity) -> cosmos::MetadataItem {
        cosmos::MetadataItem { item: Some(e) }
    }

    #[test]
    fn public_details_match_by_link_and_keep_playlist_fields() {
        let mut tracks = [
            Track { uri: "a".into(), added: 42, liked: true, recommended: true, explicit: true, image: "fallback".into(), ..Track::default() },
            Track { uri: "b".into(), ..Track::default() },
            Track { uri: "c".into(), title: "Already known".into(), ..Track::default() },
            Track { uri: "d".into(), ..Track::default() },
        ];
        let rows = [
            "Second", "Other artist", "b", "cover", "", "", "", "321",
            "Foreign", "", "unexpected", "", "", "", "", "0",
            "First", "Artist", "a", "", "Album", "album-uri", "artist-uri", "123",
            "Replacement", "", "c", "", "", "", "", "0",
        ].map(str::to_owned);
        merge_public(&mut tracks, &rows[..rows.len() - 1]);
        assert!(tracks[0].title.is_empty());
        merge_public(&mut tracks, &rows);
        assert_eq!(tracks[0].clone().fields(), ["First", "Artist", "a", "fallback", "Album", "album-uri", "artist-uri", "123", "42", "1", "1", "1"]);
        assert_eq!(tracks[1].title, "Second");
        assert_eq!(tracks[2].title, "Already known");
        assert!(tracks[3].title.is_empty());
    }

    #[test]
    fn ids_become_22_character_base62() {
        assert_eq!(base62(&[0; 16]), "0".repeat(22));
        assert_eq!(base62(&[0xff; 16]).len(), 22);
        // 61 is the last digit; 62 carries.
        assert_eq!(base62(&{ let mut g = [0u8; 16]; g[15] = 61; g }), format!("{}Z", "0".repeat(21)));
        assert_eq!(base62(&{ let mut g = [0u8; 16]; g[15] = 62; g }), format!("{}10", "0".repeat(20)));
    }

    #[test]
    fn an_album_lists_its_tracks_in_disc_order_with_the_cover() {
        let album = md::Album {
            name: "Between Stations".into(),
            artist: vec![md::Artist { name: "Northbound".into(), ..Default::default() }],
            date: Some(md::Date { year: 2025, ..Default::default() }),
            cover_group: Some(md::ImageGroup { image: vec![md::Image { file_id: vec![0xab, 0x12], width: 640, ..Default::default() }, md::Image { file_id: vec![0x01], width: 64, ..Default::default() }] }),
            disc: vec![md::Disc { number: 1, track: vec![md::Track { name: "Glasshouse".into(), gid: vec![0; 16], ..Default::default() }], ..Default::default() }],
            ..Default::default()
        };
        let found = decode(&cosmos::MultiResponse { items: vec![item(Entity::Album(album))] }.encode_to_vec()).unwrap();
        let Found::Album(page) = &found[0] else { panic!("not an album") };
        assert_eq!((page.name.as_str(), page.subtitle.as_str()), ("Between Stations", "Northbound • 2025"));
        assert_eq!(page.image, "https://i.scdn.co/image/ab12");
        assert_eq!((page.tracks[0].title.as_str(), page.tracks[0].artist.as_str()), ("Glasshouse", "Northbound"));
        assert!(page.tracks[0].uri.starts_with("spotify:track:0000"));
    }

    #[test]
    fn an_artist_names_its_top_tracks_and_releases_by_link() {
        let artist = md::Artist {
            name: "Northbound".into(),
            top_track: vec![md::TopTracks { country: "US".into(), track: vec![md::Track { gid: vec![1; 16], ..Default::default() }] }],
            album_group: vec![md::AlbumGroup { album: vec![md::Album { gid: vec![2; 16], ..Default::default() }] }],
            single_group: vec![md::AlbumGroup { album: vec![md::Album { gid: vec![3; 16], ..Default::default() }] }],
            ..Default::default()
        };
        let found = decode(&cosmos::MultiResponse { items: vec![item(Entity::Artist(artist))] }.encode_to_vec()).unwrap();
        let Found::Artist(page) = &found[0] else { panic!("not an artist") };
        assert_eq!((page.top.len(), page.releases.len()), (1, 2));
        assert!(page.top[0].starts_with("spotify:track:") && page.releases[0].starts_with("spotify:album:"));
    }

    #[test]
    fn a_show_lists_its_episodes_and_an_episode_names_its_show() {
        let show = md::Show {
            name: "Open Frequency".into(),
            publisher: "Radio Co".into(),
            episode: vec![md::Episode { name: "Pilot".into(), gid: vec![7; 16], publish_time: Some(md::Date { year: 2025, month: 3, day: 9, ..Default::default() }), ..Default::default() }],
            ..Default::default()
        };
        let found = decode(&cosmos::MultiResponse { items: vec![item(Entity::Show(show))] }.encode_to_vec()).unwrap();
        let Found::Show(page) = &found[0] else { panic!("not a show") };
        assert_eq!((page.name.as_str(), page.subtitle.as_str(), page.episodes[0].title.as_str(), page.episodes[0].artist.as_str()), ("Open Frequency", "Radio Co", "Pilot", "2025-03-09"));
        assert!(page.episodes[0].uri.starts_with("spotify:episode:"));
        let episode = md::Episode { name: "Pilot".into(), show: Some(md::Show { name: "Open Frequency".into(), ..Default::default() }), ..Default::default() };
        let found = decode(&cosmos::MultiResponse { items: vec![item(Entity::Episode(episode))] }.encode_to_vec()).unwrap();
        assert!(matches!(&found[0], Found::Track(t) if t.artist == "Open Frequency"));
    }

    #[test]
    fn requests_carry_the_links_and_errors_are_skipped() {
        let sent = cosmos::MultiRequest::decode(request(&["spotify:album:x".into()]).as_slice()).unwrap();
        assert_eq!(sent.uris, ["spotify:album:x"]);
        let failed = cosmos::MultiResponse { items: vec![item(Entity::Error(404))] };
        assert!(decode(&failed.encode_to_vec()).unwrap().is_empty());
    }

    #[test]
    fn resolves_album_tracks_when_names_are_missing() {
        let album = md::Album {
            name: "Summertime".into(),
            artist: vec![md::Artist { name: "Artist".into(), ..Default::default() }],
            disc: vec![md::Disc {
                number: 1,
                track: vec![
                    md::Track { gid: vec![1; 16], ..Default::default() },
                    md::Track { gid: vec![2; 16], name: "Already Named".into(), duration: 180_000, ..Default::default() },
                ],
                ..Default::default()
            }],
            ..Default::default()
        };
        let mut page = album_page(&album);
        assert!(page.tracks[0].title.is_empty());
        let expected_uri = page.tracks[0].uri.clone();
        resolve_tracks(&mut page.tracks, |uris| {
            assert_eq!(uris, [expected_uri.as_str()]);
            Ok(vec![Found::Track(Track { title: "Resolved Song".into(), artist: "Feat Artist".into(), uri: expected_uri.clone(), ..Default::default() })])
        });
        assert_eq!((page.tracks[0].title.as_str(), page.tracks[0].artist.as_str()), ("Resolved Song", "Feat Artist"));
        assert_eq!(page.tracks[1].title, "Already Named");
    }

    #[test]
    fn artist_page_keeps_album_links_on_releases_with_discs() {
        let album_gid = vec![2; 16];
        let expected_uri = link("album", &album_gid);
        let artist_uri = "spotify:artist:northbound";
        let page = page_with(artist_uri, |uris| {
            if uris == [artist_uri] {
                Ok(vec![Found::Artist(ArtistPage {
                    name: "Northbound".into(),
                    image: String::new(),
                    top: Vec::new(),
                    releases: vec![expected_uri.clone()],
                })])
            } else {
                let album = md::Album {
                    gid: album_gid.clone(),
                    name: "Between Stations".into(),
                    artist: vec![md::Artist { name: "Northbound".into(), ..Default::default() }],
                    date: Some(md::Date { year: 2025, ..Default::default() }),
                    disc: vec![md::Disc { number: 1, track: vec![md::Track { gid: vec![1; 16], ..Default::default() }], ..Default::default() }],
                    ..Default::default()
                };
                Ok(vec![Found::Album(album_page(&album))])
            }
        }).unwrap();
        assert_eq!(page.releases.len(), 1);
        assert_eq!(page.releases[0].0, "Between Stations");
        assert_eq!(page.releases[0].2, expected_uri);
    }
}