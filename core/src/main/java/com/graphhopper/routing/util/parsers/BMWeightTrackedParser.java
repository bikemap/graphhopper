package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.storage.IntsRef;

public class BMWeightTrackedParser implements TagParser {

    private final IntEncodedValue BMWeightTrackedEnc;

    public BMWeightTrackedParser(IntEncodedValue BMWeightTrackedEnc) {
        this.BMWeightTrackedEnc = BMWeightTrackedEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        String bmWeight = way.getTag("bm-weight-tracked");
        if (bmWeight != null) {
            double bmWeightDec = Double.parseDouble(bmWeight);
            int bmWeightNum = (int) Math.round(bmWeightDec);
            BMWeightTrackedEnc.setInt(false, edgeFlags, bmWeightNum);
            return edgeFlags;
        }
        return edgeFlags;
    }
}
