package com.ondemandmonitoring.device.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceModel;
import com.ondemandmonitoring.device.domain.DeviceType;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceModelRepository;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.DeviceTypeRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceSeedDataInitializerTest {

    @Mock
    private DeviceTypeRepository deviceTypeRepository;

    @Mock
    private DeviceModelRepository deviceModelRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @InjectMocks
    private DeviceSeedDataInitializer initializer;

    @Test
    void seedsDevicesWhenMissing() {
        when(deviceTypeRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(deviceTypeRepository.save(any(DeviceType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(deviceModelRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(deviceModelRepository.save(any(DeviceModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(deviceRepository.findBySerialNumber(anyString())).thenReturn(Optional.empty());
        when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

        initializer.run(null);

        ArgumentCaptor<DeviceType> typeCaptor = ArgumentCaptor.forClass(DeviceType.class);
        verify(deviceTypeRepository, times(3)).save(typeCaptor.capture());
        assertThat(typeCaptor.getAllValues())
                .extracting(DeviceType::getCode)
                .containsExactly("IMGAGE", "VIDEO", "THERMAL");

        ArgumentCaptor<DeviceModel> modelCaptor = ArgumentCaptor.forClass(DeviceModel.class);
        verify(deviceModelRepository, times(3)).save(modelCaptor.capture());
        assertThat(modelCaptor.getAllValues())
                .extracting(DeviceModel::getCode)
                .containsExactly("DJI-MAVIC-3E", "DJI-MAVIC-3T", "AUTEL-EVO-II-DUAL-640T");

        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository, times(10)).save(deviceCaptor.capture());

        Device device = deviceCaptor.getAllValues().getFirst();
        assertThat(device.getSerialNumber()).isEqualTo("M3E-DEMO-001");
        assertThat(device.getDeviceCode()).isEqualTo("DRN-001");
        assertThat(device.getName()).isEqualTo("DJI Mavic 3 Enterprise 01");
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.AVAILABLE);
        assertThat(device.getLastSeenAt()).isNull();
        assertThat(device.getDeviceModel()).isNotNull();
        assertThat(deviceCaptor.getAllValues())
                .extracting(Device::getDeviceCode)
                .containsExactly(
                        "DRN-001", "DRN-002", "DRN-003", "DRN-004", "DRN-005",
                        "DRN-006", "DRN-007", "DRN-008", "DRN-009", "DRN-010");
        assertThat(deviceCaptor.getAllValues())
                .extracting(Device::getSerialNumber)
                .containsExactly(
                        "M3E-DEMO-001", "M3E-DEMO-002", "M3E-DEMO-003", "M3E-DEMO-004",
                        "M3T-DEMO-001", "M3T-DEMO-002", "M3T-DEMO-003",
                        "EVO2T-DEMO-001", "EVO2T-DEMO-002", "EVO2T-DEMO-003");
    }

    @Test
    void updatesExistingRecordsButKeepsDeviceStatus() {
        DeviceType existingType = new DeviceType();
        existingType.setCode("IMGAGE");
        when(deviceTypeRepository.findByCode(anyString())).thenAnswer(invocation ->
                "IMGAGE".equals(invocation.getArgument(0))
                        ? Optional.of(existingType)
                        : Optional.empty());
        when(deviceTypeRepository.save(any(DeviceType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceModel existingModel = new DeviceModel();
        existingModel.setModelName("A* Monitoring Drone X1");
        when(deviceModelRepository.findByCode(anyString())).thenAnswer(invocation ->
                "DJI-MAVIC-3E".equals(invocation.getArgument(0))
                        ? Optional.of(existingModel)
                        : Optional.empty());
        when(deviceModelRepository.save(any(DeviceModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Device existingDevice = new Device();
        existingDevice.setSerialNumber("M3E-DEMO-001");
        existingDevice.setName("Drone test 0052");
        existingDevice.setStatus(DeviceStatus.RESERVED);

        when(deviceRepository.findBySerialNumber(anyString())).thenAnswer(invocation ->
                "M3E-DEMO-001".equals(invocation.getArgument(0))
                        ? Optional.of(existingDevice)
                        : Optional.empty());
        when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

        initializer.run(null);

        assertThat(existingModel.getModelName()).isEqualTo("Mavic 3 Enterprise");
        assertThat(existingModel.getManufacturer()).isEqualTo("DJI");
        assertThat(existingDevice.getName()).isEqualTo("DJI Mavic 3 Enterprise 01");
        assertThat(existingDevice.getStatus()).isEqualTo(DeviceStatus.RESERVED);
    }
}
