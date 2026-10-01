package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.storage;

import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.ListPage;
import nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client.S3Client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.DoublePredicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Synchronous {@link ObjectStore}: every call is one S3 request on the calling thread. */
public final class DirectObjectStore implements ObjectStore {

    private static final int LIST_PAGE_SIZE = 1000;

    private final S3Client client;

    public DirectObjectStore(S3Client client) {
        this.client = client;
    }

    @Override
    public void put(String key, byte[] data, ObjectMeta meta) throws IOException {
        client.put(key, data, meta.contentType(), meta.cacheControl());
    }

    @Override
    public byte[] get(String key) throws IOException {
        return client.get(key);
    }

    @Override
    public boolean exists(String key) throws IOException {
        return client.head(key);
    }

    @Override
    public void delete(String key) throws IOException {
        client.delete(key);
    }

    @Override
    public Stream<String> listKeys(String prefix) throws IOException {
        Iterator<String> it = new PagedKeyIterator(prefix);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    @Override
    public List<String> listPrefixes(String prefix) throws IOException {
        List<String> result = new ArrayList<>();
        String token = null;
        do {
            ListPage page = client.list(prefix, "/", token, LIST_PAGE_SIZE);
            result.addAll(page.commonPrefixes());
            token = page.nextToken();
        } while (token != null);
        return result;
    }

    @Override
    public boolean deletePrefix(String prefix, DoublePredicate onProgress) throws IOException {
        // Pass 1 counts so progress is meaningful; pass 2 lists and deletes page by page, which keeps memory bounded.
        long total = 0;
        String token = null;
        do {
            ListPage page = client.list(prefix, null, token, LIST_PAGE_SIZE);
            total += page.keys().size();
            token = page.nextToken();
        } while (token != null);
        if (total == 0) {
            onProgress.test(1d);
            return true;
        }

        long deleted = 0;
        token = null;
        do {
            ListPage page = client.list(prefix, null, token, LIST_PAGE_SIZE);
            List<String> keys = page.keys();
            if (!keys.isEmpty()) {
                for (String failed : client.deleteObjects(keys)) client.delete(failed); // throws if it still fails
                deleted += keys.size();
                if (!onProgress.test(Math.min(1d, deleted / (double) total))) return false;
            }
            token = page.nextToken();
        } while (token != null);
        return true;
    }

    @Override
    public void close() {
        // the client is owned and closed by the storage
    }

    private final class PagedKeyIterator implements Iterator<String> {
        private final String prefix;
        private Iterator<String> current = Collections.emptyIterator();
        private String token;
        private boolean finished;

        PagedKeyIterator(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean hasNext() {
            while (!current.hasNext()) {
                if (finished) return false;
                try {
                    ListPage page = client.list(prefix, null, token, LIST_PAGE_SIZE);
                    current = page.keys().iterator();
                    token = page.nextToken();
                    if (token == null) finished = true;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return true;
        }

        @Override
        public String next() {
            if (!hasNext()) throw new NoSuchElementException();
            return current.next();
        }
    }
}
