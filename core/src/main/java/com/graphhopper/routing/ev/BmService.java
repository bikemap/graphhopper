
package com.graphhopper.routing.ev;

import com.graphhopper.util.Helper;


public enum BmService {
    // Order is important to make ordinal roughly comparable
    MISSING,

    PARKING_AISLE, DRIVEWAY, ALLEY, EMERGENCY_ACCESS, DRIVE_THROUGH, SLIPWAY, OTHER;

    public static final String KEY = "bm_service";

    public static EnumEncodedValue<BmService> create() {
        return new EnumEncodedValue<>(KEY, BmService.class);
    }

    @Override
    public String toString() {
        return Helper.toLowerCase(super.toString());
    }

    public static BmService find(String name) {
        if (name == null || name.isEmpty())
            return MISSING;
        try {
            return BmService.valueOf(Helper.toUpperCase(name));
        } catch (IllegalArgumentException ex) {
            return OTHER;
        }
    }

}
