package com.ondemandmonitoring.devicecheck.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.devicecheck.domain.*;
import com.ondemandmonitoring.devicecheck.enums.*;
import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.service.impl.PersistedPreDeviceCheckService;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class PreflightMissionPermitPolicyTest {
  @ParameterizedTest
  @ValueSource(strings = {"OWN", "INHERITED", "MISSING_DOCUMENT", "REPLACEMENT"})
  void preflightPassesOnlyAnApprovedMissionSpecificDocument(String mode) {
    var service = mock(PersistedPreDeviceCheckService.class, CALLS_REAL_METHODS);
    var missions = mock(MissionRepository.class);
    var runs = mock(PersistedPreDeviceCheckRepository.class);
    ReflectionTestUtils.setField(service, "missionRepository", missions);
    ReflectionTestUtils.setField(service, "runRepository", runs);
    var mission = new Mission(); mission.setId("m");
    var permit = FlightPermit.pendingFor(mission); permit.setStatus(FlightPermitStatus.APPROVED);
    permit.setDataUrl(mode.equals("MISSING_DOCUMENT") ? null : "data:application/pdf;base64,AA==");
    if (mode.equals("INHERITED")) permit.setReusedFromMissionId("source");
    permit.setReplacementRequired(mode.equals("REPLACEMENT"));
    var run = new PersistedPreDeviceCheck(); run.setId("r"); run.setMission(mission); run.setStatus(PreDeviceCheckStatus.CHECKING);
    var item = new PersistedPreDeviceCheckItem(); item.setCheckType(PreDeviceCheckType.AIRSPACE_PERMISSION.code()); item.setPreDeviceCheck(run);
    run.getItems().add(item);
    when(missions.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
    when(runs.findFirstByMissionIdOrderByCreatedAtDesc("m")).thenReturn(Optional.of(run));
    when(runs.findByIdForUpdate("r")).thenReturn(Optional.of(run));
    service.refreshFlightPermitCheck("m");
    assertThat(item.getStatus()).isEqualTo(mode.equals("OWN") ? PreDeviceItemStatus.PASSED : PreDeviceItemStatus.FAILED);
  }
}
