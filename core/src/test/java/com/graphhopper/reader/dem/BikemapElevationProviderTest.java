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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BikemapElevationProviderTest {
    static final String FLOAT32_COG = """
            SUkqAMAAAABHREFMX1NUUlVDVFVSQUxfTUVUQURBVEFfU0laRT0wMDAxNDAgYnl0ZXMKTEFZT1VUPUlGRFNfQkVGT1JFX0RBVEEK
            QkxPQ0tfT1JERVI9Uk9XX01BSk9SCkJMT0NLX0xFQURFUj1TSVpFX0FTX1VJTlQ0CkJMT0NLX1RSQUlMRVI9TEFTVF80X0JZVEVT
            X1JFUEVBVEVECktOT1dOX0lOQ09NUEFUSUJMRV9FRElUSU9OPU5PCiAAEAAAAQMAAQAAAAMAAAABAQMAAQAAAAMAAAACAQMAAQAA
            ACAAAAADAQMAAQAAAAgAAAAGAQMAAQAAAAEAAAAVAQMAAQAAAAEAAAAcAQMAAQAAAAEAAAA9AQMAAQAAAAMAAABCAQMAAQAAAAAC
            AABDAQMAAQAAAAACAABEAQQAAQAAANoBAABFAQQAAQAAAGAEAABTAQMAAQAAAAMAAAAOgwwAAwAAAI4BAACChAwABgAAAKYBAACB
            pAIABwAAAIYBAAAAAAAALTMyNzY4AAAAAAAAAADgPwAAAAAAAOA/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAkQAAAAAAAwEdAAAAAAAAAAABgBAAAeJzs1UERQFAYBsBPCTNSuDrjLIgOZhz0UIT7yyKFIP9uiZ27vKGsdm57AChnuY4nlNWm
            /gsA5ayJ/wsbh+EOAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD97
            cCAAAAAAAOT/2giqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qirswYEAAAAAAJD/ayOoqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
            qqqqqqq0B4cEAAAAAIL+v3aFDQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAGYBXVAJAV1QCQE=
            """.replaceAll("\\s", "");

    @TempDir
    Path directory;

    private BikemapElevationProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        Files.write(directory.resolve("n46e010.tif"), Base64.getDecoder().decode(FLOAT32_COG));
        provider = new BikemapElevationProvider(directory.toString());
    }

    @AfterEach
    void tearDown() {
        provider.release();
    }

    @Test
    void buildsContinuousDatasetFileNames() {
        assertEquals("n46e010.tif", provider.getFileName(46.5, 10.5));
        assertEquals("s01w001.tif", provider.getFileName(-0.1, -0.1));
        assertEquals("n83e179.tif", provider.getFileName(84, 180));
        assertEquals("s60w180.tif", provider.getFileName(-60, -180));
        assertNull(provider.getFileName(84.00001, 10));
        assertNull(provider.getFileName(0, 180.00001));
    }

    @Test
    void readsFloat32CogAndHandlesNoDataAndMissingTiles() {
        assertEquals(100, provider.getEle(46.9999, 10));
        assertTrue(Double.isNaN(provider.getEle(46.5, 10.5)));
        assertEquals(900, provider.getEle(46, 10.9999));
        assertEquals(0, provider.getEle(48, 10));
        assertEquals(0, provider.getEle(85, 10));
    }

    @Test
    void reusesPersistentGraphHopperSidecarCache() throws IOException {
        provider.setAutoRemoveTemporaryFiles(false);
        assertEquals(100, provider.getEle(46.9999, 10));

        Path sidecar = directory.resolve("bikemap_n46e010.gh");
        assertTrue(Files.exists(sidecar));
        provider.release();

        provider = new BikemapElevationProvider(directory.toString());
        assertEquals(100, provider.getEle(46.9999, 10));
        assertTrue(Files.size(sidecar) > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MMAP", "RAM_STORE"})
    void reusesCacheAfterOriginalTiffIsDeleted(String storageType) throws IOException {
        DAType type = DAType.fromString(storageType);
        provider.setDAType(type);
        provider.setAutoRemoveTemporaryFiles(false);
        assertEquals(100, provider.getEle(46.9999, 10));
        provider.release();
        Files.delete(directory.resolve("n46e010.tif"));

        provider = new BikemapElevationProvider(directory.toString());
        provider.setDAType(type);
        assertEquals(100, provider.getEle(46.9999, 10));
        assertTrue(Double.isNaN(provider.getEle(46.5, 10.5)));
        assertEquals(900, provider.getEle(46, 10.9999));
    }

    @Test
    void reusesLegacyCacheWithoutSourceTiff() throws IOException {
        provider.setAutoRemoveTemporaryFiles(false);
        assertEquals(100, provider.getEle(46.9999, 10));
        DataAccess heights = provider.getDirectory().getDAs().get("bikemap_n46e010.gh");
        heights.setHeader(4, 1);
        for (int header = 32; header < 64; header += 4)
            heights.setHeader(header, 0);
        heights.flush();
        provider.release();
        Files.delete(directory.resolve("n46e010.tif"));

        provider = new BikemapElevationProvider(directory.toString());
        assertEquals(100, provider.getEle(46.9999, 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"modified", "length"})
    void rebuildsCacheWhenLocalSourceMetadataChanges(String changedMetadata) throws IOException {
        provider.setAutoRemoveTemporaryFiles(false);
        assertEquals(100, provider.getEle(46.9999, 10));
        DataAccess heights = provider.getDirectory().getDAs().get("bikemap_n46e010.gh");
        // Give the cached value a recognizable value to distinguish reuse from conversion.
        heights.setShort(0, (short) 123);
        heights.flush();
        provider.release();

        Path source = directory.resolve("n46e010.tif");
        if (changedMetadata.equals("modified")) {
            Files.setLastModifiedTime(source, FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 5_000));
        } else {
            Files.write(source, new byte[]{0}, StandardOpenOption.APPEND);
        }

        provider = new BikemapElevationProvider(directory.toString());
        assertEquals(100, provider.getEle(46.9999, 10));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8, 12})
    void rebuildsCacheWithInvalidVersionOrDimensions(int invalidHeader) {
        provider.setAutoRemoveTemporaryFiles(false);
        assertEquals(100, provider.getEle(46.9999, 10));
        DataAccess heights = provider.getDirectory().getDAs().get("bikemap_n46e010.gh");
        heights.setHeader(invalidHeader, 0);
        heights.setShort(0, (short) 123);
        heights.flush();
        provider.release();

        provider = new BikemapElevationProvider(directory.toString());
        assertEquals(100, provider.getEle(46.9999, 10));
    }

    @Test
    void rebuildsTruncatedSidecarCache() throws IOException {
        Files.write(directory.resolve("bikemap_n46e010.gh"), new byte[]{0, 2, 'G', 'H'});
        assertEquals(100, provider.getEle(46.9999, 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MMAP", "RAM_STORE"})
    void rebuildsCacheWhoseHeaderIsValidButBackingFileIsTruncated(String storageType) throws IOException {
        DAType type = DAType.fromString(storageType);
        provider.setDAType(type);
        provider.setAutoRemoveTemporaryFiles(false);
        assertEquals(100, provider.getEle(46.9999, 10));
        DataAccess heights = provider.getDirectory().getDAs().get("bikemap_n46e010.gh");
        heights.setShort(0, (short) 123);
        heights.flush();
        provider.release();

        try (RandomAccessFile cache = new RandomAccessFile(directory.resolve("bikemap_n46e010.gh").toFile(), "rw")) {
            // Preserve the header and actual raster values, but remove part of the allocated storage.
            cache.setLength(cache.length() - 10);
        }

        provider = new BikemapElevationProvider(directory.toString());
        provider.setDAType(type);
        assertEquals(100, provider.getEle(46.9999, 10));
    }

    @Test
    void localSourceDirectoryCanBeSeparateFromConvertedCacheDirectory() throws IOException {
        Path sourceDirectory = Files.createDirectory(directory.resolve("continuous"));
        Path source = sourceDirectory.resolve("n46e010.tif");
        Files.move(directory.resolve("n46e010.tif"), source);
        provider.setBaseURL(sourceDirectory.toString());
        provider.setAutoRemoveTemporaryFiles(false);

        assertEquals(100, provider.getEle(46.9999, 10));
        assertTrue(Files.exists(directory.resolve("bikemap_n46e010.gh")));
        assertFalse(Files.exists(sourceDirectory.resolve("bikemap_n46e010.gh")));
        provider.release();
        Files.delete(source);

        provider = new BikemapElevationProvider(directory.toString());
        provider.setBaseURL(sourceDirectory.toString());
        assertEquals(100, provider.getEle(46.9999, 10));
    }

    @Test
    void truncatedCacheWithoutSourceTiffIsTreatedAsMissing() throws IOException {
        provider.setAutoRemoveTemporaryFiles(false);
        assertEquals(100, provider.getEle(46.9999, 10));
        provider.release();
        Files.delete(directory.resolve("n46e010.tif"));

        Path sidecar = directory.resolve("bikemap_n46e010.gh");
        try (RandomAccessFile cache = new RandomAccessFile(sidecar.toFile(), "rw")) {
            cache.setLength(100);
        }

        provider = new BikemapElevationProvider(directory.toString());
        assertEquals(0, provider.getEle(46.9999, 10));
        assertFalse(Files.exists(sidecar));
    }

    @Test
    void invalidLocalTiffDoesNotPoisonLaterRetry() throws IOException {
        Path source = directory.resolve("n46e010.tif");
        Files.write(source, new byte[]{1, 2, 3});
        assertThrows(RuntimeException.class, () -> provider.getEle(46.9999, 10));
        assertFalse(Files.exists(directory.resolve("bikemap_n46e010.gh")));

        Files.write(source, Base64.getDecoder().decode(FLOAT32_COG));
        assertEquals(100, provider.getEle(46.9999, 10));
    }
}
