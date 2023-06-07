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

package com.graphhopper.routing.ev;

public class BMWeight {
    private static final String environmentTags = System.getenv("REPLICATED_OSM_TAGS");

    public static List<String> replicatedTags() {

        if (environmentTags == null) {
            return Arrays.asList(
                    "bm_weight",
                    "bm_weight_tracked",
                    "bm_weight_a_to_b_tracked",
                    "bm_weight_mountain_bike",
                    "bm_weight_road_bike",
                    "bm_weight_road_bike_tracked",
                    "bm_weight_mountain_bike_tracked",
                    "bm_weight_a_to_b"
            );
        };

        return Arrays.asList(environmentTags.replaceAll("-", "_").split(","));
    }

    public static IntEncodedValue create(String key) {
        return new IntEncodedValueImpl(key, 31, false);
    }
}
