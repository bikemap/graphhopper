package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.util.parsers.helpers.OSMValueExtractor;
import com.graphhopper.storage.IntsRef;

import java.util.Arrays;
import java.util.List;

public class BMWeightParser implements TagParser {

    private final IntEncodedValue BMWeightEnc;

    public BMWeightParser(IntEncodedValue BMWeightEnc) {
        this.BMWeightEnc = BMWeightEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        int bmweight = way.getTag("bm-weight");
        BMWeightEnc.setInt(false, edgeFlags, bmweight);
        return edgeFlags;
    }
}
