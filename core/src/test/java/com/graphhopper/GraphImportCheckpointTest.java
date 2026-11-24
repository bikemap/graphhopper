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

import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.storage.StorableProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class GraphImportCheckpointTest {
    @Test
    void settingsIdentifyTheImportIndependentlyOfOrderAndWorkerCounts() {
        GraphHopperConfig config = new GraphHopperConfig().putObject("graph.elevation.provider", "hgt")
                .putObject("graph.urban_density.threads", 1).putObject("import.osm.ignored_highways", "steps");
        String expected = GraphImportCheckpoint.settings(config);
        GraphHopperConfig reordered = new GraphHopperConfig().putObject("import.osm.ignored_highways", "steps")
                .putObject("graph.urban_density.threads", 8).putObject("graph.elevation.provider", "hgt")
                .putObject("prepare.subnetworks.threads", 1).putObject("datareader.worker_threads", 2)
                .putObject("datareader.file", "old.pbf");
        assertEquals(expected, GraphImportCheckpoint.settings(reordered));
        assertNotEquals(expected, GraphImportCheckpoint.settings(reordered.putObject("graph.urban_density.threads", 0)));
        assertNotEquals(expected, GraphImportCheckpoint.settings(config.putObject("import.osm.ignored_highways", "")));
    }

    @Test
    void checkSourceMatchesPathSizeAndModificationTimeWhenAvailable(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("input.osm"), "abc");
        FileTime modified = Files.getLastModifiedTime(source);
        StorableProperties properties = new StorableProperties(new RAMDirectory());
        GraphImportCheckpoint.recordSource(properties, source.toFile());
        GraphImportCheckpoint.checkSource(properties, dir.resolve(".").resolve("input.osm").toFile());

        Path other = Files.copy(source, dir.resolve("other.osm"));
        Files.setLastModifiedTime(other, modified);
        assertThrows(IllegalStateException.class, () -> GraphImportCheckpoint.checkSource(properties, other.toFile()));
        Files.setLastModifiedTime(source, FileTime.fromMillis(modified.toMillis() + 2000));
        assertThrows(IllegalStateException.class, () -> GraphImportCheckpoint.checkSource(properties, source.toFile()));
        Files.writeString(source, "more bytes");
        Files.setLastModifiedTime(source, modified);
        assertThrows(IllegalStateException.class, () -> GraphImportCheckpoint.checkSource(properties, source.toFile()));

        Files.delete(source);
        GraphImportCheckpoint.checkSource(properties, source.toFile());
        GraphImportCheckpoint.checkSource(properties, null);
        assertThrows(IllegalStateException.class, () -> GraphImportCheckpoint.checkSource(properties, dir.resolve("missing.osm").toFile()));
    }
}
