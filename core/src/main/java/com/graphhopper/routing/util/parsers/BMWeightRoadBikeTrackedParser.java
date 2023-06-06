package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.storage.IntsRef;

public class BMWeightRoadBikeTrackedParser implements TagParser {

    private final IntEncodedValue BMWeightRoadBikeTrackedEnc;

    public BMWeightRoadBikeTrackedParser(IntEncodedValue BMWeightRoadBikeTrackedEnc) {
        this.BMWeightRoadBikeTrackedEnc = BMWeightRoadBikeTrackedEnc;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, IntsRef relationFlags) {
        String bmWeight = way.getTag("bm-weight-road-bike-tracked");
        if (bmWeight != null) {
            double bmWeightDec = Double.parseDouble(bmWeight);
            int bmWeightNum = (int) Math.round(bmWeightDec);
            BMWeightRoadBikeTrackedEnc.setInt(false, edgeFlags, bmWeightNum);
            return edgeFlags;
        }
        return edgeFlags;
    }
}
