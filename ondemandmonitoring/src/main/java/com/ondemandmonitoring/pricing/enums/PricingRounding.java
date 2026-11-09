package com.ondemandmonitoring.pricing.enums;

/** How a partial block inside a media price tier is billed. */
public enum PricingRounding {
    /** A started block is billed in full. */
    CEILING,
    /** Only complete blocks are billed. */
    FLOOR,
    /** Round to the nearest whole block. */
    HALF_UP
}
