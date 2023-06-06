package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.storage.IntsRef;

public class BMWeightMountainBikeParser implements TagParser {

    private final IntEncodedValue BMWeightMountainBikeEnc;

    public BMWeightMountainBikeParser(IntEncodedValue BMWeightMountainBikeEnc) {
        this.BMWeightMountainBikeEnc = BMWeightMountainBikeEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        String bmWeight = way.getTag("bm-weight-mountain-bike");
        if (bmWeight != null) {
            double bmWeightDec = Double.parseDouble(bmWeight);
            int bmWeightNum = (int) Math.round(bmWeightDec);
            BMWeightMountainBikeEnc.setInt(false, edgeFlags, bmWeightNum);
            return edgeFlags;
        }
        return edgeFlags;
    }
}
