package com.ondemandmonitoring.mission.service.impl;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** One display-code format for original, Customer rework and internal supplemental Missions. */
public final class MissionCodeGenerator {
    private MissionCodeGenerator() {}

    public static String next(Predicate<String> exists) {
        return next(exists, () -> ThreadLocalRandom.current().nextInt(100000));
    }

    static String next(Predicate<String> exists, IntSupplier numbers) {
        String code;
        do { code = String.format(Locale.ROOT, "MS-%05d", numbers.getAsInt()); }
        while (exists.test(code));
        return code;
    }
}
