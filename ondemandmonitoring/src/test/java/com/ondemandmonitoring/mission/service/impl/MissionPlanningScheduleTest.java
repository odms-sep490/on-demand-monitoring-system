package com.ondemandmonitoring.mission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MissionPlanningScheduleTest {
    private final MissionService service = mock(MissionService.class, CALLS_REAL_METHODS);
    private final Order order = originalOrder();
    private final Instant newStart = Instant.parse("2099-11-11T03:00:00Z");
    private final Instant newEnd = Instant.parse("2099-11-11T04:00:00Z");

    @Test
    void reworkUsesNewDateAndTimeWithoutChangingOriginalPreferences() {
        assertThatCode(() -> validate(newStart, newEnd, MissionPurpose.REWORK)).doesNotThrowAnyException();
        assertThat(order.getPreferredDateTo()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(order.getPreferredTime().getEndTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void originalMissionStillRequiresTheOriginalDateWindow() {
        assertThatThrownBy(() -> validate(newStart, newEnd, MissionPurpose.ORIGINAL))
                .isInstanceOf(ApiException.class).hasMessageContaining("customer preferred dates");
    }

    @Test
    void originalMissionStillRequiresTheOriginalTimeSlot() {
        assertThatThrownBy(() -> validate(Instant.parse("2026-11-09T03:00:00Z"),
                Instant.parse("2026-11-09T04:00:00Z"), MissionPurpose.ORIGINAL))
                .isInstanceOf(ApiException.class).hasMessageContaining("customer preferred time slot");
    }

    @Test
    void reworkCannotBeScheduledInThePast() {
        assertThatThrownBy(() -> validate(Instant.parse("2000-01-01T03:00:00Z"),
                Instant.parse("2000-01-01T04:00:00Z"), MissionPurpose.REWORK))
                .isInstanceOf(ApiException.class).hasMessageContaining("future");
    }

    @Test
    void reworkStillRequiresOrderedTimesOnOneBusinessDay() {
        assertThatThrownBy(() -> validate(newEnd, newStart, MissionPurpose.REWORK))
                .isInstanceOf(ApiException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> validate(Instant.parse("2099-11-11T16:30:00Z"),
                Instant.parse("2099-11-11T17:30:00Z"), MissionPurpose.REWORK))
                .isInstanceOf(ApiException.class).hasMessageContaining("same day");
    }

    private void validate(Instant start, Instant end, MissionPurpose purpose) {
        ReflectionTestUtils.invokeMethod(service, "validatePlanningSchedule", start, end, order, purpose);
    }

    private static Order originalOrder() {
        Order order = new Order();
        order.setPreferredDateFrom(LocalDate.of(2026, 11, 9));
        order.setPreferredDateTo(LocalDate.of(2026, 11, 9));
        PreferredTime slot = new PreferredTime();
        slot.setStartTime(LocalTime.of(8, 0));
        slot.setEndTime(LocalTime.of(9, 0));
        order.setPreferredTime(slot);
        return order;
    }
}
