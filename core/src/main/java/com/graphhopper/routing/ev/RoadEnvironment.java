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
    OTHER("other"), ROAD("road"), FERRY("ferry"),
    TUNNEL("tunnel"), BRIDGE("bridge"), FORD("ford"),
    CYCLE_ONEWAY_YES("cycle_oneway_yes"), CYCLE_ONEWAY_NO("cycle_oneway_no"),
    CYCLE_LEFT("cycle_left"), CYCLE_RIGHT_ONEWAY("cycle_right_oneway"),
    BICYCLE_ONEWAY_YES("bicycle_oneway_yes"), BICYCLE_ONEWAY_NO("bicycle_oneway_no"),
    CYCLE_LEFT_ONEWAY("cycle_left_oneway"), CYCLE_BOTH("cycle_both"), CYCLE_WIDTH("cycle_width"),
    CYCLE_RIGHT_BICYCLE("cycle_right_bicycle"), CYCLE_BOTH_LANE("cycle_both_lane"),
    CYCLE_RIGHT_SEGREGATED("cycle_right_segregated"), CYCLE_RIGHT("cycle_right"),
    CYCLE_LEFT_LANE("cycle_left_lane"), CYCLE_SURFACE("cycle_surface"),
    BICYCLE_DESIGNATED("bicycle_designated"), CYCLE_LANE_EXCLUSIVE("cycle_lane_exclusive"),
    CYCLE_LANE_ADVISORY("cycle_lane_advisory"), CYCLE_TRACK("cycle_track"),
    CYCLE_OPPOSITE_TRACK("cycle_opposite_track"), CYCLE_SHARED_LANE("cycle_shared_lane"),
    CYCLE_SHARE_BUSWAY("cycle_share_busway"), BICYCLE_YES("bicycle_yes"), BICYCLE_NO("bicycle_no"),
    BICYCLE_USE_SIDEPATH("bicycle_use_sidepath"), BICYCLE_DISMOUNT("bicycle_dismount"),
    SAC_SCALE_HIKING("sac_scale_hiking"), SAC_SCALE_DEMANDING_HIKING("sac_scale_demanding_hiking"),
    SAC_SCALE_DIFFICULT_HIKING("sac_scale_difficult_hiking"), EMBEDDED_RAILS("embedded_rails"),
    MTB_SCALE("mtb_scale"), BICYCLE_ROAD("bicycle_road"), CONSTRUCTION("construction"),
    CYCLE_LEFT_TRACK("cycle_left_track"), CYCLE_RIGHT_TRACK("cycle_right_track"),
    CYCLE_LEFT_BICYCLE("cycle_left_bicycle"), CYCLE_RIGHT_LANE("cycle_right_lane");

    public static final String KEY = "road_environment";

    private final String name;

    RoadEnvironment(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
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
