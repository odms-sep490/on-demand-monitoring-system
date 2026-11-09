package com.ondemandmonitoring.device.config;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceModel;
import com.ondemandmonitoring.device.domain.DeviceType;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceModelRepository;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.DeviceTypeRepository;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@org.springframework.core.annotation.Order(35)
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DeviceSeedDataInitializer implements ApplicationRunner {

    static final String DRONE_TYPE_CODE = "DRONE";
    static final String MODEL_CODE = "DJI-MAVIC-3E";

    static final String DEVICE_CODE = "DRN-001";
    static final String SERIAL_NUMBER = "M3E-DEMO-001";
    static final String DEVICE_CODE_2 = "DRN-002";
    static final String SERIAL_NUMBER_2 = "M3E-DEMO-002";
    static final String DEVICE_CODE_3 = "DRN-003";
    static final String SERIAL_NUMBER_3 = "M3E-DEMO-003";

    static final String DEVICE_IMAGE_URL =
            "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRLnK1rYadOXNTzz9jh83_n1geH3J4LSLez3Hwsrs3eLp-xj8lcjZT8Ks4&s=10";
    static final String DEVICE_IMAGE_URL_2 =
            "https://cdn.mos.cms.futurecdn.net/3ExwAytUhjDbPCBjg52rvW-1200-80.jpg";
    static final String DEVICE_IMAGE_URL_3 =
            "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSF-PuFVIv4NSIFraK12729c5RKUlEszA3R-Mod1ddSJA&s=10";

    DeviceTypeRepository deviceTypeRepository;
    DeviceModelRepository deviceModelRepository;
    DeviceRepository deviceRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        DeviceType droneType = ensureDeviceType(
                DRONE_TYPE_CODE,
                "Drone",
                "Thiết bị bay không người lái phục vụ nhiệm vụ giám sát và kiểm tra.");

        DeviceModel model = ensureDeviceModel(droneType);

        ensureDevice(SERIAL_NUMBER, DEVICE_CODE, "DJI Mavic 3 Enterprise", model,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL);
        ensureDevice(SERIAL_NUMBER_2, DEVICE_CODE_2, "DJI Mavic 3 Enterprise 02", model,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_2);
        ensureDevice(SERIAL_NUMBER_3, DEVICE_CODE_3, "DJI Mavic 3 Enterprise 03", model,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_3);

        log.info("3 DJI Mavic 3 Enterprise seed devices ready.");
    }

    // Upsert: refresh seed fields on existing rows so edits here take effect after restart.
    private DeviceType ensureDeviceType(String code, String name, String description) {
        DeviceType type = deviceTypeRepository.findByCode(code).orElseGet(DeviceType::new);
        type.setCode(code);
        type.setName(name);
        type.setDescription(description);
        return deviceTypeRepository.save(type);
    }

    private DeviceModel ensureDeviceModel(DeviceType droneType) {
        DeviceModel model = deviceModelRepository.findByCode(MODEL_CODE).orElseGet(DeviceModel::new);
        model.setCode(MODEL_CODE);
        model.setManufacturer("DJI");
        model.setModelName("Mavic 3 Enterprise");
        // Camera is a payload of the drone, not a device type; "camera" key is read by MissionMapperHelper.
        model.setSpecsMetadata(Map.of(
                "aircraftType", "Multirotor",
                "maxFlightMinutes", 45,
                "camera", "RGB",
                "cameraResolutionMp", 20,
                "sensor", "4/3 CMOS",
                "rtkSupported", true,
                "gnss", "GPS/Galileo/BeiDou",
                "supportsPreflight", true,
                "supportsPostflight", true));
        model.setDeviceTypes(new HashSet<>(Set.of(droneType)));
        return deviceModelRepository.save(model);
    }

    private void ensureDevice(
            String serialNumber,
            String deviceCode,
            String name,
            DeviceModel model,
            DeviceStatus status,
            String imageUrl) {
        Device device = deviceRepository.findBySerialNumber(serialNumber).orElse(null);
        if (device == null) {
            device = new Device();
            device.setSerialNumber(serialNumber);
            // Seed must not pretend the drone sent telemetry: lastSeenAt stays null until real telemetry arrives.
            device.setLastSeenAt(null);
            // Status is only seeded on create so a restart never resets a RESERVED/IN_USE device.
            device.setStatus(status);
        }
        device.setDeviceCode(deviceCode);
        device.setName(name);
        device.setDeviceModel(model);
        device.setImageUrl(imageUrl);
        deviceRepository.save(device);
    }
}
