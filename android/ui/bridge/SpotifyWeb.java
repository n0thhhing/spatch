package spatch;

import com.spotify.extendedmetadata.proto.BatchedExtensionResponse;
import com.spotify.playlist4.proto.Playlist4ApiProto;
import com.spotify.searchview.proto.MainViewResponse;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;

/**
 * Spotify's own web services, made by the app's webgate (RetrofitMaker.createWebgateService), which adds the access
 * and client tokens. The annotations are Spotify 9.2.0.2053's obfuscated Retrofit ones: p.x740 @GET, p.ngr0 @POST,
 * p.gf60 @Headers, p.brs0 @Path, p.xmb @Body, p.pjz0 @QueryMap, p.pc60 @HeaderMap.
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

    /** A song's (time-synced) lyrics: color-lyrics v3, as the lyrics screen asks. */
    @p.x740("color-lyrics/v3/track/{trackId}")
    @p.gf60(value = { "Accept: application/protobuf" }, allowUnsafeNonAsciiValues = false)
    Single<p.ii31> lyrics(@p.brs0(value = "trackId", encoded = false) String trackId);

    /** extended-metadata again, with a placeholder header the WebAuth hook swaps for the web-player bearer. */
    @p.ngr0("extended-metadata/v0/extended-metadata")
    @p.gf60(value = { "Accept: application/protobuf", "Content-Type: application/protobuf" }, allowUnsafeNonAsciiValues = false)
    Single<BatchedExtensionResponse> extendedMetadataAsWeb(@p.xmb com.google.protobuf.Empty request,
        @p.pc60(allowUnsafeNonAsciiValues = false) Map<String, String> headers);

    /** One audio file's CDN URL: storage-resolve v2. The path is a full sub-path so shapes can be tried. */
    @p.x740("storage-resolve/v2/files/audio/{path}")
    @p.gf60(value = { "Accept: application/protobuf" }, allowUnsafeNonAsciiValues = false)
    Single<p.ii31> storageResolve(
        @p.brs0(value = "path", encoded = true) String path,
        @p.pjz0(encoded = false) Map<String, String> params
    );

    /** A track's audio key: playplay v2. The reply is a small protobuf (key bytes). */
    @p.x740("playplay/v2/key/track/{file_id}")
    @p.gf60(value = { "Accept: application/protobuf" }, allowUnsafeNonAsciiValues = false)
    Single<p.ii31> playplayKey(@p.brs0(value = "file_id", encoded = false) String fileId);

    /** Catalog search: searchview v3, matching Spotify's own search endpoint. */
    @p.x740("searchview/v3/search")
    @p.gf60(value = { "Accept: application/protobuf" }, allowUnsafeNonAsciiValues = false)
    Single<MainViewResponse> search(
        @p.pjz0(encoded = false) Map<String, String> params,
        @p.pc60(allowUnsafeNonAsciiValues = false) Map<String, String> headers
    );
}
