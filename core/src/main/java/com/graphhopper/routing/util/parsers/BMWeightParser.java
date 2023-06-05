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
        String bmWeight = way.getTag("bm-weight");
        if (bmWeight != null) {
            double bmWeightNumber = Double.parseDouble(bmWeight);
            BMWeightEnc.setInt(false, edgeFlags, (int) Math.round(bmWeightNumber));
            return edgeFlags;
        }
        return edgeFlags;
    }
}
