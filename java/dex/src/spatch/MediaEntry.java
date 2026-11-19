package spatch;

/** Media rows and the shared twelve-field song payload. */
class MediaEntry {
    final String title, subtitle, uri, image;
    final int category;
    String queueKey = "";
    boolean editable;
    MediaEntry(String title, String subtitle, String uri, String image, int category) { this.title = title; this.subtitle = subtitle; this.uri = uri; this.image = image; this.category = category; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof MediaEntry)) return false;
        MediaEntry e = (MediaEntry) o;
        return title.equals(e.title) && subtitle.equals(e.subtitle) && uri.equals(e.uri) && image.equals(e.image) && category == e.category;
    }
    @Override public int hashCode() { return java.util.Objects.hash(title, subtitle, uri, image, category); }

    /** A playlist song: a media row plus what Rust's playlist fields carry, and its place in the playlist. */
    static final class Song extends MediaEntry {
        static final int FIELDS = 12;
        final String album, albumUri, artistUri;
        final long duration;
        final int index;
        final boolean recommended, explicit;
        boolean liked;
        Song(String[] f, int at, int index) {
            super(f[at], f[at + 1], f[at + 2], f[at + 3], -1);
            album = f[at + 4]; albumUri = f[at + 5]; artistUri = f[at + 6];
            long ms = 0;
            try { ms = Long.parseLong(f[at + 7]); } catch (NumberFormatException ignored) { /* no length */ }
            duration = ms;
            liked = !f[at + 9].isEmpty();
            recommended = !f[at + 10].isEmpty();
            explicit = !f[at + 11].isEmpty();
            this.index = index;
        }
    }
}
