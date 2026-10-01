package nl.klrnbk.minecraft.plugins.bluemap.addon.s3.client;

import java.util.List;

/** One page of a ListObjectsV2 response. {@code nextToken} is null on the last page. */
public record ListPage(List<String> keys, List<String> commonPrefixes, String nextToken) {}
