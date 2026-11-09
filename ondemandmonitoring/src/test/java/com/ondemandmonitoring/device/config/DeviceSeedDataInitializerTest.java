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

        verify(deviceTypeRepository, times(1)).save(any(DeviceType.class));
        verify(deviceModelRepository, times(1)).save(any(DeviceModel.class));

        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository, times(3)).save(deviceCaptor.capture());

        Device device = deviceCaptor.getAllValues().getFirst();
        assertThat(device.getSerialNumber()).isEqualTo("M3E-DEMO-001");
        assertThat(device.getDeviceCode()).isEqualTo("DRN-001");
        assertThat(device.getName()).isEqualTo("DJI Mavic 3 Enterprise");
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.AVAILABLE);
        assertThat(device.getLastSeenAt()).isNull();
        assertThat(device.getDeviceModel()).isNotNull();
        assertThat(deviceCaptor.getAllValues())
                .extracting(Device::getDeviceCode)
                .containsExactly("DRN-001", "DRN-002", "DRN-003");
        assertThat(deviceCaptor.getAllValues())
                .extracting(Device::getSerialNumber)
                .containsExactly("M3E-DEMO-001", "M3E-DEMO-002", "M3E-DEMO-003");
    }

    @Test
    void updatesExistingRecordsButKeepsDeviceStatus() {
        DeviceType existingType = new DeviceType();
        when(deviceTypeRepository.findByCode(anyString())).thenReturn(Optional.of(existingType));
        when(deviceTypeRepository.save(any(DeviceType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceModel existingModel = new DeviceModel();
        existingModel.setModelName("A* Monitoring Drone X1");
        when(deviceModelRepository.findByCode(anyString())).thenReturn(Optional.of(existingModel));
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
        assertThat(existingDevice.getName()).isEqualTo("DJI Mavic 3 Enterprise");
        assertThat(existingDevice.getStatus()).isEqualTo(DeviceStatus.RESERVED);
    }
}
