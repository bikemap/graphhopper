package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.storage.IntsRef;

public class BMWeightParser implements TagParser {

    private final IntEncodedValue BMWeightEnc;

    public BMWeightParser(IntEncodedValue BMWeightEnc) {
        this.BMWeightEnc = BMWeightEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        String bmWeight = way.getTag(BMWeightEnc.getName().replaceAll("_", "-"));
        if (bmWeight != null) {
            double bmWeightDec = Double.parseDouble(bmWeight);
            int bmWeightNum = (int) Math.round(bmWeightDec);
            BMWeightEnc.setInt(false, edgeId, edgeIntAccess, bmWeightNum);
        }
    }


}
