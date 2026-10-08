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

import com.graphhopper.storage.DAType;
import com.graphhopper.storage.DataAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BikemapParallelElevationProviderTest {
    private static final int SIZE = 1_025;
    private static final int BLOCK_SIZE = 512;
    private static final String CACHE_NAME = "bikemap_n46e010.gh";

    @TempDir
    Path directory;

    private final List<TrackingProvider> providers = new ArrayList<>();
    private Path sourceDirectory;

    @BeforeEach
    void setUp() throws IOException {
        sourceDirectory = Files.createDirectory(directory.resolve("source"));
        writeTiledTiff(sourceDirectory.resolve("n46e010.tif"));
    }

    @AfterEach
    void tearDown() {
        for (TrackingProvider provider : providers)
            provider.release();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MMAP", "RAM_STORE", "RAM_INT_STORE"})
    void parallelConversionMatchesSequentialForEverySample(String storageType) throws IOException, InterruptedException {
        assumeTwoWorkersFitInMemory();
        DAType type = storageType.equals("RAM_INT_STORE") ? DAType.RAM_INT_STORE : DAType.fromString(storageType);
        TrackingProvider sequential = createProvider("sequential", type, 1);
        sequential.getEle(46.9999, 10);
        assertEquals(type, sequential.getDirectory().getDAs().get(CACHE_NAME).getType());
        assertEquals(1, sequential.maximumActive.get());

        TrackingProvider parallel = createProvider("parallel", type, 2);
        parallel.firstWorkersEntered = new CountDownLatch(2);
        parallel.getEle(46.9999, 10);
        assertEquals(type, parallel.getDirectory().getDAs().get(CACHE_NAME).getType());

        // The latch forces two decoders to be active together, independently of machine speed.
        assertEquals(2, parallel.maximumActive.get());
        assertEquals(2, parallel.readers.size());
        assertEquals(expectedRegions(), new HashSet<>(parallel.regions));
        assertEquals(3, parallel.regions.size());

        short[] expected = expectedSamples();
        assertArrayEquals(expected, readSamples(sequential));
        assertArrayEquals(expected, readSamples(parallel));
        assertTrue(Double.isNaN(parallel.getEle(46.5, 10.5)));
        assertDecoderThreadsFinished(sequential);
        assertDecoderThreadsFinished(parallel);
        parallel.release();
        assertDecoderThreadsFinished(parallel);
    }

    @Test
    void validatesConfiguredThreadCount() {
        TrackingProvider provider = new TrackingProvider(directory.resolve("cache").toString());
        providers.add(provider);
        assertThrows(IllegalArgumentException.class, () -> provider.setThreads(0));
        assertThrows(IllegalArgumentException.class, () -> provider.setThreads(-1));
        assertSame(provider, provider.setThreads(1));
    }

    @Test
    void defaultsToAvailableCpusWithinBlockAndHeapBudgets() throws IOException, InterruptedException {
        int memoryWorkers = (int) Math.max(1, Runtime.getRuntime().maxMemory() / 4 / decoderBudgetBytes());
        int expectedWorkers = Math.min(3, Math.min(Runtime.getRuntime().availableProcessors(), memoryWorkers));
        TrackingProvider provider = createProvider("default", DAType.MMAP);
        provider.firstWorkersEntered = new CountDownLatch(expectedWorkers);
        provider.getEle(46.9999, 10);

        assertEquals(expectedWorkers, provider.maximumActive.get());
        assertEquals(expectedWorkers, provider.readers.size());
        assertArrayEquals(expectedSamples(), readSamples(provider));
        assertDecoderThreadsFinished(provider);
    }

    @Test
    void decoderFailureRemovesPartialCacheAndAllowsRetry() throws IOException, InterruptedException {
        assumeTwoWorkersFitInMemory();
        TrackingProvider provider = createProvider("failure", DAType.MMAP, 2);
        provider.firstWorkersEntered = new CountDownLatch(2);
        provider.failFirstBlock = true;

        RuntimeException failure = assertThrows(RuntimeException.class, () -> provider.getEle(46.9999, 10));
        assertTrue(hasCauseWithMessage(failure, "forced TIFF block failure"), failure.toString());
        assertEquals(0, provider.active.get());
        assertFalse(Files.exists(provider.getCacheDir().toPath().resolve(CACHE_NAME)));
        assertDecoderThreadsFinished(provider);

        provider.failFirstBlock = false;
        provider.getEle(46.9999, 10);
        assertArrayEquals(expectedSamples(), readSamples(provider));
        assertEquals(0, provider.active.get());
        assertDecoderThreadsFinished(provider);
        provider.release();
        assertDecoderThreadsFinished(provider);
    }

    @Test
    void interruptedCallerDoesNotPublishPartialCacheAndCanRetry() throws IOException, InterruptedException {
        TrackingProvider provider = createProvider("interrupted", DAType.MMAP, 2);
        RuntimeException failure;
        Thread.currentThread().interrupt();
        try {
            failure = assertThrows(RuntimeException.class, () -> provider.getEle(46.9999, 10));
        } finally {
            assertTrue(Thread.interrupted(), "The caller's interruption must be preserved");
        }
        assertTrue(hasCauseWithMessage(failure, "Elevation processing interrupted"), failure.toString());
        assertEquals(0, provider.active.get());
        assertFalse(Files.exists(provider.getCacheDir().toPath().resolve(CACHE_NAME)));
        assertDecoderThreadsFinished(provider);

        provider.getEle(46.9999, 10);
        assertArrayEquals(expectedSamples(), readSamples(provider));
        assertDecoderThreadsFinished(provider);
    }

    private TrackingProvider createProvider(String name, DAType type, int threads) throws IOException {
        TrackingProvider result = createProvider(name, type);
        result.setThreads(threads);
        return result;
    }

    private TrackingProvider createProvider(String name, DAType type) throws IOException {
        TrackingProvider result = new TrackingProvider(Files.createDirectory(directory.resolve(name)).toString());
        result.setBaseURL(sourceDirectory.toString());
        result.setDAType(type);
        result.setAutoRemoveTemporaryFiles(false);
        providers.add(result);
        return result;
    }

    private static void assumeTwoWorkersFitInMemory() {
        assumeTrue(Runtime.getRuntime().maxMemory() / 4 / decoderBudgetBytes() >= 2,
                "The provider's heap budget must permit two raster decoders");
    }

    private static long decoderBudgetBytes() {
        return 16L * 1024 * 1024 + 10L * SIZE * Math.min(BLOCK_SIZE, SIZE);
    }

    private static short[] readSamples(TrackingProvider provider) {
        DataAccess heights = provider.getDirectory().getDAs().get(CACHE_NAME);
        assertEquals(SIZE, heights.getHeader(8));
        assertEquals(SIZE, heights.getHeader(12));
        short[] samples = new short[SIZE * SIZE];
        for (int i = 0; i < samples.length; i++)
            samples[i] = heights.getShort(2L * i);
        return samples;
    }

    private static short[] expectedSamples() {
        short[] samples = new short[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int sample = sampleAt(x, y);
                samples[y * SIZE + x] = sample > 12_000 ? Short.MIN_VALUE : (short) sample;
            }
        }
        return samples;
    }

    private static int sampleAt(int x, int y) {
        if ((x == 0 && y == 0) || (x == 512 && y == 512) || (x == 1024 && y == 1024))
            return 65_535;
        return (x * 7 + y * 11) % 10_001;
    }

    private static Set<Rectangle> expectedRegions() {
        Set<Rectangle> regions = new HashSet<>();
        for (int y = 0; y < SIZE; y += BLOCK_SIZE)
            regions.add(new Rectangle(0, y, SIZE, Math.min(BLOCK_SIZE, SIZE - y)));
        return regions;
    }

    private static void assertDecoderThreadsFinished(TrackingProvider provider) throws InterruptedException {
        for (Thread worker : provider.workers) {
            if (worker == Thread.currentThread())
                continue;
            worker.join(10_000);
            assertFalse(worker.isAlive(), "Decoder thread is still alive: " + worker.getName());
        }
    }

    private static boolean hasCauseWithMessage(Throwable exception, String message) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (message.equals(cause.getMessage()))
                return true;
        }
        return false;
    }

    private static void writeTiledTiff(Path destination) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster raster = image.getRaster();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++)
                raster.setSample(x, y, 0, sampleAt(x, y));
        }

        ImageWriter writer = null;
        Iterator<ImageWriter> candidates = ImageIO.getImageWritersByFormatName("TIFF");
        while (candidates.hasNext()) {
            ImageWriter candidate = candidates.next();
            if (candidate.getDefaultWriteParam().canWriteTiles()) {
                writer = candidate;
                break;
            }
            candidate.dispose();
        }
        if (writer == null)
            throw new IllegalStateException("No tiled TIFF writer is available");

        // TwelveMonkeys decodes the fixture; its writer does not support tiling, so use the JDK writer.
        try (ImageOutputStream output = ImageIO.createImageOutputStream(destination.toFile())) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setTiling(BLOCK_SIZE, BLOCK_SIZE, 0, 0);
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionType("Deflate");
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
    }

    private static class TrackingProvider extends BikemapElevationProvider {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();
        private final Set<ImageReader> readers = ConcurrentHashMap.newKeySet();
        private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
        private final Set<ImageReader> currentlyUsedReaders = ConcurrentHashMap.newKeySet();
        private final List<Rectangle> regions = Collections.synchronizedList(new ArrayList<>());
        private CountDownLatch firstWorkersEntered;
        private volatile boolean failFirstBlock;

        TrackingProvider(String cacheDir) {
            super(cacheDir);
        }

        @Override
        Raster readRaster(ImageReader reader, Rectangle region) throws IOException {
            int activeDecoders = active.incrementAndGet();
            maximumActive.accumulateAndGet(activeDecoders, Math::max);
            readers.add(reader);
            workers.add(Thread.currentThread());
            regions.add(new Rectangle(region));
            boolean exclusiveReader = currentlyUsedReaders.add(reader);

            try {
                if (!exclusiveReader)
                    throw new IOException("An ImageReader is shared between concurrent decoders");
                if (firstWorkersEntered != null) {
                    firstWorkersEntered.countDown();
                    if (!firstWorkersEntered.await(10, TimeUnit.SECONDS))
                        throw new IOException("A second TIFF decoder never entered");
                }
                if (failFirstBlock && region.x == 0 && region.y == 0)
                    throw new IOException("forced TIFF block failure");
                return super.readRaster(reader, region);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for TIFF decoders", exception);
            } finally {
                if (exclusiveReader)
                    currentlyUsedReaders.remove(reader);
                active.decrementAndGet();
            }
        }
    }
}
