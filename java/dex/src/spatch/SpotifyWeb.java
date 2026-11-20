package spatch;

import com.spotify.extendedmetadata.proto.BatchedExtensionResponse;
import com.spotify.playlist4.proto.Playlist4ApiProto;
import io.reactivex.rxjava3.core.Single;

/**
 * Spotify's own web services, made by the app's webgate (RetrofitMaker.createWebgateService), which adds the access
 * and client tokens. The annotations are Spotify 9.2.0.2053's obfuscated Retrofit ones: p.x740 @GET, p.ngr0 @POST,
 * p.gf60 @Headers, p.brs0 @Path, p.xmb @Body, p.pjz0 @QueryMap; p.ii31 is OkHttp's ResponseBody. The token interceptor
 * (p.fkl1) adds the account's token for every host, so Spotify's public Web API works through it too.
 */
public interface SpotifyWeb {
    /** A playlist's items, straight from the playlist service. */
    @p.x740("playlist/v2/playlist/{playlist_id}")
    @p.gf60(value = { "Accept: application/protobuf" }, allowUnsafeNonAsciiValues = false)
    Single<Playlist4ApiProto.SelectedListContent> playlist(@p.brs0(value = "playlist_id", encoded = false) String playlistId);

    /** Song, album and artist details: the service the core itself asks (BatchedEntityRequest in, BatchedExtensionResponse out). */
    @p.ngr0("extended-metadata/v0/extended-metadata")
    @p.gf60(value = { "Accept: application/protobuf", "Content-Type: application/protobuf" }, allowUnsafeNonAsciiValues = false)
    Single<BatchedExtensionResponse> extendedMetadata(@p.xmb com.google.protobuf.Empty request);

    /** Spotify's public Web API: one track by id (JSON), for accounts whose song-details service answers 502. */
    @p.x740("https://api.spotify.com/v1/tracks/{id}")
    Single<p.ii31> track(@p.brs0(value = "id", encoded = false) String id, @p.pjz0(encoded = false) java.util.Map<String, String> query);
}
