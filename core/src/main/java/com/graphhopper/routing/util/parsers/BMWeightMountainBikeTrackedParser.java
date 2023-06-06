package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.storage.IntsRef;

public class BMWeightMountainBikeTrackedParser implements TagParser {

    private final IntEncodedValue BMWeightMountainBikeTrackedEnc;

    public BMWeightMountainBikeTrackedParser(IntEncodedValue BMWeightMountainBikeTrackedEnc) {
        this.BMWeightMountainBikeTrackedEnc = BMWeightMountainBikeTrackedEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        String bmWeight = way.getTag("bm-weight-mountain-bike-tracked");
        if (bmWeight != null) {
            double bmWeightDec = Double.parseDouble(bmWeight);
            int bmWeightNum = (int) Math.round(bmWeightDec);
            BMWeightMountainBikeTrackedEnc.setInt(false, edgeFlags, bmWeightNum);
            return edgeFlags;
        }
        return edgeFlags;
    }
}
