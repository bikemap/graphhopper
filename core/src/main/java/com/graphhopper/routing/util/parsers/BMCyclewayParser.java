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
import com.graphhopper.routing.ev.BmCycleway;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.storage.IntsRef;

/**
 * Parses the cycleway type from cycleway, cycleway:left, cycleway:right and cycleway:both OSM tags.
 * Stores values per direction: cycleway:right maps to forward, cycleway:left maps to reverse,
 * cycleway:both and cycleway (without direction qualifier) map to both directions.
 * The deprecated opposite_lane and opposite_track values are stored as LANE/TRACK in the reverse direction.
 *
 * @see <a href="https://wiki.openstreetmap.org/wiki/Key:cycleway">Key:cycleway</a>
 *
 *
 * Backported from https://github.com/graphhopper/graphhopper/blob/master/core/src/main/java/com/graphhopper/routing/util/parsers/OSMCyclewayParser.java
 */
public class BMCyclewayParser implements TagParser {

    private final EnumEncodedValue<BmCycleway> cyclewayEnc;

    public BMCyclewayParser(EnumEncodedValue<BmCycleway> cyclewayEnc) {
        this.cyclewayEnc = cyclewayEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay readerWay, IntsRef relationFlags) {
        BmCycleway bestFwd = BmCycleway.MISSING;
        BmCycleway bestRev = BmCycleway.MISSING;

        BmCycleway both = BmCycleway.find(readerWay.getTag("cycleway:both"));
        if (both != BmCycleway.MISSING) {
            bestFwd = both;
            bestRev = both;
        }

        BmCycleway right = BmCycleway.find(readerWay.getTag("cycleway:right"));
        if (right != BmCycleway.MISSING)
            bestFwd = better(bestFwd, right);

        BmCycleway left = BmCycleway.find(readerWay.getTag("cycleway:left"));
        if (left != BmCycleway.MISSING)
            bestRev = better(bestRev, left);

        // cycleway (generic) used for both directions, unless opposite_* which maps to reverse only
        String generic = readerWay.getTag("cycleway");
        if (generic != null) {
            if (generic.startsWith("opposite_")) {
                BmCycleway c = BmCycleway.find(generic.substring("opposite_".length()));
                if (c != BmCycleway.MISSING)
                    bestRev = better(bestRev, c);
            } else {
                BmCycleway c = BmCycleway.find(generic);
                if (c != BmCycleway.MISSING) {
                    bestFwd = better(bestFwd, c);
                    bestRev = better(bestRev, c);
                }
            }
        }

        cyclewayEnc.setEnum(false, edgeId, edgeIntAccess, bestFwd);
        cyclewayEnc.setEnum(true, edgeId, edgeIntAccess, bestRev);
    }

    private static BmCycleway better(BmCycleway current, BmCycleway candidate) {
        if (current == BmCycleway.MISSING || candidate.ordinal() < current.ordinal())
            return candidate;
        return current;
    }
}
