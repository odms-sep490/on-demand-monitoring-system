package com.ondemandmonitoring.warehouse.config;

import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import com.ondemandmonitoring.warehouse.enums.PreferredTimeCode;
import com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository;
import java.time.LocalTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PreferredTimeDataInitializer {

    PreferredTimeRepository preferredTimeRepository;

    @Bean
    ApplicationRunner seedPreferredTimes() {
        return args -> DEFAULT_TIMES.forEach(time -> {
            if (!preferredTimeRepository.existsByCode(time.code())) {
                preferredTimeRepository.save(PreferredTime.builder()
                        .code(time.code())
                        .name(time.name())
                        .startTime(time.startTime())
                        .endTime(time.endTime())
                        .build());
            }
        });
    }

    private static final List<PreferredTimeSeed> DEFAULT_TIMES = List.of(
            new PreferredTimeSeed(PreferredTimeCode.MORNING, "Morning", LocalTime.of(7, 0), LocalTime.of(11, 0)),
            new PreferredTimeSeed(PreferredTimeCode.AFTERNOON, "Afternoon", LocalTime.of(13, 0), LocalTime.of(17, 0)),
            new PreferredTimeSeed(PreferredTimeCode.EVENING, "Evening", LocalTime.of(17, 30), LocalTime.of(20, 30)),
            new PreferredTimeSeed(PreferredTimeCode.NIGHT, "Night", LocalTime.of(21, 0), LocalTime.of(23, 59)),
            new PreferredTimeSeed(PreferredTimeCode.ANY_TIME, "Any time", LocalTime.MIDNIGHT, LocalTime.of(23, 59))
    );

    private record PreferredTimeSeed(PreferredTimeCode code, String name, LocalTime startTime, LocalTime endTime) {
    }
}
