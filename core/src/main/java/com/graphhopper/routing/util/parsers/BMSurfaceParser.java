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
import com.graphhopper.routing.ev.BmSurface;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.storage.IntsRef;

import java.util.HashMap;
import java.util.Map;


public class BMSurfaceParser implements TagParser {

    private static final Map<String, BmSurface> GRADE_MAP = new HashMap<>();

    static {
        GRADE_MAP.put("grade1", BmSurface.PAVED);
        GRADE_MAP.put("grade2", BmSurface.GRAVEL);
        GRADE_MAP.put("grade3", BmSurface.UNPAVED);
        GRADE_MAP.put("grade4", BmSurface.GROUND);
        GRADE_MAP.put("grade5", BmSurface.GROUND);
    }

    private final EnumEncodedValue<BmSurface> surfaceEnc;

    public BMSurfaceParser(EnumEncodedValue<BmSurface> surfaceEnc) {
        this.surfaceEnc = surfaceEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay readerWay, IntsRef relationFlags) {
        BmSurface surface = withGrade(readerWay.getTag("surface"), readerWay.getTag("tracktype"));


        if (surface == BmSurface.MISSING)
            return;

        surfaceEnc.setEnum(false, edgeId, edgeIntAccess, surface);
    }

    public static BmSurface withGrade(String surfaceTag, String trackTypeTag) {
        BmSurface surface = BmSurface.find(surfaceTag);
        return surface == BmSurface.MISSING ? GRADE_MAP.getOrDefault(trackTypeTag, BmSurface.MISSING) : surface;
    }
}
