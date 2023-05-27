package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.storage.IntsRef;

import java.util.Arrays;
import java.util.List;

public class BMWeightRoadBikeParser implements TagParser {

    private final IntEncodedValue BMWeightRoadBikeEnc;

    public BMWeightRoadBikeParser(IntEncodedValue BMWeightRoadBikeEnc) {

        this.BMWeightRoadBikeEnc = BMWeightRoadBikeEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        return edgeFlags;
    }
}
