package com.ondemandmonitoring.service.mapper;

import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.dto.request.ServiceRequest;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ServiceMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "imageS3Key", ignore = true)
    @Mapping(target = "imageS3Bucket", ignore = true)
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "checklistDefaultsInitialized", ignore = true)
    Service toEntity(ServiceRequest request);

    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "fallbackImageUrl", ignore = true)
    @Mapping(target = "defaultPrice", ignore = true)
    @Mapping(target = "priceSource", ignore = true)
    ServiceResponse toResponse(Service entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "imageS3Key", ignore = true)
    @Mapping(target = "imageS3Bucket", ignore = true)
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "checklistDefaultsInitialized", ignore = true)
    void updateEntityFromRequest(ServiceRequest request, @MappingTarget Service entity);
}
