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
package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.storage.IntsRef;

import java.util.List;

import static com.graphhopper.routing.ev.RoadEnvironment.*;

public class OSMRoadEnvironmentParser implements TagParser {

    private final EnumEncodedValue<RoadEnvironment> roadEnvEnc;

    public OSMRoadEnvironmentParser() {
        this.roadEnvEnc = new EnumEncodedValue<>(RoadEnvironment.KEY, RoadEnvironment.class);
    }

    @Override
    public void createEncodedValues(EncodedValueLookup lookup, List<EncodedValue> list) {
        list.add(roadEnvEnc);
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay readerWay, boolean ferry, IntsRef relationFlags) {
        RoadEnvironment roadEnvironment = OTHER;
        if (ferry)
            roadEnvironment = FERRY;
        else if (readerWay.hasTag("bridge") && !readerWay.hasTag("bridge", "no"))
            roadEnvironment = BRIDGE;
        else if (readerWay.hasTag("tunnel") && !readerWay.hasTag("tunnel", "no"))
            roadEnvironment = TUNNEL;
        else if (readerWay.hasTag("ford") || readerWay.hasTag("highway", "ford"))
            roadEnvironment = FORD;
        else if (readerWay.hasTag("route", "shuttle_train"))
            // TODO how to feed this information from a relation like https://www.openstreetmap.org/relation/1932780
            roadEnvironment = SHUTTLE_TRAIN;
        else if (readerWay.hasTag("cycleway", "lane") || readerWay.hasTag("cycleway:both:lane") || readerWay.hasTag("cycleway:both")) && !readerWay.hasTag("cycleway:both", "no"))
            roadEnvironment = CYCLE_BOTH_LANE;
        else if (readerWay.hasTag("cycleway:left:lane") || readerWay.hasTag("cycleway:left", "lane"))
            roadEnvironment = CYCLE_LEFT_LANE;
        else if (readerWay.hasTag("cycleway:right:lane") || readerWay.hasTag("cycleway:right", "lane"))
            roadEnvironment = CYCLE_RIGHT_LANE;
        else if (readerWay.hasTag("cycleway:left"))
            roadEnvironment = CYCLE_LEFT;
        else if (readerWay.hasTag("cycleway:right"))
            roadEnvironment = CYCLE_RIGHT;
        else if (readerWay.hasTag("cycleway:right:oneway"))
            roadEnvironment = CYCLE_RIGHT_ONEWAY;
        else if (readerWay.hasTag("cycleway:left:oneway"))
            roadEnvironment = CYCLE_LEFT_ONEWAY;
        else if (readerWay.hasTag("cycleway:both:oneway", "yes") || readerWay.hasTag("cycleway:oneway", "yes"))
            roadEnvironment = CYCLE_ONEWAY_YES;
        else if (readerWay.hasTag("cycleway:both:oneway", "no") || readerWay.hasTag("cycleway:oneway", "no"))
            roadEnvironment = CYCLE_ONEWAY_NO;
        else if (readerWay.hasTag("cycleway:width"))
            roadEnvironment = CYCLE_WIDTH;
        else if (readerWay.hasTag("cycleway:right:bicycle"))
            roadEnvironment = CYCLE_RIGHT_BICYCLE;
        else if (readerWay.hasTag("cycleway:left:bicycle"))
            roadEnvironment = CYCLE_LEFT_BICYCLE;
        else if (readerWay.hasTag("oneway:bicycle", "yes"))
            roadEnvironment = BICYCLE_ONEWAY_YES;
        else if (readerWay.hasTag("oneway:bicycle", "no"))
            roadEnvironment = BICYCLE_ONEWAY_NO;
        else if (readerWay.hasTag("cycleway:both:bicycle", "designated") || readerWay.hasTag("cycleway:bicycle", "designated") || readerWay.hasTag("bicycle", "designated"))
            roadEnvironment = BICYCLE_DESIGNATED;
        else if (readerWay.hasTag("cycleway:right:segregated"))
            roadEnvironment = CYCLE_RIGHT_SEGREGATED;
        else if (readerWay.hasTag("cycleway:surface") || readerWay.hasTag("cycleway:surface", "paving_stones"))
            roadEnvironment = CYCLE_SURFACE;
        else if (readerWay.hasTag("cycleway:lane", "exclusive"))
            roadEnvironment = CYCLE_LANE_EXCLUSIVE;
        else if (readerWay.hasTag("cycleway:lane", "advisory"))
            roadEnvironment = CYCLE_LANE_ADVISORY;
        else if (readerWay.hasTag("cycleway", "track"))
            roadEnvironment = CYCLE_TRACK;
        else if (readerWay.hasTag("cycleway:left", "track"))
            roadEnvironment = CYCLE_LEFT_TRACK;
        else if (readerWay.hasTag("cycleway:right", "track"))
            roadEnvironment = CYCLE_RIGHT_TRACK;
        else if (readerWay.hasTag("cycleway", "opposite_track"))
            roadEnvironment = CYCLE_OPPOSITE_TRACK;
        else if (readerWay.hasTag("cycleway", "shared_lane"))
            roadEnvironment = CYCLE_SHARED_LANE;
        else if (readerWay.hasTag("cycleway", "share_busway"))
            roadEnvironment = CYCLE_SHARE_BUSWAY;
        else if (readerWay.hasTag("cycleway:smoothness", "good"))
            roadEnvironment = CYCLE_SHARE_BUSWAY;
        else if (readerWay.hasTag("bicycle", "yes"))
            roadEnvironment = BICYCLE_YES;
        else if (readerWay.hasTag("bicycle", "no"))
            roadEnvironment = BICYCLE_NO;
        else if (readerWay.hasTag("bicycle", "use_sidepath"))
            roadEnvironment = BICYCLE_USE_SIDEPATH;
        else if (readerWay.hasTag("bicycle", "dismount"))
            roadEnvironment = BICYCLE_DISMOUNT;
        else if (readerWay.hasTag("sac_scale", "hiking") || readerWay.hasTag("sac_scale", "mountain_hiking") || readerWay.hasTag("sac_scale", "alpine_hiking"))
            roadEnvironment = SAC_SCALE_HIKING;
        else if (readerWay.hasTag("sac_scale", "demanding_mountain_hiking") || readerWay.hasTag("sac_scale", "demanding_alpine_hiking"))
            roadEnvironment = SAC_SCALE_DEMANDING_HIKING;
        else if (readerWay.hasTag("sac_scale", "difficult_alpine_hiking"))
            roadEnvironment = SAC_SCALE_DIFFICULT_HIKING;
        else if (readerWay.hasTag("embedded_rails"))
            roadEnvironment = EMBEDDED_RAILS;
        else if (readerWay.hasTag("mtb:scale"))
            roadEnvironment = MTB_SCALE;
        else if (readerWay.hasTag("bicycle_road"))
            roadEnvironment = BICYCLE_ROAD;
        else if (readerWay.hasTag("highway", "construction") || readerWay.hasTag("construction", "cycleway") || readerWay.hasTag("construction", "yes"))
            roadEnvironment = CONSTRUCTION;
        else if (readerWay.hasTag("highway"))
            roadEnvironment = ROAD;

        if (roadEnvironment != OTHER)
            roadEnvEnc.setEnum(false, edgeFlags, roadEnvironment);
        return edgeFlags;
    }
}
