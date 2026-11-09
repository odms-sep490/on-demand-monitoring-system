package com.ondemandmonitoring.service.config;

import com.ondemandmonitoring.service.domain.DeliverableType;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.domain.ServiceDeliverable;
import com.ondemandmonitoring.service.repository.DeliverableTypeRepository;
import com.ondemandmonitoring.service.repository.ServiceDeliverableRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(20)
@RequiredArgsConstructor
@Slf4j
public class ServiceCatalogSeedDataInitializer implements ApplicationRunner {

    private final ServiceRepository serviceRepository;
    private final DeliverableTypeRepository deliverableTypeRepository;
    private final ServiceDeliverableRepository serviceDeliverableRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int servicesUpserted = seedServices();
        int legacyServicesDeactivated = deactivateLegacyServices();
        int deliverablesUpserted = seedDeliverableTypes();
        int legacyDeliverablesDeactivated = deactivateUnsupportedDeliverableTypes();
        int linksCreated = seedServiceDeliverables();
        pruneUnsupportedServiceDeliverables();
        log.info(
                "Service catalog seed completed: services={}, legacyServicesDeactivated={}, deliverableTypes={}, legacyDeliverableTypesDeactivated={}, serviceDeliverables={}",
                servicesUpserted,
                legacyServicesDeactivated,
                deliverablesUpserted,
                legacyDeliverablesDeactivated,
                linksCreated
        );
    }

    private int seedServices() {
        int count = 0;
        for (ServiceSeed seed : serviceSeeds()) {
            if (upsertService(seed)) {
                count++;
            }
        }
        return count;
    }

    private List<ServiceSeed> serviceSeeds() {
        return List.of(
                new ServiceSeed(
                        "CONSTRUCTION_MONITORING",
                        "Giám sát công trình",
                        "Ghi nhận hiện trạng và tiến độ thi công bằng hình ảnh, video từ drone.",
                        3_500_000L,
                        "https://images.unsplash.com/photo-1541888946425-d81bb19240f5?w=1200&auto=format&fit=crop&q=80"
                ),
                new ServiceSeed(
                        "FACTORY_INSPECTION",
                        "Kiểm tra nhà xưởng",
                        "Kiểm tra mái, kết cấu bên ngoài và các vị trí khó tiếp cận bằng drone.",
                        3_000_000L,
                        "https://images.unsplash.com/photo-1565793298595-6a879b1d9492?w=1200&auto=format&fit=crop&q=80"
                ),
                new ServiceSeed(
                        "AREA_MONITORING",
                        "Giám sát khu vực",
                        "Quan sát tổng thể, ghi nhận hiện trạng và các vị trí cần theo dõi trong khu vực.",
                        2_500_000L,
                        "https://images.unsplash.com/photo-1477959858617-67f85cf4f1df?w=1200&auto=format&fit=crop&q=80"
                ),
                new ServiceSeed(
                        "FOREST_MONITORING",
                        "Giám sát rừng",
                        "Quan sát hiện trạng rừng, theo dõi thảm thực vật và ghi nhận khu vực bất thường.",
                        4_000_000L,
                        "https://images.unsplash.com/photo-1448375240586-882707db888b?w=1200&auto=format&fit=crop&q=80"
                )
        );
    }

    private boolean upsertService(ServiceSeed seed) {
        Service service = serviceRepository.findByCodeIgnoreCase(seed.code())
                .or(() -> serviceRepository.findByNameIgnoreCase(seed.name()))
                .orElseGet(Service::new);

        boolean changed = false;
        if (!seed.code().equals(service.getCode())) {
            service.setCode(seed.code());
            changed = true;
        }
        if (!seed.name().equals(service.getName())) {
            service.setName(seed.name());
            changed = true;
        }
        if (!seed.description().equals(service.getDescription())) {
            service.setDescription(seed.description());
            changed = true;
        }
        BigDecimal price = BigDecimal.valueOf(seed.basePrice());
        if (!price.equals(service.getBasePrice())) {
            service.setBasePrice(price);
            changed = true;
        }
        if (!Objects.equals(seed.imageUrl(), service.getImageUrl())) {
            service.setImageUrl(seed.imageUrl());
            changed = true;
        }
        if (!Boolean.TRUE.equals(service.getIsActive())) {
            service.setIsActive(true);
            changed = true;
        }

        if (service.getId() == null || changed) {
            serviceRepository.save(service);
            return true;
        }
        return false;
    }

    private int deactivateLegacyServices() {
        Set<String> supportedCodes = serviceSeeds().stream()
                .map(ServiceSeed::code)
                .collect(Collectors.toSet());
        Set<String> supportedNames = serviceSeeds().stream()
                .map(ServiceSeed::name)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        int count = 0;
        for (Service service : serviceRepository.findAll()) {
            boolean supported = supportedCodes.contains(service.getCode())
                    || (service.getName() != null && supportedNames.contains(service.getName().toLowerCase()));
            if (!supported && Boolean.TRUE.equals(service.getIsActive())) {
                service.setIsActive(false);
                serviceRepository.save(service);
                count++;
            }
        }
        return count;
    }

    private int seedDeliverableTypes() {
        int count = 0;
        for (DeliverableTypeSeed seed : deliverableSeeds()) {
            if (upsertDeliverableType(seed)) {
                count++;
            }
        }
        return count;
    }

    private List<DeliverableTypeSeed> deliverableSeeds() {
        return List.of(
                new DeliverableTypeSeed("Ảnh chụp", "IMAGE"),
                new DeliverableTypeSeed("Video", "VIDEO"),
                new DeliverableTypeSeed("Báo cáo kết quả", "REPORT")
        );
    }

    private boolean upsertDeliverableType(DeliverableTypeSeed seed) {
        DeliverableType type = deliverableTypeRepository.findByNameIgnoreCase(seed.name())
                .orElseGet(DeliverableType::new);

        boolean changed = false;
        if (!seed.name().equals(type.getName())) {
            type.setName(seed.name());
            changed = true;
        }
        if (!seed.defaultFormat().equals(type.getDefaultFormat())) {
            type.setDefaultFormat(seed.defaultFormat());
            changed = true;
        }
        if (!Boolean.TRUE.equals(type.getIsActive())) {
            type.setIsActive(true);
            changed = true;
        }

        if (type.getId() == null || changed) {
            deliverableTypeRepository.save(type);
            return true;
        }
        return false;
    }

    private int deactivateUnsupportedDeliverableTypes() {
        Set<String> supportedNames = deliverableSeeds().stream()
                .map(DeliverableTypeSeed::name)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        int count = 0;
        for (DeliverableType type : deliverableTypeRepository.findAll()) {
            if (type.getName() == null || supportedNames.contains(type.getName().toLowerCase())) {
                continue;
            }
            if (Boolean.TRUE.equals(type.getIsActive())) {
                type.setIsActive(false);
                deliverableTypeRepository.save(type);
                count++;
            }
        }
        return count;
    }

    private int seedServiceDeliverables() {
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        mapping.put("CONSTRUCTION_MONITORING", List.of("Ảnh chụp", "Video", "Báo cáo kết quả"));
        mapping.put("FACTORY_INSPECTION", List.of("Ảnh chụp", "Video", "Báo cáo kết quả"));
        mapping.put("AREA_MONITORING", List.of("Ảnh chụp", "Video"));
        mapping.put("FOREST_MONITORING", List.of("Ảnh chụp", "Video", "Báo cáo kết quả"));

        int count = 0;
        for (Map.Entry<String, List<String>> entry : mapping.entrySet()) {
            Service service = serviceRepository.findByCodeIgnoreCase(entry.getKey()).orElse(null);
            if (service == null) {
                continue;
            }
            for (String deliverableName : entry.getValue()) {
                DeliverableType deliverableType = deliverableTypeRepository
                        .findByNameIgnoreCase(deliverableName)
                        .orElse(null);
                if (deliverableType == null
                        || !Boolean.TRUE.equals(deliverableType.getIsActive())
                        || serviceDeliverableRepository.existsByServiceIdAndDeliverableTypeId(
                                service.getId(), deliverableType.getId())) {
                    continue;
                }
                serviceDeliverableRepository.save(ServiceDeliverable.builder()
                        .service(service)
                        .deliverableType(deliverableType)
                        .build());
                count++;
            }
        }
        return count;
    }

    private void pruneUnsupportedServiceDeliverables() {
        Set<String> supportedServiceCodes = serviceSeeds().stream()
                .map(ServiceSeed::code)
                .collect(Collectors.toSet());
        Set<String> supportedDeliverableNames = deliverableSeeds().stream()
                .map(DeliverableTypeSeed::name)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        serviceDeliverableRepository.findAll().stream()
                .filter(link -> link.getService() == null
                        || link.getDeliverableType() == null
                        || !supportedServiceCodes.contains(link.getService().getCode())
                        || link.getDeliverableType().getName() == null
                        || !supportedDeliverableNames.contains(link.getDeliverableType().getName().toLowerCase()))
                .forEach(serviceDeliverableRepository::delete);
    }

    private record ServiceSeed(String code, String name, String description, long basePrice, String imageUrl) {
    }

    private record DeliverableTypeSeed(String name, String defaultFormat) {
    }

}
