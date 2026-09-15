package com.graphhopper.routing.ev;

public class BmRampBicycle {
    public final static String KEY = "bm_ramp_bicycle";

    public static BooleanEncodedValue create() {
        return new SimpleBooleanEncodedValue(KEY, true);
    }

}
