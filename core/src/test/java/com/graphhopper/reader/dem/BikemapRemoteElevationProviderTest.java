/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.reader.dem;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BikemapRemoteElevationProviderTest {
    private static final byte[] COG = Base64.getDecoder().decode(BikemapElevationProviderTest.FLOAT32_COG);
    private static final String S3_SOURCE = "s3://elevation-test/2026-09/continuous";
    private static final String CACHE_NAME = "bikemap_n46e010.gh";
    private static final String ETAG = "\"elevation-revision-1\"";

    @TempDir
    Path directory;

    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<RangeRequest> rangeRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger activeRangeRequests = new AtomicInteger();
    private final AtomicInteger maximumActiveRangeRequests = new AtomicInteger();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI endpoint;
    private TestProvider provider;
    private volatile int responseStatus = 200;
    private volatile byte[] responseBody = COG;
    private volatile String authorization;
    private volatile boolean serveRanges;
    private volatile RangeFailure rangeFailure = RangeFailure.NONE;
    private volatile CountDownLatch overlappingRanges;
    private volatile boolean overlapTimedOut;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "bikemap-s3-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        provider = createProvider(S3_SOURCE);
    }

    @AfterEach
    void tearDown() {
        if (provider != null)
            provider.release();
        if (server != null)
            server.stop(0);
        if (serverExecutor != null)
            serverExecutor.shutdownNow();
    }

    @Test
    void convertsS3TileAndReusesOnlyConvertedCacheWithoutNetwork() throws IOException {
        assertEquals(100, provider.getEle(46.9999, 10));
        assertTrue(Double.isNaN(provider.getEle(46.5, 10.5)));
        assertEquals(900, provider.getEle(46, 10.9999));
        assertEquals(List.of("/elevation-test/2026-09/continuous/n46e010.tif"), requestPaths);
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 "));
        assertDirectoryContains(Set.of(CACHE_NAME));
        provider.release();
        server.stop(0);
        server = null;

        provider = createProvider(S3_SOURCE);
        provider.forbidClientCreation = true;
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(0, provider.clientsCreated);
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void changingSourceUriInvalidatesConvertedCache() throws IOException {
        assertEquals(100, provider.getEle(46.9999, 10));
        provider.release();

        provider = createProvider("s3://elevation-test/2026-10/continuous/");
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(List.of("/elevation-test/2026-09/continuous/n46e010.tif",
                "/elevation-test/2026-10/continuous/n46e010.tif"), requestPaths);
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void remembersMissingS3TileOnlyUntilProviderRestart() throws IOException {
        setError(404, "NoSuchKey");
        assertEquals(0, provider.getEle(46.9999, 10));
        setSuccess(COG);
        assertEquals(0, provider.getEle(46.9999, 10));
        assertEquals(1, requestPaths.size());
        assertDirectoryContains(Set.of());
        provider.release();

        provider = createProvider(S3_SOURCE);
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(2, requestPaths.size());
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @ParameterizedTest
    @CsvSource({"403, AccessDenied", "503, SlowDown", "404, NoSuchBucket"})
    void s3FailuresThrowCleanUpAndAllowRetry(int status, String errorCode) throws IOException {
        setError(status, errorCode);
        assertThrows(RuntimeException.class, () -> provider.getEle(46.9999, 10));
        assertEquals(1, requestPaths.size());
        assertDirectoryContains(Set.of());

        setSuccess(COG);
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(2, requestPaths.size());
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void invalidRemoteTiffIsRemovedAndCanBeRetried() throws IOException {
        setSuccess(new byte[]{1, 2, 3});
        assertThrows(RuntimeException.class, () -> provider.getEle(46.9999, 10));
        assertDirectoryContains(Set.of());

        setSuccess(COG);
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(2, requestPaths.size());
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void truncatedRemoteCacheIsRebuilt() throws IOException {
        Files.write(directory.resolve(CACHE_NAME), new byte[]{0, 2, 'G', 'H'});
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(1, requestPaths.size());
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void remoteCacheWithValidHeaderButTruncatedBackingFileIsFetchedAgain() throws IOException {
        assertEquals(100, provider.getEle(46.9999, 10));
        provider.release();
        try (RandomAccessFile cache = new RandomAccessFile(directory.resolve(CACHE_NAME).toFile(), "rw")) {
            cache.setLength(100);
        }

        provider = createProvider(S3_SOURCE);
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(2, requestPaths.size());
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void downloadsLargeS3TileWithConcurrentRangesAndOneObjectVersion() throws IOException {
        byte[] largeCog = largeCog();
        setRangedSuccess(largeCog);
        provider.setThreads(3);
        overlappingRanges = new CountDownLatch(2);

        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(900, provider.getEle(46, 10.9999));
        assertFalse(overlapTimedOut, "At least two remaining ranges must be requested concurrently");
        assertTrue(maximumActiveRangeRequests.get() >= 2);
        assertEquals(4, rangeRequests.size());
        assertEquals(new RangeRequest(range(0, BikemapElevationProvider.DOWNLOAD_CHUNK_BYTES - 1), null),
                rangeRequests.get(0));
        assertEquals(expectedRemainingRanges(largeCog.length), Set.copyOf(rangeRequests.subList(1, rangeRequests.size())));
        assertEquals(1, provider.clientsCreated);
        assertDirectoryContains(Set.of(CACHE_NAME));
        provider.release();

        provider = createProvider(S3_SOURCE);
        provider.forbidClientCreation = true;
        assertEquals(900, provider.getEle(46, 10.9999));
        assertEquals(4, rangeRequests.size());
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void oneWorkerDownloadsRemainingRangesSequentially() throws IOException {
        byte[] largeCog = largeCog();
        setRangedSuccess(largeCog);
        provider.setThreads(1);

        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(900, provider.getEle(46, 10.9999));
        int chunkBytes = BikemapElevationProvider.DOWNLOAD_CHUNK_BYTES;
        assertEquals(List.of(
                new RangeRequest(range(0, chunkBytes - 1), null),
                new RangeRequest(range(chunkBytes, 2L * chunkBytes - 1), ETAG),
                new RangeRequest(range(2L * chunkBytes, 3L * chunkBytes - 1), ETAG),
                new RangeRequest(range(3L * chunkBytes, largeCog.length - 1), ETAG)), rangeRequests);
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void serverIgnoringRangesFallsBackToOneCompleteDownload() throws IOException {
        setSuccess(largeCog());
        provider.setThreads(3);

        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(900, provider.getEle(46, 10.9999));
        assertEquals(List.of(new RangeRequest(range(0, BikemapElevationProvider.DOWNLOAD_CHUNK_BYTES - 1), null)),
                rangeRequests);
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @ParameterizedTest
    @EnumSource(value = RangeFailure.class, names = {"MALFORMED_METADATA", "WRONG_RANGE", "TRUNCATED_BODY", "CHANGED_OBJECT"})
    void rangeFailuresRemovePartialDownloadAndAllowRetry(RangeFailure failure) throws IOException {
        setRangedSuccess(largeCog());
        rangeFailure = failure;
        provider.setThreads(3);

        assertThrows(RuntimeException.class, () -> provider.getEle(46.9999, 10));
        assertDirectoryContains(Set.of());

        rangeFailure = RangeFailure.NONE;
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(900, provider.getEle(46, 10.9999));
        assertEquals(1, provider.clientsCreated);
        assertDirectoryContains(Set.of(CACHE_NAME));
    }

    @Test
    void httpSourceUsesTemporaryTiffAndPersistentConvertedCache() throws IOException {
        provider.release();
        provider = createProvider(endpoint + "/2026-09/continuous/");
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(List.of("/2026-09/continuous/n46e010.tif"), requestPaths);
        assertEquals(0, provider.clientsCreated);
        assertDirectoryContains(Set.of(CACHE_NAME));
        provider.release();

        provider = createProvider(endpoint + "/2026-09/continuous/");
        setError(503, "Unavailable");
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(1, requestPaths.size());
    }

    @Test
    void http404IsMissingButAuthorizationFailureCanBeRetried() throws IOException {
        provider.release();
        provider = createProvider(endpoint + "/continuous");
        setError(404, "NotFound");
        assertEquals(0, provider.getEle(46.9999, 10));
        assertEquals(0, provider.getEle(46.9999, 10));
        assertEquals(1, requestPaths.size());
        assertDirectoryContains(Set.of());
        provider.release();

        provider = createProvider(endpoint + "/continuous");
        setError(403, "Forbidden");
        assertThrows(RuntimeException.class, () -> provider.getEle(46.9999, 10));
        assertDirectoryContains(Set.of());
        setSuccess(COG);
        assertEquals(100, provider.getEle(46.9999, 10));
        assertEquals(3, requestPaths.size());
    }

    private TestProvider createProvider(String source) {
        TestProvider result = new TestProvider(directory.toString(), endpoint);
        result.setBaseURL(source);
        result.setAutoRemoveTemporaryFiles(false);
        return result;
    }

    private void setSuccess(byte[] body) {
        responseBody = body;
        responseStatus = 200;
        serveRanges = false;
    }

    private void setRangedSuccess(byte[] body) {
        setSuccess(body);
        serveRanges = true;
    }

    private void setError(int status, String errorCode) {
        responseBody = ("<Error><Code>" + errorCode + "</Code><Message>Test failure</Message></Error>")
                .getBytes(StandardCharsets.UTF_8);
        responseStatus = status;
        serveRanges = false;
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            requestPaths.add(exchange.getRequestURI().getPath());
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            rangeRequests.add(new RangeRequest(exchange.getRequestHeaders().getFirst("Range"),
                    exchange.getRequestHeaders().getFirst("If-Match")));
            byte[] body = responseBody;
            if (serveRanges && responseStatus == 200) {
                respondRange(exchange, body);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", responseStatus == 200 ? "image/tiff" : "application/xml");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private void respondRange(HttpExchange exchange, byte[] body) throws IOException {
        String requestedRange = exchange.getRequestHeaders().getFirst("Range");
        if (requestedRange == null || !requestedRange.matches("bytes=\\d+-\\d+")) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        String[] bounds = requestedRange.substring("bytes=".length()).split("-");
        int first = Math.toIntExact(Long.parseLong(bounds[0]));
        int last = Math.toIntExact(Math.min(Long.parseLong(bounds[1]), body.length - 1L));
        if (first < 0 || first > last) {
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        if (first > 0 && (!ETAG.equals(exchange.getRequestHeaders().getFirst("If-Match"))
                || rangeFailure == RangeFailure.CHANGED_OBJECT)) {
            byte[] error = "<Error><Code>PreconditionFailed</Code><Message>Object changed</Message></Error>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(412, error.length);
            exchange.getResponseBody().write(error);
            return;
        }

        int active = activeRangeRequests.incrementAndGet();
        maximumActiveRangeRequests.accumulateAndGet(active, Math::max);
        try {
            CountDownLatch overlap = overlappingRanges;
            if (first > 0 && overlap != null) {
                overlap.countDown();
                try {
                    if (!overlap.await(5, TimeUnit.SECONDS))
                        overlapTimedOut = true;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Range server interrupted", ex);
                }
            }
            String contentRange = "bytes " + first + "-" + last + "/" + body.length;
            if (rangeFailure == RangeFailure.MALFORMED_METADATA && first == 0)
                contentRange = "bytes invalid";
            else if (rangeFailure == RangeFailure.WRONG_RANGE && first == BikemapElevationProvider.DOWNLOAD_CHUNK_BYTES)
                contentRange = "bytes " + (first + 1) + "-" + last + "/" + body.length;
            exchange.getResponseHeaders().set("Content-Type", "image/tiff");
            exchange.getResponseHeaders().set("Content-Range", contentRange);
            exchange.getResponseHeaders().set("ETag", ETAG);
            int length = last - first + 1;
            exchange.sendResponseHeaders(206, length);
            if (rangeFailure == RangeFailure.TRUNCATED_BODY && first == BikemapElevationProvider.DOWNLOAD_CHUNK_BYTES)
                length -= 128;
            exchange.getResponseBody().write(body, first, length);
        } finally {
            activeRangeRequests.decrementAndGet();
        }
    }

    private static String range(long first, long last) {
        return "bytes=" + first + "-" + last;
    }

    private static Set<RangeRequest> expectedRemainingRanges(int length) {
        int chunkBytes = BikemapElevationProvider.DOWNLOAD_CHUNK_BYTES;
        return Set.of(
                new RangeRequest(range(chunkBytes, 2L * chunkBytes - 1), ETAG),
                new RangeRequest(range(2L * chunkBytes, 3L * chunkBytes - 1), ETAG),
                new RangeRequest(range(3L * chunkBytes, length - 1), ETAG));
    }

    private static byte[] largeCog() {
        ByteBuffer original = ByteBuffer.wrap(COG).order(ByteOrder.LITTLE_ENDIAN);
        int directoryOffset = original.getInt(4);
        int entries = Short.toUnsignedInt(original.getShort(directoryOffset));
        int tileOffsetEntry = -1;
        int tileBytes = -1;
        for (int index = 0; index < entries; index++) {
            int entry = directoryOffset + 2 + 12 * index;
            int tag = Short.toUnsignedInt(original.getShort(entry));
            if (tag == 324) {
                assertEquals(1, original.getInt(entry + 4));
                tileOffsetEntry = entry + 8;
            } else if (tag == 325) {
                assertEquals(1, original.getInt(entry + 4));
                tileBytes = original.getInt(entry + 8);
            }
        }
        assertTrue(tileOffsetEntry >= 0 && tileBytes > 0);
        int payloadOffset = 3 * BikemapElevationProvider.DOWNLOAD_CHUNK_BYTES + 37;
        byte[] result = Arrays.copyOf(COG, payloadOffset + tileBytes + 17);
        // Place the actual elevation pixels in the last range, so incorrectly assembled ranges cannot decode correctly.
        System.arraycopy(COG, original.getInt(tileOffsetEntry), result, payloadOffset, tileBytes);
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(tileOffsetEntry, payloadOffset);
        return result;
    }

    private record RangeRequest(String range, String ifMatch) {
    }

    private enum RangeFailure {
        NONE, MALFORMED_METADATA, WRONG_RANGE, TRUNCATED_BODY, CHANGED_OBJECT
    }

    private void assertDirectoryContains(Set<String> expected) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(expected, files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
    }

    private static class TestProvider extends BikemapElevationProvider {
        private final URI endpoint;
        private int clientsCreated;
        private boolean forbidClientCreation;

        TestProvider(String cacheDir, URI endpoint) {
            super(cacheDir);
            this.endpoint = endpoint;
        }

        @Override
        S3Client createS3Client() {
            if (forbidClientCreation)
                throw new AssertionError("A cached tile must not initialize an S3 client");
            clientsCreated++;
            return S3Client.builder()
                    .endpointOverride(endpoint)
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(5)))
                    .forcePathStyle(true)
                    .overrideConfiguration(config -> config.retryPolicy(RetryPolicy.none()))
                    .build();
        }
    }
}
