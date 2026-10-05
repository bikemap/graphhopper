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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BikemapRemoteElevationProviderTest {
    private static final byte[] COG = Base64.getDecoder().decode(BikemapElevationProviderTest.FLOAT32_COG);
    private static final String S3_SOURCE = "s3://elevation-test/2026-09/continuous";
    private static final String CACHE_NAME = "bikemap_n46e010.gh";

    @TempDir
    Path directory;

    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI endpoint;
    private TestProvider provider;
    private volatile int responseStatus = 200;
    private volatile byte[] responseBody = COG;
    private volatile String authorization;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
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
    }

    private void setError(int status, String errorCode) {
        responseBody = ("<Error><Code>" + errorCode + "</Code><Message>Test failure</Message></Error>")
                .getBytes(StandardCharsets.UTF_8);
        responseStatus = status;
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            requestPaths.add(exchange.getRequestURI().getPath());
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = responseBody;
            exchange.getResponseHeaders().set("Content-Type", responseStatus == 200 ? "image/tiff" : "application/xml");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
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
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .forcePathStyle(true)
                    .overrideConfiguration(config -> config.retryPolicy(RetryPolicy.none()))
                    .build();
        }
    }
}
