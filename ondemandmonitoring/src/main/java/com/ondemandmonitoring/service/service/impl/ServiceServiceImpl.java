package com.ondemandmonitoring.service.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.dto.request.ServiceRequest;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import com.ondemandmonitoring.service.mapper.ServiceMapper;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.service.service.IServiceService;
import com.ondemandmonitoring.service.service.IServiceImageService;
import java.math.BigDecimal;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ServiceServiceImpl implements IServiceService {

    ServiceRepository serviceRepository;
    ServiceMapper serviceMapper;
    IServiceImageService serviceImageService;
    org.springframework.jdbc.core.JdbcTemplate db;

    @Override
    @Transactional
    public ServiceResponse create(ServiceRequest request) {
        if (serviceRepository.existsByNameIgnoreCase(request.getName())) {
            throw new ApiException(ErrorCode.SERVICE_ALREADY_EXISTS,
                    "Service with name '" + request.getName() + "' already exists");
        }
        if (request.getCode() != null && !request.getCode().isBlank()
                && serviceRepository.existsByCodeIgnoreCase(request.getCode())) {
            throw new ApiException(ErrorCode.SERVICE_ALREADY_EXISTS,
                    "Service with code '" + request.getCode() + "' already exists");
        }
        Service entity = serviceMapper.toEntity(request);
        if (entity.getBasePrice() == null) entity.setBasePrice(BigDecimal.ZERO);
        if (entity.getIsActive() == null) {
            entity.setIsActive(true);
        }
        Service saved = serviceRepository.save(entity);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceResponse getById(String id) {
        Service entity = getEntityById(id);
        return toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceResponse> getAll() {
        return serviceRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceResponse> getAllActive() {
        return serviceRepository.findAllByIsActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public ServiceResponse update(String id, ServiceRequest request) {
        Service entity = getEntityForUpdate(id);
        if (request.getCode() != null && !request.getCode().isBlank()
                && serviceRepository.existsByCodeIgnoreCaseAndIdNot(request.getCode(), id)) {
            throw new ApiException(ErrorCode.SERVICE_ALREADY_EXISTS,
                    "Service with code '" + request.getCode() + "' already exists");
        }
        serviceMapper.updateEntityFromRequest(request, entity);
        Service saved = serviceRepository.save(entity);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(String id) {
        Service entity = getEntityForUpdate(id);
        if (Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM orders WHERE service_id = ?)", Boolean.class, id))) {
            throw new ApiException(ErrorCode.SERVICE_IN_USE);
        }
        try {
            // Delete only owned configuration. Shared definitions and immutable audit rows remain.
            db.update("DELETE FROM mission_template_items WHERE mission_template_id IN (SELECT id FROM mission_templates WHERE service_id = ?)", id);
            db.update("DELETE FROM mission_templates WHERE service_id = ?", id);
            db.update("DELETE FROM service_checklists WHERE service_id = ?", id);
            db.update("DELETE FROM service_deliverables WHERE service_id = ?", id);
            serviceRepository.delete(entity);
            // Surface any FK race here, while it can still become the localized conflict contract.
            serviceRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            throw new ApiException(ErrorCode.SERVICE_IN_USE);
        }
        serviceImageService.scheduleDeletion(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Service getEntityById(String id) {
        return serviceRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND,
                        "Service not found with id: " + id));
    }

    private ServiceResponse toResponse(Service service) {
        ServiceResponse response = serviceMapper.toResponse(service);
        response.setImageUrl(serviceImageService.getImageUrl(service));
        response.setFallbackImageUrl(service.getImageS3Key() == null ? null : service.getImageUrl());
        return response;
    }

    private Service getEntityForUpdate(String id) {
        return serviceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
    }
}
