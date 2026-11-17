package com.ondemandmonitoring.mission.service.impl;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MissionCodeGeneratorTest {
    @Test void sharesTheExistingFiveDigitFormat() {
        assertThat(MissionCodeGenerator.next(code -> false, () -> 42)).isEqualTo("MS-00042");
    }
    @Test void skipsCodesAlreadyUsedByAnyMissionType() {
        var sequence = new AtomicInteger(12345);
        assertThat(MissionCodeGenerator.next(code -> code.equals("MS-12345"), sequence::getAndIncrement))
            .isEqualTo("MS-12346");
    }
}
