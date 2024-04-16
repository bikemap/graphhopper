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

import com.graphhopper.util.Helper;

import java.util.HashMap;
import java.util.Map;


public enum BMWayType {
    // Order is important to make ordinal roughly comparable
    CYCLEWAY, LIVING_STREET, QUIET_ROAD, PATH, TRACK, ACCESS_ROAD, PEDESTRIAN_AREA, ROAD, STEPS,
    BUSY_ROAD, MISSING;

    public static final String KEY = "bm_way_type";

    private static final Map<String, BMWayType> WAYTYPE_MAP = new HashMap<>();

    static {
        for (BMWayType way_type : values()) {
            if (way_type == MISSING)
                continue;
            WAYTYPE_MAP.put(way_type.toString(), way_type);
        }

        // Busy Road Additions
        WAYTYPE_MAP.put("primary", BUSY_ROAD);
        WAYTYPE_MAP.put("primary_link", BUSY_ROAD);
        WAYTYPE_MAP.put("secondary", BUSY_ROAD);
        WAYTYPE_MAP.put("secondary_link", BUSY_ROAD);

        // Road Additions
        WAYTYPE_MAP.put("tertiary", ROAD);
        WAYTYPE_MAP.put("tertiary_link", ROAD);

        // Quiet Road Additions
        WAYTYPE_MAP.put("residential", QUIET_ROAD);
        WAYTYPE_MAP.put("unclassified", QUIET_ROAD);

        // Access Road Additions
        WAYTYPE_MAP.put("service", ACCESS_ROAD);

        // Pedestrian Area Additions
        WAYTYPE_MAP.put("pedestrian", PEDESTRIAN_AREA);
        WAYTYPE_MAP.put("footway", PEDESTRIAN_AREA);

    }

    public static EnumEncodedValue<BMWayType> create() {
        return new EnumEncodedValue<>(KEY, BMWayType.class);
    }

    @Override
    public String toString() {
        return Helper.toLowerCase(super.toString());
    }

    public static BMWayType find(String name) {
        if (Helper.isEmpty(name))
            return MISSING;

        int colonIndex = name.indexOf(":");
        if (colonIndex != -1) {
            name = name.substring(0, colonIndex);
        }

        return WAYTYPE_MAP.getOrDefault(name, MISSING);
    }

    public boolean isPleasant() {
        return this.ordinal() <= ACCESS_ROAD.ordinal();
    }
}
