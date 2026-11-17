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

    static final String VIDEO_TYPE_CODE = "VIDEO";
    static final String IMAGE_TYPE_CODE = "IMGAGE";
    static final String THERMAL_TYPE_CODE = "THERMAL";
    static final String LEGACY_DRONE_TYPE_CODE = "DRONE";

    static final String MAVIC_3E_MODEL_CODE = "DJI-MAVIC-3E";
    static final String MAVIC_3T_MODEL_CODE = "DJI-MAVIC-3T";
    static final String EVO_II_DUAL_MODEL_CODE = "AUTEL-EVO-II-DUAL-640T";

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
        DeviceType imageType = ensureImageType();
        DeviceType videoType = ensureDeviceType(
                VIDEO_TYPE_CODE,
                "Video",
                "Thiết bị có khả năng ghi hình video phục vụ nhiệm vụ giám sát.");
        DeviceType thermalType = ensureDeviceType(
                THERMAL_TYPE_CODE,
                "Thermal",
                "Thiết bị có cảm biến nhiệt phục vụ phát hiện điểm nóng và chênh lệch nhiệt độ.");

        DeviceModel mavic3e = ensureDeviceModel(
                MAVIC_3E_MODEL_CODE,
                "DJI",
                "Mavic 3 Enterprise",
                Map.of(
                        "aircraftType", "Multirotor",
                        "maxFlightMinutes", 45,
                        "camera", "RGB",
                        "cameraResolutionMp", 20,
                        "sensor", "4/3 CMOS",
                        "rtkSupported", true,
                        "supportsPreflight", true,
                        "supportsPostflight", true),
                Set.of(imageType, videoType));
        DeviceModel mavic3t = ensureDeviceModel(
                MAVIC_3T_MODEL_CODE,
                "DJI",
                "Mavic 3 Thermal",
                Map.of(
                        "aircraftType", "Multirotor",
                        "maxFlightMinutes", 45,
                        "camera", "RGB + Thermal",
                        "cameraResolutionMp", 48,
                        "thermalResolution", "640x512",
                        "rtkSupported", true,
                        "supportsPreflight", true,
                        "supportsPostflight", true),
                Set.of(imageType, videoType, thermalType));
        DeviceModel evoDual = ensureDeviceModel(
                EVO_II_DUAL_MODEL_CODE,
                "Autel Robotics",
                "EVO II Dual 640T",
                Map.of(
                        "aircraftType", "Multirotor",
                        "maxFlightMinutes", 38,
                        "camera", "RGB + Thermal",
                        "cameraResolutionMp", 48,
                        "thermalResolution", "640x512",
                        "rtkSupported", false,
                        "supportsPreflight", true,
                        "supportsPostflight", true),
                Set.of(imageType, videoType, thermalType));

        ensureDevice("M3E-DEMO-001", "DRN-001", "DJI Mavic 3 Enterprise 01", mavic3e,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL);
        ensureDevice("M3E-DEMO-002", "DRN-002", "DJI Mavic 3 Enterprise 02", mavic3e,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_2);
        ensureDevice("M3E-DEMO-003", "DRN-003", "DJI Mavic 3 Enterprise 03", mavic3e,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_3);
        ensureDevice("M3E-DEMO-004", "DRN-004", "DJI Mavic 3 Enterprise 04", mavic3e,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL);
        ensureDevice("M3T-DEMO-001", "DRN-005", "DJI Mavic 3 Thermal 01", mavic3t,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_2);
        ensureDevice("M3T-DEMO-002", "DRN-006", "DJI Mavic 3 Thermal 02", mavic3t,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_3);
        ensureDevice("M3T-DEMO-003", "DRN-007", "DJI Mavic 3 Thermal 03", mavic3t,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_2);
        ensureDevice("EVO2T-DEMO-001", "DRN-008", "Autel EVO II Dual 640T 01", evoDual,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_3);
        ensureDevice("EVO2T-DEMO-002", "DRN-009", "Autel EVO II Dual 640T 02", evoDual,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL);
        ensureDevice("EVO2T-DEMO-003", "DRN-010", "Autel EVO II Dual 640T 03", evoDual,
                DeviceStatus.AVAILABLE, DEVICE_IMAGE_URL_3);

        log.info("3 device types, 3 device models and 10 seed devices ready.");
    }

    /** Reuses the former DRONE seed row so existing references are preserved after the capability migration. */
    private DeviceType ensureImageType() {
        DeviceType type = deviceTypeRepository.findByCode(IMAGE_TYPE_CODE)
                .orElseGet(() -> deviceTypeRepository.findByCode(LEGACY_DRONE_TYPE_CODE)
                        .orElseGet(DeviceType::new));
        type.setCode(IMAGE_TYPE_CODE);
        type.setName("Image");
        type.setDescription("Thiết bị có khả năng chụp ảnh phục vụ nhiệm vụ khảo sát và kiểm tra.");
        return deviceTypeRepository.save(type);
    }

    // Upsert: refresh seed fields on existing rows so edits here take effect after restart.
    private DeviceType ensureDeviceType(String code, String name, String description) {
        DeviceType type = deviceTypeRepository.findByCode(code).orElseGet(DeviceType::new);
        type.setCode(code);
        type.setName(name);
        type.setDescription(description);
        return deviceTypeRepository.save(type);
    }

    private DeviceModel ensureDeviceModel(
            String code,
            String manufacturer,
            String modelName,
            Map<String, Object> specsMetadata,
            Set<DeviceType> deviceTypes) {
        DeviceModel model = deviceModelRepository.findByCode(code).orElseGet(DeviceModel::new);
        model.setCode(code);
        model.setManufacturer(manufacturer);
        model.setModelName(modelName);
        model.setSpecsMetadata(specsMetadata);
        model.setDeviceTypes(new HashSet<>(deviceTypes));
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
