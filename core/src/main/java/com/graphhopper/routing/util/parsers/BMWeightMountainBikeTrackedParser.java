package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.storage.IntsRef;

import java.util.Arrays;
import java.util.List;

public class BMWeightMountainBikeTrackedParser implements TagParser {

    private final IntEncodedValue BMWeightMountainBikeTrackedEnc;

    public BMWeightMountainBikeTrackedParser(IntEncodedValue BMWeightMountainBikeTrackedEnc) {

        this.BMWeightMountainBikeTrackedEnc = BMWeightMountainBikeTrackedEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        return edgeFlags;
    }
}
