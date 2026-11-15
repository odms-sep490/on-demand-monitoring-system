package com.ondemandmonitoring.missiontemplate.service;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.missiontemplate.domain.MissionTemplate;
import com.ondemandmonitoring.missiontemplate.domain.MissionTemplateItem;
import com.ondemandmonitoring.missiontemplate.dto.MissionTemplateRequest;
import com.ondemandmonitoring.missiontemplate.dto.MissionTemplateResponse;
import com.ondemandmonitoring.missiontemplate.repository.MissionTemplateItemRepository;
import com.ondemandmonitoring.missiontemplate.repository.MissionTemplateRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionTemplateServiceImpl implements IMissionTemplateService {
    private final ServiceRepository services;
    private final ChecklistDefinitionRepository checklists;
    private final MissionTemplateRepository templates;
    private final MissionTemplateItemRepository items;

    @Override
    public List<MissionTemplateResponse> getAll(String serviceId) {
        requireService(serviceId);
        var found = templates.findAllByServiceIdOrderByCreatedAtAscIdAsc(serviceId);
        return responses(found);
    }

    @Override
    public MissionTemplateResponse get(String serviceId, String templateId) {
        requireService(serviceId);
        return responses(List.of(requireTemplate(serviceId, templateId))).getFirst();
    }

    @Override
    @Transactional
    public MissionTemplateResponse create(String serviceId, MissionTemplateRequest request) {
        var template = new MissionTemplate();
        template.setService(lockService(serviceId));
        template.setScopeLabel(request.scopeLabel().strip());
        var saved = templates.saveAndFlush(template);
        replaceItems(saved, request.checklistIds());
        return response(saved, request.checklistIds());
    }

    @Override
    @Transactional
    public MissionTemplateResponse update(String serviceId, String templateId, MissionTemplateRequest request) {
        lockService(serviceId);
        var template = requireTemplate(serviceId, templateId);
        template.setScopeLabel(request.scopeLabel().strip());
        replaceItems(template, request.checklistIds());
        return response(templates.saveAndFlush(template), request.checklistIds());
    }

    @Override
    @Transactional
    public void delete(String serviceId, String templateId) {
        requireService(serviceId);
        var template = requireTemplate(serviceId, templateId);
        var current = items.findAllByMissionTemplateId(templateId);
        items.deleteAll(current);
        items.flush();
        templates.delete(template);
        templates.flush();
    }

    private void replaceItems(MissionTemplate template, List<String> checklistIds) {
        var uniqueIds = new LinkedHashSet<>(checklistIds);
        Map<String, ChecklistDefinition> definitions = new LinkedHashMap<>();
        checklists.findAllById(uniqueIds).forEach(definition -> definitions.put(definition.getId(), definition));
        if (definitions.size() != uniqueIds.size()) throw new ApiException(ErrorCode.CHECKLIST_NOT_FOUND);
        if (!uniqueIds.isEmpty()) {
            var serviceId = template.getService().getId();
            var overlapCount = items.countOverlappingChecklistsExcludingTemplate(
                    serviceId, template.getId(), uniqueIds);
            if (overlapCount > 0) {
                throw new ApiException(ErrorCode.MISSION_TEMPLATE_CHECKLIST_OVERLAP);
            }
        }
        var current = items.findAllByMissionTemplateId(template.getId());
        items.deleteAll(current);
        items.flush();
        var replacements = new ArrayList<MissionTemplateItem>();
        uniqueIds.forEach(id -> {
            var item = new MissionTemplateItem();
            item.setMissionTemplate(template);
            item.setChecklistDefinition(definitions.get(id));
            replacements.add(item);
        });
        items.saveAllAndFlush(replacements);
    }

    private List<MissionTemplateResponse> responses(List<MissionTemplate> found) {
        if (found.isEmpty()) return List.of();
        Map<String, List<String>> checklistIds = new LinkedHashMap<>();
        items.findAllByMissionTemplateIdIn(found.stream().map(MissionTemplate::getId).toList())
                .forEach(item -> checklistIds.computeIfAbsent(item.getMissionTemplate().getId(), ignored -> new ArrayList<>())
                        .add(item.getChecklistDefinition().getId()));
        return found.stream().map(template -> response(template,
                checklistIds.getOrDefault(template.getId(), List.of()))).toList();
    }

    private MissionTemplateResponse response(MissionTemplate template, Collection<String> checklistIds) {
        return new MissionTemplateResponse(template.getId(), template.getService().getId(), template.getScopeLabel(),
                List.copyOf(checklistIds),
                template.getCreatedAt(), template.getUpdatedAt(), template.getVersion());
    }

    private MissionTemplate requireTemplate(String serviceId, String templateId) {
        return templates.findByIdAndServiceId(templateId, serviceId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_TEMPLATE_NOT_FOUND));
    }

    private void requireService(String serviceId) {
        if (!services.existsById(serviceId)) throw new ApiException(ErrorCode.SERVICE_NOT_FOUND);
    }

    private com.ondemandmonitoring.service.domain.Service lockService(String serviceId) {
        return services.findByIdForUpdate(serviceId)
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND));
    }
}
