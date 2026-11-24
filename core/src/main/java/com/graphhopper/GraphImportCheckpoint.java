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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.graphhopper.storage.StorableProperties;
import com.graphhopper.util.Parameters;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The marker guards the graph directory until the final flush. Only its "ready" name allows recovery:
 * the base graph has been flushed, and subnetwork flags are the only fields that may have changed since.
 * Renames take place under the graph write lock and stay on the same filesystem.
 */
final class GraphImportCheckpoint {
    private static final String SOURCE_PATH = "import.checkpoint.source_path";
    private static final String SOURCE = "import.checkpoint.source";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final Path incomplete;
    private final Path ready;

    GraphImportCheckpoint(String graphLocation) {
        Path directory = Path.of(graphLocation);
        incomplete = directory.resolve("import-in-progress");
        ready = directory.resolve("osm-import-checkpoint");
    }

    boolean exists() {
        return Files.exists(ready);
    }

    boolean isIncomplete() {
        return Files.exists(incomplete);
    }

    void begin() throws IOException {
        Files.createFile(incomplete);
    }

    void save(StorableProperties properties) throws IOException {
        MAPPER.writeValue(incomplete.toFile(), properties.getAll());
        Files.move(incomplete, ready, StandardCopyOption.ATOMIC_MOVE);
    }

    void restore(StorableProperties properties) throws IOException {
        properties.putAll(MAPPER.readValue(ready.toFile(), new TypeReference<Map<String, String>>() {}));
    }

    void finishSubnetworks() throws IOException {
        // Later preparations may create their own files and modify the graph. Only cleanup is replayable in place.
        Files.move(ready, incomplete, StandardCopyOption.ATOMIC_MOVE);
    }

    void complete() throws IOException {
        Files.delete(incomplete);
    }

    static String settings(GraphHopperConfig config) {
        Map<String, Object> settings = config.asPMap().toMap();
        settings.keySet().removeIf(key -> !affectsImport(key));
        // The first worker enables urban density calculation. Additional workers only change its speed.
        settings.put("graph.urban_density.threads", config.getInt("graph.urban_density.threads", 0) > 0);
        return digest(settings);
    }

    private static boolean affectsImport(String key) {
        return switch (key) {
            case "datareader.file", "datareader.worker_threads", "graph.elevation.clear", "graph.elevation.cgiar.clear",
                    "graph.elevation.dataaccess", "graph.elevation.threads" -> false;
            case "graph.sort", "graph.encoded_values", "country_rules.enabled", "max_speed_calculator.enabled",
                    "custom_areas.directory", Parameters.Routing.INIT_WAY_POINT_MAX_DISTANCE -> true;
            default -> key.startsWith("datareader.") || key.startsWith("import.osm.")
                    || key.startsWith("graph.urban_density.") || key.startsWith("graph.elevation.");
        };
    }

    static void recordSource(StorableProperties properties, File source) {
        if (source != null) {
            Path path = source.toPath().toAbsolutePath().normalize();
            properties.put(SOURCE_PATH, digest(path.toString()));
            properties.put(SOURCE, sourceIdentity(path));
        }
    }

    static void checkSource(StorableProperties properties, File source) {
        // Once the import has been saved the input file is optional. If supplied, reject a different extract.
        if (source == null) return;
        Path path = source.toPath().toAbsolutePath().normalize();
        if (!properties.get(SOURCE_PATH).equals(digest(path.toString()))
                || (Files.isReadable(path) && !properties.get(SOURCE).equals(sourceIdentity(path))))
            throw new IllegalStateException("OSM input differs from the import checkpoint: " + source);
    }

    private static String sourceIdentity(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return digest(List.of(attributes.size(), attributes.lastModifiedTime().toString()));
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot identify OSM input " + path, ex);
        }
    }

    private static String digest(Object value) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot compute import checkpoint identity", ex);
        }
    }
}
