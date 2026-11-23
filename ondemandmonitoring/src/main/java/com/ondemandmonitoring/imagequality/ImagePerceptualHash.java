package com.ondemandmonitoring.imagequality;

import java.util.regex.Pattern;

public final class ImagePerceptualHash {
    private static final Pattern HEX = Pattern.compile("[a-fA-F0-9]{16}");

    private ImagePerceptualHash() {}

    public static Long parse(String value) {
        return value != null && HEX.matcher(value).matches() ? Long.parseUnsignedLong(value, 16) : null;
    }

    public static boolean similar(Long first, Long second) {
        return first != null && second != null && Long.bitCount(first ^ second) <= 5;
    }
}
