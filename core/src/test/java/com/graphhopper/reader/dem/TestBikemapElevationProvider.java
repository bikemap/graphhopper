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
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.GHDirectory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Records storage allocations so tests can inspect caches without exposing directory internals. */
class TestBikemapElevationProvider extends BikemapElevationProvider {
    private final Map<String, DataAccess> createdDataAccesses = new HashMap<>();

    TestBikemapElevationProvider(String cacheDir) {
        super(cacheDir);
    }

    @Override
    protected Directory getDirectory() {
        if (dir == null) {
            createdDataAccesses.clear();
            dir = new GHDirectory(getCacheDir().getAbsolutePath(), daType) {
                @Override
                public DataAccess create(String name, DAType type, int segmentSize) {
                    DataAccess dataAccess = super.create(name, type, segmentSize);
                    createdDataAccesses.put(name, dataAccess);
                    return dataAccess;
                }
            };
        }
        return dir;
    }

    DataAccess getCreatedDataAccess(String name) {
        DataAccess dataAccess = createdDataAccesses.get(name);
        assertNotNull(dataAccess, "No DataAccess created for " + name);
        return dataAccess;
    }
}
