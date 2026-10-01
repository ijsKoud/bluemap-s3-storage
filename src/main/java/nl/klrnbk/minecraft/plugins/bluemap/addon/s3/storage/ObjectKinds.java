package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

/** Chooses Content-Type and Cache-Control so objects can be served directly by a reverse proxy. */
public final class ObjectKinds {

    private final String tileCacheControl;
    private final String metaCacheControl;

    public ObjectKinds(String tileCacheControl, String metaCacheControl) {
        this.tileCacheControl = tileCacheControl;
        this.metaCacheControl = metaCacheControl;
    }

    /**
     * Hires tiles (compressed or not) are served as opaque bytes: the webapp gunzips them itself with
     * client-decompression, so there must be NO Content-Encoding header on them.
     */
    public ObjectMeta hires() {
        return new ObjectMeta("application/octet-stream", tileCacheControl);
    }

    public ObjectMeta lowres() {
        return new ObjectMeta("image/png", tileCacheControl);
    }

    public ObjectMeta json() {
        return new ObjectMeta("application/json", metaCacheControl);
    }

    /** textures.json is JSON when stored uncompressed, otherwise opaque compressed bytes. */
    public ObjectMeta textures(boolean compressed) {
        return compressed ? new ObjectMeta("application/octet-stream", metaCacheControl) : json();
    }

    public ObjectMeta asset(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        String type = n.endsWith(".png") ? "image/png"
                : n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg"
                : n.endsWith(".gif") ? "image/gif"
                : n.endsWith(".svg") ? "image/svg+xml"
                : n.endsWith(".webp") ? "image/webp"
                : n.endsWith(".json") ? "application/json"
                : n.endsWith(".css") ? "text/css"
                : n.endsWith(".js") ? "text/javascript"
                : "application/octet-stream";
        return new ObjectMeta(type, metaCacheControl);
    }
}
