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

/**
 * This enum the road environment of an edge. Currently road, ferry, tunnel, ford, bridge. All edges
 * that do not fit get "other" as value.
 */
public enum RoadEnvironment {
    OTHER, ROAD, FERRY, TUNNEL, BRIDGE, FORD,
    CYCLE_ONEWAY_YES, CYCLE_ONEWAY_NO,
    CYCLE_LEFT, CYCLE_RIGHT_ONEWAY,
    BICYCLE_ONEWAY_YES, BICYCLE_ONEWAY_NO,
    CYCLE_LEFT_ONEWAY, CYCLE_BOTH, CYCLE_WIDTH,
    CYCLE_RIGHT_BICYCLE, CYCLE_BOTH_LANE,
    CYCLE_RIGHT_SEGREGATED, CYCLE_RIGHT,
    CYCLE_LEFT_LANE, CYCLE_SURFACE,
    BICYCLE_DESIGNATED, CYCLE_LANE_EXCLUSIVE,
    CYCLE_LANE_ADVISORY, CYCLE_TRACK,
    CYCLE_OPPOSITE_TRACK, CYCLE_SHARED_LANE,
    CYCLE_SHARE_BUSWAY, BICYCLE_YES, BICYCLE_NO,
    BICYCLE_USE_SIDEPATH, BICYCLE_DISMOUNT,
    SAC_SCALE_HIKING, SAC_SCALE_DEMANDING_HIKING,
    SAC_SCALE_DIFFICULT_HIKING, EMBEDDED_RAILS,
    MTB_SCALE, BICYCLE_ROAD, CONSTRUCTION,
    CYCLE_LEFT_TRACK, CYCLE_RIGHT_TRACK,
    CYCLE_LEFT_BICYCLE, CYCLE_RIGHT_LANE;

    public static final String KEY = "road_environment";

    public static EnumEncodedValue<RoadEnvironment> create() {
        return new EnumEncodedValue<>(RoadEnvironment.KEY, RoadEnvironment.class);
    }

    @Override
    public String toString() {
        return Helper.toLowerCase(super.toString());
    }

    public static RoadEnvironment find(String name) {
        if (name == null)
            return OTHER;
        try {
            return RoadEnvironment.valueOf(Helper.toUpperCase(name));
        } catch (IllegalArgumentException ex) {
            return OTHER;
        }
    }
}
