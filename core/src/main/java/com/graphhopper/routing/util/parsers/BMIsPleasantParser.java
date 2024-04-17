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
import com.graphhopper.routing.ev.*;
import com.graphhopper.storage.IntsRef;


public class BMIsPleasantParser implements TagParser {
    private final IntEncodedValue isPleasantEnc;

    public BMIsPleasantParser(IntEncodedValue isPleasantEnc) {
        this.isPleasantEnc = isPleasantEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay readerWay, IntsRef relationFlags) {
        String highwayTag = readerWay.getTag("highway");
        BMWayType wayType = BMWayType.find(highwayTag);

        String surfaceTag = readerWay.getTag("surface");
        String trackTypeTag = readerWay.getTag("tracktype");
        BMSurface surface = BMSurfaceParser.withGrade(surfaceTag, trackTypeTag);
        boolean missingSurface = surface == BMSurface.MISSING || surface == BMSurface.OTHER;

        boolean isPleasant;

        if (wayType == BMWayType.MISSING && missingSurface) {
            return;
        } else if (wayType == BMWayType.MISSING) {
            isPleasant = surface.isPleasant();
        } else if (missingSurface) {
            isPleasant = wayType.isPleasant();
        } else {
            isPleasant = wayType.isPleasant() && surface.isPleasant();
        }

        isPleasantEnc.setInt(false, edgeId, edgeIntAccess, isPleasant ? 1:0);
    }

}
