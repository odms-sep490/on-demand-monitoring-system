package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.media.mapper.MediaAssetMapper;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.service.IMissionMediaUploadService;
import com.ondemandmonitoring.mission.service.IMissionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionControllerStaffSearchTest {

    private final IMissionService missionService = mock(IMissionService.class);
    private final MissionController controller = new MissionController(missionService,
                org.mockito.Mockito.mock(com.ondemandmonitoring.flightpermit.service.IFlightPermitService.class),
                mock(IMissionMediaUploadService.class),
                mock(MediaAssetMapper.class));

    @Test
    void searchStaffMissionsConvertsInclusiveVietnameseDatesToUtcInterval() {
        PageResponse<MissionResponse> page = PageResponse.<MissionResponse>builder()
                .items(List.of()).page(0).size(50).totalPages(0).build();
        Instant start = Instant.parse("2026-09-20T17:00:00Z");
        Instant endExclusive = Instant.parse("2026-09-27T17:00:00Z");
        when(missionService.searchStaffMissions(eq(MissionStatus.PENDING_CONFIRMATION), eq(start),
                eq(endExclusive), any(Pageable.class))).thenReturn(page);

        var result = controller.searchStaffMissions(MissionStatus.PENDING_CONFIRMATION,
                LocalDate.parse("2026-09-21"), LocalDate.parse("2026-09-27"), 0, 50);

        assertEquals(page, result.getBody().getData());
        verify(missionService).searchStaffMissions(eq(MissionStatus.PENDING_CONFIRMATION), eq(start),
                eq(endExclusive), any(Pageable.class));
    }

    @Test
    void searchStaffMissionsRejectsInvertedRangeAndUnboundedPageSize() {
        assertThrows(ApiException.class, () -> controller.searchStaffMissions(null,
                LocalDate.parse("2026-09-27"), LocalDate.parse("2026-09-21"), 0, 50));
        assertThrows(ApiException.class, () -> controller.searchStaffMissions(null, null, null, 0, 101));
    }
}
