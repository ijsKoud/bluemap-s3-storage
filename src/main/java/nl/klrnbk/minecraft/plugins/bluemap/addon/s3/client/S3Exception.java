package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client;

import java.io.IOException;

/** A failed S3 request. Never carries credentials, signatures or response bodies. */
public class S3Exception extends IOException {

    private final int status;
    private final String code;

    public S3Exception(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public S3Exception(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
        this.code = null;
    }

    /** HTTP status, or -1 when there was no response (I/O error, timeout). */
    public int status() {
        return status;
    }

    /** S3 error code such as {@code NoSuchBucket}, or null. */
    public String code() {
        return code;
    }
}
