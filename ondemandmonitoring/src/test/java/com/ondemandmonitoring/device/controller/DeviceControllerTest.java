package com.ondemandmonitoring.device.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.device.dto.response.DeviceResponse;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.service.IDeviceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeviceControllerTest {

    private IDeviceService deviceService;
    private com.ondemandmonitoring.device.service.IDeviceAvailabilityService availabilityService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deviceService = mock(IDeviceService.class);
        availabilityService = mock(com.ondemandmonitoring.device.service.IDeviceAvailabilityService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceController(deviceService, availabilityService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void availabilityUsesExactMissionAndBothDeviceAliases() throws Exception {
        when(availabilityService.getAvailableForMission("mission-1"))
                .thenReturn(List.of(DeviceResponse.builder().id("device-1").build()));
        for (String path : List.of("/api/drones/available", "/api/devices/available")) {
            mockMvc.perform(get(path).param("missionId", "mission-1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("device-1"));
        }
        mockMvc.perform(get("/api/drones/available")).andExpect(status().isBadRequest());
    }

    @Test
    void create_returns201Created() throws Exception {
        DeviceResponse response = DeviceResponse.builder()
                .id("device-123")
                .serialNumber("SN-1001")
                .name("Camera Device 1")
                .status(DeviceStatus.AVAILABLE)
                .build();

        when(deviceService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serialNumber": "SN-1001",
                                  "name": "Camera Device 1",
                                  "modelId": "model-123",
                                  "status": "AVAILABLE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Device created successfully"))
                .andExpect(jsonPath("$.data.id").value("device-123"))
                .andExpect(jsonPath("$.data.serialNumber").value("SN-1001"))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
    }

    @Test
    void create_blankSerialNumber_returns400() throws Exception {
        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serialNumber": "",
                                  "name": "Camera Device 1",
                                  "modelId": "model-123"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getById_returns200() throws Exception {
        DeviceResponse response = DeviceResponse.builder()
                .id("device-123")
                .serialNumber("SN-1001")
                .name("Camera Device 1")
                .build();

        when(deviceService.getById("device-123")).thenReturn(response);

        mockMvc.perform(get("/api/devices/device-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("device-123"))
                .andExpect(jsonPath("$.data.serialNumber").value("SN-1001"));
    }

    @Test
    void getAll_returns200() throws Exception {
        DeviceResponse response = DeviceResponse.builder()
                .id("device-123")
                .serialNumber("SN-1001")
                .name("Camera Device 1")
                .build();

        PageResponse<DeviceResponse> pageResponse = PageResponse.from(
                new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1)
        );

        when(deviceService.getAll(any())).thenReturn(pageResponse);

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("device-123"))
                .andExpect(jsonPath("$.data.items[0].serialNumber").value("SN-1001"));
    }

    @Test
    void update_returns200() throws Exception {
        DeviceResponse response = DeviceResponse.builder()
                .id("device-123")
                .serialNumber("SN-1002")
                .status(DeviceStatus.IN_MISSION)
                .build();

        when(deviceService.update(eq("device-123"), any())).thenReturn(response);

        mockMvc.perform(put("/api/devices/device-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serialNumber": "SN-1002",
                                  "status": "IN_MISSION"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Device updated successfully"))
                .andExpect(jsonPath("$.data.serialNumber").value("SN-1002"))
                .andExpect(jsonPath("$.data.status").value("IN_MISSION"));
    }

    @Test
    void delete_returns200() throws Exception {
        doNothing().when(deviceService).delete("device-123");

        mockMvc.perform(delete("/api/devices/device-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Device deleted successfully"));

        verify(deviceService).delete("device-123");
    }
}
