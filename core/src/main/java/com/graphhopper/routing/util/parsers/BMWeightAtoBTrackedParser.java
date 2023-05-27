package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.storage.IntsRef;

import java.util.Arrays;
import java.util.List;

public class BMWeightAtoBTrackedParser implements TagParser {

    private final IntEncodedValue BMWeightAtoBTrackedEnc;

    public BMWeightAtoBTrackedParser(IntEncodedValue BMWeightAtoBTrackedEnc) {

        this.BMWeightAtoBTrackedEnc = BMWeightAtoBTrackedEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        return edgeFlags;
    }
}
