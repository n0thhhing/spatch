package spatch;

import com.spotify.extendedmetadata.proto.BatchedExtensionResponse;
import com.spotify.playlist4.proto.Playlist4ApiProto;
import io.reactivex.rxjava3.core.Single;

/**
 * Spotify's own web services, made by the app's webgate (RetrofitMaker.createWebgateService), which adds the access
 * and client tokens. The annotations are Spotify 9.2.0.2053's obfuscated Retrofit ones: p.x740 @GET, p.ngr0 @POST,
 * p.gf60 @Headers, p.brs0 @Path, p.xmb @Body.
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
}
