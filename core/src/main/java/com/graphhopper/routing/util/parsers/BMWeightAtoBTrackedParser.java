package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.storage.IntsRef;

public class BMWeightAtoBTrackedParser implements TagParser {

    private final IntEncodedValue BMWeightAtoBTrackedEnc;

    public BMWeightAtoBTrackedParser(IntEncodedValue BMWeightAtoBTrackedEnc) {
        this.BMWeightAtoBTrackedEnc = BMWeightAtoBTrackedEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        String bmWeight = way.getTag("bm-weight-a-to-b-tracked");
        if (bmWeight != null) {
            double bmWeightDec = Double.parseDouble(bmWeight);
            int bmWeightNum = (int) Math.round(bmWeightDec);
            BMWeightAtoBTrackedEnc.setInt(false, edgeFlags, bmWeightNum);
            return edgeFlags;
        }
        return edgeFlags;
    }
}
