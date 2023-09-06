package com.graphhopper.routing.ev;

public class MaxSlope {
    public static final String KEY = "max_slope";

    public static DecimalEncodedValue create() {
        return new UnsignedDecimalEncodedValue(KEY, 5, 1, false);
    }

}
