package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.*;
import com.ondemandmonitoring.delivery.mapper.ResultReviewEvidenceMapper;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.delivery.service.impl.ResultRevisionCaseService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository;
import com.ondemandmonitoring.support.service.impl.SupportTicketService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultRevisionCaseEvidenceTest {
    final ResultRevisionCaseRepository cases = mock(ResultRevisionCaseRepository.class);
    final ResultRevisionCaseItemRepository items = mock(ResultRevisionCaseItemRepository.class);
    final ResultRevisionRequestItemRepository requests = mock(ResultRevisionRequestItemRepository.class);
    final ResultRevisionRequestItemMediaRepository media = mock(ResultRevisionRequestItemMediaRepository.class);
    final SupportCustomerTicketRepository tickets = mock(SupportCustomerTicketRepository.class);
    final MediaDerivativeService derivatives = mock(MediaDerivativeService.class);
    final IResultRetentionAccessService retention = mock(IResultRetentionAccessService.class);
    final ResultRevisionCaseService service = new ResultRevisionCaseService(cases, items, requests, tickets,
            mock(SupportTicketService.class), mock(AuthenticatedUserResolver.class), mock(IMissionService.class),
            mock(MissionRepository.class), mock(OrderRepository.class), mock(IDeliveryWorkflowService.class),
            media, new ResultReviewEvidenceMapper(derivatives, retention));
    MediaAsset firstAsset;

    @BeforeEach void setup() {
        var order = new Order(); order.setId("order");
        var revisionCase = new ResultRevisionCase(); revisionCase.setId("case");
        revisionCase.setOrder(order); revisionCase.setTicketId("ticket");
        var ticket = new SupportCustomerTicket(); ticket.setId("ticket");
        var checklist = new OrderChecklistItem(); checklist.setId("checklist"); checklist.setContent("Roof");
        var item = new ResultRevisionCaseItem(); item.setId("case-item"); item.setOrderChecklistItem(checklist);
        when(cases.findByOrderIdAndActiveTrue("order")).thenReturn(Optional.of(revisionCase));
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));
        when(items.findByResultRevisionCaseIdOrderByCreatedAtAsc("case")).thenReturn(List.of(item));
        var first = feedback("feedback-1", item, 1);
        var second = feedback("feedback-2", item, 2);
        when(requests.findByCaseItemIdInOrderByCreatedAtAscIdAsc(List.of("case-item")))
                .thenReturn(List.of(first, second));
        var firstLink = selected(first, "evidence-old", "original-name.jpg");
        var secondLink = selected(second, "evidence-new", "new-name.jpg");
        firstAsset = firstLink.getRoundEvidence().getMediaAsset();
        when(media.findByRequestItemIdIn(List.of("feedback-1", "feedback-2")))
                .thenReturn(List.of(secondLink, firstLink));
    }

    @Test void attachesOnlySelectedMediaToItsOwnFeedbackRoundInOneBatch() {
        var feedback = service.getByOrderId("order").items().getFirst().feedback();
        assertThat(feedback).hasSize(2);
        assertThat(feedback.get(0).roundNumber()).isEqualTo(1);
        assertThat(feedback.get(0).evidence()).extracting(e -> e.roundEvidenceId()).containsExactly("evidence-old");
        assertThat(feedback.get(1).evidence()).extracting(e -> e.roundEvidenceId()).containsExactly("evidence-new");
        assertThat(feedback.get(0).evidence().getFirst().previewUrl()).isEqualTo("preview-evidence-old");
        assertThat(feedback.get(0).evidence().getFirst().url()).isNull();
        verify(media).findByRequestItemIdIn(List.of("feedback-1", "feedback-2"));
        verify(media, never()).findByRequestItemId(anyString());
    }

    @Test void expiredEvidenceKeepsItsIdentityWithoutSigningViewingUrls() {
        doThrow(new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED)).when(retention).requireAccessible(firstAsset);
        var evidence = service.getByOrderId("order").items().getFirst().feedback().getFirst().evidence().getFirst();
        assertThat(evidence.fileName()).isEqualTo("original-name.jpg");
        assertThat(evidence.accessReason()).isEqualTo("RETENTION_EXPIRED");
        assertThat(evidence.thumbnailUrl()).isNull();
        assertThat(evidence.previewUrl()).isNull();
        verify(derivatives, never()).previewUrl(firstAsset);
    }

    @Test void feedbackWithoutSelectedMediaReturnsEmptyLists() {
        when(media.findByRequestItemIdIn(any())).thenReturn(List.of());
        assertThat(service.getByOrderId("order").items().getFirst().feedback())
                .allSatisfy(feedback -> assertThat(feedback.evidence()).isEmpty());
    }

    private ResultRevisionRequestItem feedback(String id, ResultRevisionCaseItem item, int roundNumber) {
        var round = new ResultReviewRound(); round.setRoundNumber(roundNumber);
        var request = new ResultRevisionRequest(); request.setId("request-" + id); request.setRound(round);
        var feedback = new ResultRevisionRequestItem(); feedback.setId(id); feedback.setRequest(request);
        feedback.setCaseItem(item); return feedback;
    }

    private ResultRevisionRequestItemMedia selected(ResultRevisionRequestItem feedback, String id, String fileName) {
        var asset = new MediaAsset(); asset.setId("asset-" + id);
        var evidence = new ResultReviewRoundEvidence(); evidence.setId(id); evidence.setMediaAsset(asset);
        evidence.setFileNameSnapshot(fileName); evidence.setMediaTypeSnapshot("IMAGE");
        when(derivatives.previewUrl(asset)).thenReturn("preview-" + id);
        when(derivatives.thumbnailUrl(asset)).thenReturn("thumbnail-" + id);
        var link = new ResultRevisionRequestItemMedia(); link.setRequestItem(feedback); link.setRoundEvidence(evidence);
        return link;
    }
}
