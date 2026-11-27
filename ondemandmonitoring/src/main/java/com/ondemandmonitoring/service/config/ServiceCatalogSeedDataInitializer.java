package com.ondemandmonitoring.service.config;

import com.ondemandmonitoring.service.domain.DeliverableType;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.domain.ServiceDeliverable;
import com.ondemandmonitoring.service.repository.DeliverableTypeRepository;
import com.ondemandmonitoring.service.repository.ServiceDeliverableRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final com.ondemandmonitoring.service.repository.ServiceCatalogBootstrapRepository bootstrap;
    private final DeliverableTypeRepository deliverableTypeRepository;
    private final ServiceDeliverableRepository serviceDeliverableRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrap.claimInitialization() == 0) return;
        // Adopt existing catalogs without rewriting activation, custom rows, names, or configuration.
        if (serviceRepository.count() > 0) {
            log.info("Existing service catalog marked initialized without changes");
            return;
        }
        int servicesCreated = seedServices();
        int deliverablesCreated = seedDeliverableTypes();
        int linksCreated = seedServiceDeliverables();
        log.info("Fresh service catalog initialized: services={}, deliverables={}, links={}",
                servicesCreated, deliverablesCreated, linksCreated);
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
                        "https://images.unsplash.com/photo-1541888946425-d81bb19240f5?w=1200&auto=format&fit=crop&q=80"
                ),
                new ServiceSeed(
                        "FACTORY_INSPECTION",
                        "Kiểm tra nhà xưởng",
                        "Kiểm tra mái, kết cấu bên ngoài và các vị trí khó tiếp cận bằng drone.",
                        "https://images.unsplash.com/photo-1565793298595-6a879b1d9492?w=1200&auto=format&fit=crop&q=80"
                ),
                new ServiceSeed(
                        "AREA_MONITORING",
                        "Giám sát khu vực",
                        "Quan sát tổng thể, ghi nhận hiện trạng và các vị trí cần theo dõi trong khu vực.",
                        "https://images.unsplash.com/photo-1477959858617-67f85cf4f1df?w=1200&auto=format&fit=crop&q=80"
                ),
                new ServiceSeed(
                        "FOREST_MONITORING",
                        "Giám sát rừng",
                        "Quan sát hiện trạng rừng, theo dõi thảm thực vật và ghi nhận khu vực bất thường.",
                        "https://images.unsplash.com/photo-1448375240586-882707db888b?w=1200&auto=format&fit=crop&q=80"
                )
        );
    }

    private boolean upsertService(ServiceSeed seed) {
        if (serviceRepository.findByCodeIgnoreCase(seed.code())
                .or(() -> serviceRepository.findByNameIgnoreCase(seed.name())).isPresent()) return false;
        serviceRepository.save(Service.builder().code(seed.code()).name(seed.name())
                .description(seed.description()).imageUrl(seed.imageUrl()).isActive(true).build());
        return true;
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
        if (deliverableTypeRepository.findByNameIgnoreCase(seed.name()).isPresent()) return false;
        deliverableTypeRepository.save(DeliverableType.builder().name(seed.name())
                .defaultFormat(seed.defaultFormat()).isActive(true).build());
        return true;
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

    private record ServiceSeed(String code, String name, String description, String imageUrl) {
    }

    private record DeliverableTypeSeed(String name, String defaultFormat) {
    }

}
