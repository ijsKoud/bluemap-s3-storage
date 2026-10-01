package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

/** Headers stored with an object so a reverse proxy can serve it straight from the bucket. */
public record ObjectMeta(String contentType, String cacheControl) {}
