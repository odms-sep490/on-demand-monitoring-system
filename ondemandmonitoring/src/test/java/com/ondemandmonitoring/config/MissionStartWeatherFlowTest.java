package com.ondemandmonitoring.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.flightpermit.service.IFlightPermitService;
import com.ondemandmonitoring.mission.controller.MissionAccessController;
import com.ondemandmonitoring.mission.controller.MissionController;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.MissionService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.weather.controller.WeatherPreflightController;
import com.ondemandmonitoring.weather.domain.MissionWeatherCheck;
import com.ondemandmonitoring.weather.dto.WeatherPreflightCheckResponse;
import com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository;
import com.ondemandmonitoring.weather.service.WeatherPreflightService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Real HTTP controllers, role policy and start business logic; repositories/weather provider are test doubles. */
@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class, GlobalExceptionHandler.class,
        MissionController.class, MissionAccessController.class, WeatherPreflightController.class, MissionStartWeatherFlowTest.Beans.class})
@WebAppConfiguration
class MissionStartWeatherFlowTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthenticatedUserResolver resolver;
    @Autowired MissionRepository missions;
    @Autowired MissionStaffAssignmentRepository assignments;
    @Autowired MissionDeviceAssignmentRepository devices;
    @Autowired ControlHandoverRepository handovers;
    @Autowired MissionWeatherCheckRepository weatherChecks;
    @Autowired WeatherPreflightService weatherProvider;
    @Autowired IMissionService service;
    private MockMvc mvc;
    private Mission mission;
    private MissionDeviceAssignment device;
    private List<MissionStaffAssignment> crew;
    private MissionWeatherCheck latest;

    @BeforeEach void setup() {
        reset(resolver, missions, assignments, devices, handovers, weatherChecks, weatherProvider, service);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var inspector = User.builder().role(Role.builder().code(RoleCode.STAFF).active(true).build()).isActive(true).build();
        inspector.setId("staff-1");
        when(resolver.getCurrentUser()).thenReturn(inspector);
        when(resolver.getCurrentUserId()).thenReturn("staff-1");
        mission = new Mission(); mission.setId("mission-1"); mission.setMissionCode("MS-QA-START"); mission.setStatus(MissionStatus.READY);
        var order = new Order(); order.setId("order-1"); order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
        order.getExecutionPlan().setRevision(1); mission.setOrder(order);
        var permit = new com.ondemandmonitoring.flightpermit.domain.FlightPermit();
        permit.setMission(mission); mission.setFlightPermit(permit);
        mission.getFlightPermit().setStatus(FlightPermitStatus.APPROVED);
        mission.getFlightPermit().setUpdatedAt(Instant.now().minusSeconds(60));
        when(missions.findById("mission-1")).thenReturn(Optional.of(mission));
        when(missions.findByIdWithOrder("mission-1")).thenReturn(Optional.of(mission));
        when(missions.save(any())).thenAnswer(call -> call.getArgument(0));
        crew = new ArrayList<>();
        // The same person may hold all four roles, matching the reported mission.
        for (var role : List.of(MissionStaffRole.PILOT, MissionStaffRole.OPERATOR, MissionStaffRole.MAINTAINER, MissionStaffRole.INSPECTOR))
            crew.add(MissionStaffAssignment.builder().staff(inspector).mission(mission).assignedRole(role)
                    .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).isCurrent(true).build());
        when(assignments.findAllByMissionIdAndIsCurrentTrue("mission-1")).thenReturn(crew);
        when(assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("mission-1", "staff-1"))
                .thenAnswer(call -> crew.stream().filter(a -> "staff-1".equals(a.getStaff().getId())).toList());
        when(assignments.findByMissionId("mission-1")).thenReturn(crew);
        device = new MissionDeviceAssignment(); device.setDeviceRole(DeviceRole.MAIN); device.setDevice(new Device());
        device.setStatus(MissionDeviceAssignmentStatus.READY_TO_FLY); device.setIsCurrent(true);
        when(devices.findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc("mission-1", DeviceRole.MAIN)).thenReturn(List.of(device));
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("mission-1")).thenReturn(List.of(device));
        when(devices.findFirstByMissionIdOrderByCreatedAtDesc("mission-1")).thenReturn(Optional.of(device));
        when(handovers.findTopByStaffAssignment_Mission_IdAndStatusOrderByConfirmedAtDesc("mission-1", "CONFIRMED"))
                .thenReturn(Optional.of(new ControlHandover()));
        latest = null;
        when(weatherChecks.findFirstByMissionIdOrderByCreatedAtDesc("mission-1")).thenAnswer(call -> Optional.ofNullable(latest));
        when(weatherChecks.save(any())).thenAnswer(call -> { latest = call.getArgument(0); latest.setId("weather-1"); return latest; });
        when(weatherProvider.checkCurrent(eq("mission-1"), isNull(), anyDouble(), anyDouble())).thenAnswer(call ->
                new WeatherPreflightCheckResponse(null, "mission-1", null, "PASS", true, "Safe", 1, 2, 0, 10, 28, 70,
                        List.of(), Instant.now(), false, null, null));
        // Execute the real start method without creating the full unrelated mission service dependency graph.
        var runtime = mock(MissionService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(runtime, "missionRepository", missions);
        ReflectionTestUtils.setField(runtime, "missionDeviceAssignmentRepository", devices);
        ReflectionTestUtils.setField(runtime, "controlHandoverRepository", handovers);
        ReflectionTestUtils.setField(runtime, "missionWeatherCheckRepository", weatherChecks);
        ReflectionTestUtils.setField(runtime, "flightPermitService", mock(IFlightPermitService.class));
        ReflectionTestUtils.setField(runtime, "checklistExecutionService", mock(IMissionChecklistExecutionService.class));
        ReflectionTestUtils.setField(runtime, "deviceRepository", mock(DeviceRepository.class));
        var mapper = mock(MissionMapper.class);
        when(mapper.toResponse(any())).thenAnswer(call -> MissionResponse.builder().id(mission.getId()).status(mission.getStatus()).build());
        ReflectionTestUtils.setField(runtime, "missionMapper", mapper);
        when(service.startMission("mission-1")).thenAnswer(call -> runtime.startMission("mission-1"));
    }

    private void checkWeather() throws Exception {
        mvc.perform(post("/api/weather/preflight-check").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"missionId\":\"mission-1\",\"latitude\":10.8,\"longitude\":106.7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.inspectorAccepted").value(false));
    }

    @Test void fourRoleInspectorChecksAcceptsWeatherThenStartsMission() throws Exception {
        mvc.perform(get("/api/missions/mission-1/permissions").with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canStartMission").value(true));
        checkWeather();
        mvc.perform(post("/api/missions/mission-1/start-operation").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.READY);
        mvc.perform(post("/api/weather/missions/mission-1/checks/weather-1/accept").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.inspectorAccepted").value(true));
        mvc.perform(post("/api/missions/mission-1/start-operation").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        assertThat(mission.getActualStartAt()).isNotNull();
        assertThat(device.getStatus()).isEqualTo(MissionDeviceAssignmentStatus.IN_PROGRESS);
        mvc.perform(post("/api/missions/mission-1/start-operation").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test void pilotWithoutInspectorAssignmentCannotAcceptWeatherOrStart() throws Exception {
        checkWeather();
        var other = User.builder().role(Role.builder().code(RoleCode.STAFF).build()).isActive(true).build(); other.setId("other-inspector");
        crew.stream().filter(a -> a.getAssignedRole() == MissionStaffRole.INSPECTOR).forEach(a -> a.setStaff(other));
        mvc.perform(get("/api/missions/mission-1/permissions").with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canStartMission").value(false));
        mvc.perform(post("/api/weather/missions/mission-1/checks/weather-1/accept").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/missions/mission-1/start-operation").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test void expiredWeatherAndChecksBeforePermitCannotBeAcceptedOrUsedForStart() throws Exception {
        checkWeather(); latest.setCheckedAt(Instant.now().minusSeconds(901)); latest.setInspectorAccepted(true);
        mvc.perform(post("/api/weather/missions/mission-1/checks/weather-1/accept").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/missions/mission-1/start-operation").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isBadRequest());
        latest.setCheckedAt(mission.getFlightPermit().getUpdatedAt().minusSeconds(1));
        mvc.perform(post("/api/weather/missions/mission-1/checks/weather-1/accept").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/missions/mission-1/start-operation").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.READY);
    }

    @Configuration
    static class Beans extends MissionAuthorizationHttpTest.Beans {
        @Bean WeatherPreflightService weatherProvider() { return mock(WeatherPreflightService.class); }
    }
}
