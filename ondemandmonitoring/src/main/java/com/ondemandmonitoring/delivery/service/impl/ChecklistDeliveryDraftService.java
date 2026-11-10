package com.ondemandmonitoring.delivery.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.ChecklistDeliveryDraft;
import com.ondemandmonitoring.delivery.dto.request.SaveChecklistDeliveryDraftRequest;
import com.ondemandmonitoring.delivery.dto.response.ChecklistDeliveryDraftResponse;
import com.ondemandmonitoring.delivery.repository.ChecklistDeliveryDraftRepository;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository;
import com.ondemandmonitoring.delivery.service.IChecklistDeliveryDraftService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChecklistDeliveryDraftService implements IChecklistDeliveryDraftService {
    private final ChecklistDeliveryDraftRepository drafts;
    private final OrderRepository orders;
    private final OrderDeliveryRepository deliveries;
    private final ResultRevisionCaseRepository cases;
    private final AuthenticatedUserResolver currentUser;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private DeliveryMissionScope missionScope;

    @Override
    @Transactional(readOnly = true)
    public ChecklistDeliveryDraftResponse get(String orderId) { return get(orderId, null); }

    @Override @Transactional(readOnly = true)
    public ChecklistDeliveryDraftResponse get(String orderId, String missionId) {
        var order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var delivery = (missionId == null ? deliveries.findByOrderId(orderId) : missionScope.find(orderId, missionId, false)).orElse(null);
        var currentCase = cases.findByOrderIdAndActiveTrue(orderId).orElse(null);
        var draft = (missionId == null ? drafts.findByOrderId(orderId) : drafts.findScoped(orderId, missionId)).orElse(null);
        if (draft == null) return new ChecklistDeliveryDraftResponse(order.getId(), 0, currentCase == null ? null : currentCase.getId(),
                delivery == null ? null : delivery.getCurrentReviewRoundId(), delivery == null ? null : delivery.getVersion(), "", List.of(), null, false);
        List<SaveChecklistDeliveryDraftRequest.ChecklistOperation> operations = mapper.convertValue(
                draft.getChecklistOperations(), new TypeReference<>() {});
        boolean stale = !Objects.equals(draft.getBaseReviewRoundId(), delivery == null ? null : delivery.getCurrentReviewRoundId())
                || !Objects.equals(draft.getExpectedDeliveryVersion(), delivery == null ? null : delivery.getVersion())
                || !Objects.equals(draft.getCaseId(), currentCase == null ? null : currentCase.getId());
        return response(draft, operations, stale);
    }

    @Override
    @Transactional
    public ChecklistDeliveryDraftResponse save(String orderId, SaveChecklistDeliveryDraftRequest request) { return save(orderId, request, null); }

    @Override @Transactional
    public ChecklistDeliveryDraftResponse save(String orderId, SaveChecklistDeliveryDraftRequest request, String missionId) {
        var order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var delivery = (missionId == null ? deliveries.findByOrderIdForUpdate(orderId) : missionScope.find(orderId, missionId, true)).orElse(null);
        var activeCase = cases.findByOrderIdAndActiveTrueForUpdate(orderId).orElse(null);
        if (!Objects.equals(request.baseReviewRoundId(), delivery == null ? null : delivery.getCurrentReviewRoundId())
                || !Objects.equals(request.expectedDeliveryVersion(), delivery == null ? null : delivery.getVersion())
                || !Objects.equals(request.caseId(), activeCase == null ? null : activeCase.getId()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);

        var draft = (missionId == null ? drafts.findByOrderIdForUpdate(orderId) : drafts.findScopedForUpdate(orderId, missionId)).orElse(null);
        long actualVersion = draft == null || draft.getVersion() == null ? 0 : draft.getVersion();
        if (actualVersion != request.expectedDraftVersion())
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED, "The saved delivery draft changed. Refresh before saving again.");
        if (draft == null) {
            draft = new ChecklistDeliveryDraft();
            draft.setOrder(order);
            draft.setMissionId(missionId);
        }
        draft.setCaseId(request.caseId());
        draft.setBaseReviewRoundId(request.baseReviewRoundId());
        draft.setExpectedDeliveryVersion(request.expectedDeliveryVersion());
        draft.setNotes(request.notes() == null ? "" : request.notes().trim());
        draft.setChecklistOperations(mapper.valueToTree(request.checklists()));
        draft.setUpdatedBy(currentUser.getCurrentUserId());
        draft = drafts.saveAndFlush(draft);
        return response(draft, request.checklists(), false);
    }

    @Override
    @Transactional
    public void clear(String orderId, long expectedDraftVersion) { clear(orderId, expectedDraftVersion, null); }

    @Override @Transactional
    public void clear(String orderId, long expectedDraftVersion, String missionId) {
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (missionId != null) missionScope.requireOriginal(orderId, missionId);
        var draft = (missionId == null ? drafts.findByOrderIdForUpdate(orderId) : drafts.findScopedForUpdate(orderId, missionId)).orElse(null);
        long actualVersion = draft == null || draft.getVersion() == null ? 0 : draft.getVersion();
        if (actualVersion != expectedDraftVersion)
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED,
                    "The saved delivery draft changed. Refresh before discarding it.");
        if (draft != null) drafts.delete(draft);
    }

    @Override
    @Transactional
    public void clearAfterPublish(String orderId) { clearAfterPublish(orderId, null); }

    @Override @Transactional
    public void clearAfterPublish(String orderId, String missionId) {
        (missionId == null ? drafts.findByOrderId(orderId) : drafts.findScoped(orderId, missionId)).ifPresent(drafts::delete);
    }

    private ChecklistDeliveryDraftResponse response(ChecklistDeliveryDraft draft,
            List<SaveChecklistDeliveryDraftRequest.ChecklistOperation> operations, boolean stale) {
        return new ChecklistDeliveryDraftResponse(draft.getOrder().getId(),
                draft.getVersion() == null ? 0 : draft.getVersion(), draft.getCaseId(),
                draft.getBaseReviewRoundId(), draft.getExpectedDeliveryVersion(), draft.getNotes(),
                operations, draft.getUpdatedAt(), stale);
    }
}
