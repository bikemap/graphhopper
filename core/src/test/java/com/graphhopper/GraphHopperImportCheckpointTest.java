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
package com.graphhopper;

import com.graphhopper.config.CHProfile;
import com.graphhopper.routing.TestProfiles;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.AllEdgesIterator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphHopperImportCheckpointTest {
    private static final String PROFILE = "car";
    private static final String OSM = "src/test/resources/com/graphhopper/reader/osm/test-osm.xml";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({"RAM_STORE, true, 200", "MMAP, false, 0"})
    void resumeSubnetworkPreparation(String storage, boolean importAndClose, int retryMinNetworkSize) throws IOException {
        Path source = Files.copy(Path.of(OSM), tempDir.resolve("input.osm"));
        GraphHopperConfig config = config(storage).putObject("datareader.file", source.toString())
                .putObject("graph.import_checkpoint", true);
        interruptImport(config);

        Files.delete(source);
        config.putObject("prepare.subnetworks.threads", 2).putObject("prepare.min_network_size", retryMinNetworkSize);
        try (TrackedGraphHopper resumed = new TrackedGraphHopper(config)) {
            if (importAndClose)
                resumed.importAndClose();
            else
                resumed.importOrLoad();
            assertEquals(0, resumed.imports);
            assertEquals(1, resumed.cleanups);
        }

        config.putObject("graph.import_checkpoint", false);
        try (TrackedGraphHopper loaded = new TrackedGraphHopper(config)) {
            loaded.importOrLoad();
            assertEquals(0, loaded.imports);
            assertEquals(0, loaded.cleanups);
            // All ways in this fixture belong to the same connected component.
            BooleanEncodedValue subnetwork = loaded.getEncodingManager().getBooleanEncodedValue(Subnetwork.key(PROFILE));
            AllEdgesIterator edges = loaded.getBaseGraph().getAllEdges();
            while (edges.next())
                assertFalse(edges.get(subnetwork), "Unexpected subnetwork flag on edge " + edges.getEdge());
            GHResponse response = loaded.route(new GHRequest(51.2492152, 9.4317166, 51.2, 9.4).setProfile(PROFILE));
            assertFalse(response.hasErrors(), () -> response.getErrors().toString());
            assertEquals(3, response.getBest().getPoints().size());
        }
    }

    @Test
    void incompatibleRetriesLeaveTheCheckpointAvailable() {
        GraphHopperConfig config = config("RAM_STORE").putObject("graph.import_checkpoint", true);
        interruptImport(config);
        assertRefusesCheckpoint(new GraphHopperConfig(config).putObject("graph.import_checkpoint", false),
                "graph.import_checkpoint");
        assertRefusesCheckpoint(new GraphHopperConfig(config).putObject("datareader.file", "different-extract.osm"),
                "OSM input differs");
        assertRefusesCheckpoint(new GraphHopperConfig(config).putObject("import.osm.ignored_highways", "steps"),
                "settings or profiles differ");
        try (TrackedGraphHopper retry = new TrackedGraphHopper(config)) {
            retry.importOrLoad();
            assertEquals(0, retry.imports);
            assertEquals(1, retry.cleanups);
            assertTrue(retry.getFullyLoaded());
        }
        try (TrackedGraphHopper complete = new TrackedGraphHopper(config)) {
            assertTrue(complete.load());
            assertEquals(0, complete.cleanups);
        }
    }

    @Test
    void interruptedPostProcessingRequiresFreshImport() {
        GraphHopperConfig config = config("RAM_STORE").putObject("graph.import_checkpoint", true);
        try (TrackedGraphHopper interrupted = new TrackedGraphHopper(config) {
            @Override
            protected void postProcessing(boolean closeEarly) {
                super.postProcessing(closeEarly);
                throw new InterruptedPreparation();
            }
        }) {
            assertThrows(InterruptedPreparation.class, interrupted::importAndClose);
        }
        assertRefusesCheckpoint(config, "outside subnetwork preparation");
    }

    private void assertRefusesCheckpoint(GraphHopperConfig config, String expectedMessage) {
        try (TrackedGraphHopper retry = new TrackedGraphHopper(config)) {
            assertFalse(retry.load());
            IllegalStateException error = assertThrows(IllegalStateException.class, retry::importOrLoad);
            assertTrue(error.getMessage().contains(expectedMessage), error::getMessage);
            assertEquals(0, retry.imports);
            assertEquals(0, retry.cleanups);
            assertFalse(retry.getFullyLoaded());
            assertThrows(IllegalStateException.class, () -> retry.route(new GHRequest().setProfile(PROFILE)));
        }
    }

    @Test
    void defaultConfigurationReimportsAfterInterruptedCleanup() {
        GraphHopperConfig config = config("RAM_STORE");
        interruptImport(config);
        try (TrackedGraphHopper retry = new TrackedGraphHopper(config)) {
            retry.importOrLoad();
            assertEquals(1, retry.imports);
            assertEquals(1, retry.cleanups);
        }
    }

    private void interruptImport(GraphHopperConfig config) {
        try (TrackedGraphHopper interrupted = new TrackedGraphHopper(config)) {
            interrupted.interruptCleanup = true;
            assertThrows(InterruptedPreparation.class, interrupted::importAndClose);
            assertEquals(1, interrupted.imports);
        }
    }

    private GraphHopperConfig config(String storage) {
        return new GraphHopperConfig()
                .putObject("graph.location", tempDir.resolve("graph").toString())
                .putObject("graph.dataaccess.default_type", storage)
                .putObject("datareader.file", OSM)
                .putObject("import.osm.ignored_highways", "")
                .setProfiles(List.of(TestProfiles.constantSpeed(PROFILE)))
                .setCHProfiles(List.of(new CHProfile(PROFILE)));
    }

    private static class InterruptedPreparation extends RuntimeException {
    }

    private static class TrackedGraphHopper extends GraphHopper implements AutoCloseable {
        private int imports;
        private int cleanups;
        private boolean interruptCleanup;

        TrackedGraphHopper(GraphHopperConfig config) {
            init(config);
        }

        @Override
        protected void importOSM() {
            imports++;
            super.importOSM();
        }

        @Override
        protected void cleanUp() {
            cleanups++;
            if (interruptCleanup) {
                getBaseGraph().getEdgeIteratorStateForKey(0)
                        .set(getEncodingManager().getBooleanEncodedValue(Subnetwork.key(PROFILE)), true);
                throw new InterruptedPreparation();
            }
            super.cleanUp();
        }
    }
}
