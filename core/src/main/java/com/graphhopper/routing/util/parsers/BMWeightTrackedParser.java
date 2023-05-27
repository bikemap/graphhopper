package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.storage.IntsRef;

import java.util.Arrays;
import java.util.List;

public class BMWeightTrackedParser implements TagParser {

    private final IntEncodedValue BMWeightTrackedEnc;

    public BMWeightTrackedParser(IntEncodedValue BMWeightTrackedEnc) {
        this.BMWeightTrackedEnc = BMWeightTrackedEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        return edgeFlags;
    }
}
