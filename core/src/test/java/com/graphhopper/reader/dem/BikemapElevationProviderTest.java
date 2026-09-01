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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BikemapElevationProviderTest {
    private static final String FLOAT32_COG =
            "SUkqAMAAAABHREFMX1NUUlVDVFVSQUxfTUVUQURBVEFfU0laRT0wMDAxNDAgYnl0ZXMKTEFZT1VUPUlGRFNfQkVGT1JFX0RBVEEK" +
            "QkxPQ0tfT1JERVI9Uk9XX01BSk9SCkJMT0NLX0xFQURFUj1TSVpFX0FTX1VJTlQ0CkJMT0NLX1RSQUlMRVI9TEFTVF80X0JZVEVT" +
            "X1JFUEVBVEVECktOT1dOX0lOQ09NUEFUSUJMRV9FRElUSU9OPU5PCiAAEAAAAQMAAQAAAAMAAAABAQMAAQAAAAMAAAACAQMAAQAA" +
            "ACAAAAADAQMAAQAAAAgAAAAGAQMAAQAAAAEAAAAVAQMAAQAAAAEAAAAcAQMAAQAAAAEAAAA9AQMAAQAAAAMAAABCAQMAAQAAAAAC" +
            "AABDAQMAAQAAAAACAABEAQQAAQAAANoBAABFAQQAAQAAAGAEAABTAQMAAQAAAAMAAAAOgwwAAwAAAI4BAACChAwABgAAAKYBAACB" +
            "pAIABwAAAIYBAAAAAAAALTMyNzY4AAAAAAAAAADgPwAAAAAAAOA/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAkQAAAAAAAwEdAAAAAAAAAAABgBAAAeJzs1UERQFAYBsBPCTNSuDrjLIgOZhz0UIT7yyKFIP9uiZ27vKGsdm57AChnuY4nlNWm" +
            "/gsA5ayJ/wsbh+EOAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD97" +
            "cCAAAAAAAOT/2giqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qirswYEAAAAAAJD/ayOoqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
            "qqqqqqq0B4cEAAAAAIL+v3aFDQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAGYBXVAJAV1QCQE=";

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
}
