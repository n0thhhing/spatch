//! ProfileService: fetches the signed-in user's Spotify profile (display name, username, avatar).

use super::image_url;
use crate::bridge;
use crate::protos::spotify::profile_esperanto::proto as es;
use prost::Message;
use std::time::Duration;

pub const SERVICE: &str = "spotify.profile_esperanto.proto.v1.ProfileService";
pub const METHOD: &str = "SubscribeToProfiles";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Profile {
    pub display_name: String,
    pub username: String,
    pub image_url: String,
    pub profile_uri: String,
}

pub fn request(username: &str) -> Vec<u8> {
    es::GetProfilesRequest { usernames: vec![username.to_string()] }.encode_to_vec()
}

pub fn decode(bytes: &[u8]) -> Result<Profile, String> {
    let reply = es::GetProfilesResponse::decode(bytes).map_err(|e| e.to_string())?;
    if !(200..300).contains(&reply.status_code) {
        return Err(format!("Profile request failed (status {})", reply.status_code));
    }
    let profile = reply.profiles.into_iter().next().ok_or_else(|| "Profile not found".to_string())?;
    let username = profile.username.map(|v| v.value).unwrap_or_default().trim().to_string();
    let raw_name = profile.name.map(|v| v.value).unwrap_or_default().trim().to_string();
    let display_name = if raw_name.is_empty() { username.clone() } else { raw_name };
    if username.is_empty() && display_name.is_empty() {
        return Err("Profile response missing user identity".to_string());
    }
    let best_image = profile
        .images
        .into_iter()
        .filter(|img| !img.url.is_empty())
        .max_by_key(|img| i64::from(img.max_width.max(0)) * i64::from(img.max_height.max(0)))
        .map(|img| image_url(&img.url))
        .unwrap_or_default();
    let profile_uri = if username.is_empty() { String::new() } else { format!("spotify:user:{username}") };
    Ok(Profile { display_name, username, image_url: best_image, profile_uri })
}

/// Opens `SubscribeToProfiles`, waits up to `wait` for the first valid profile reply, then cancels the stream.
/// Blocks: never call on the UI thread.
pub fn fetch(username: &str, wait: Duration) -> Result<Profile, String> {
    bridge::subscribe_until(SERVICE, METHOD, &request(username), wait, "Spotify didn't answer profile request", |b| {
        (decode(b), true)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protos::com::google::protobuf::StringValue;
    use crate::protos::com::spotify::identity::proto::v3::{IdentityV3Image, IdentityV3UserProfile};

    #[test]
    fn encodes_get_profiles_request() {
        let bytes = request("levi");
        let decoded = es::GetProfilesRequest::decode(bytes.as_slice()).unwrap();
        assert_eq!(decoded.usernames, vec!["levi".to_string()]);
    }

    #[test]
    fn decodes_profile_picking_largest_image_and_cdn_url() {
        let reply = es::GetProfilesResponse {
            status_code: 200,
            profiles: vec![IdentityV3UserProfile {
                username: Some(StringValue { value: "levi".into() }),
                name: Some(StringValue { value: "Levi".into() }),
                images: vec![
                    IdentityV3Image { max_width: 64, max_height: 64, url: "https://i.scdn.co/small".into() },
                    IdentityV3Image { max_width: 300, max_height: 300, url: "spotify:image:ab12".into() },
                ],
                ..Default::default()
            }],
        };
        let profile = decode(&reply.encode_to_vec()).unwrap();
        assert_eq!(
            profile,
            Profile {
                display_name: "Levi".into(),
                username: "levi".into(),
                image_url: "https://i.scdn.co/image/ab12".into(),
                profile_uri: "spotify:user:levi".into(),
            }
        );
    }

    #[test]
    fn falls_back_to_username_when_display_name_is_empty() {
        let reply = es::GetProfilesResponse {
            status_code: 200,
            profiles: vec![IdentityV3UserProfile {
                username: Some(StringValue { value: "levi".into() }),
                name: Some(StringValue { value: String::new() }),
                ..Default::default()
            }],
        };
        let profile = decode(&reply.encode_to_vec()).unwrap();
        assert_eq!(profile.display_name, "levi");
        assert_eq!(profile.profile_uri, "spotify:user:levi");
    }

    #[test]
    fn rejects_error_status_or_empty_profiles() {
        let err = es::GetProfilesResponse { status_code: 404, profiles: vec![] };
        assert!(decode(&err.encode_to_vec()).unwrap_err().contains("404"));

        let empty = es::GetProfilesResponse { status_code: 200, profiles: vec![] };
        assert_eq!(decode(&empty.encode_to_vec()), Err("Profile not found".into()));
    }
}
