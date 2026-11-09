package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.ResultRevisionCase;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository;
import com.ondemandmonitoring.support.domain.SupportCustomerMessage;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.enums.SupportTicketKind;
import com.ondemandmonitoring.support.repository.*;
import com.ondemandmonitoring.support.service.ISupportAuthorizationService;
import com.ondemandmonitoring.support.service.impl.SupportTicketService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.user.service.IStaffDirectoryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultRevisionTicketPresentationTest {
    @Test
    void resultRevisionDoesNotLoadOldConversationsWhileGeneralTicketsKeepTheirReplies() {
        var tickets = mock(SupportCustomerTicketRepository.class);
        var messages = mock(SupportCustomerMessageRepository.class);
        var service = new SupportTicketService(tickets, messages,
                mock(SupportFaqFeedbackRepository.class), mock(SupportFaqArticleRepository.class),
                mock(AuthenticatedUserResolver.class), mock(ISupportAuthorizationService.class), mock(IStaffDirectoryService.class), mock(ResultRevisionCaseRepository.class));
        var ticket = new SupportCustomerTicket();
        ticket.setId("ticket"); ticket.setTicketKind(SupportTicketKind.RESULT_REVISION);
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));

        assertThat(service.getTicketById("ticket").getMessages()).isEmpty();
        verifyNoInteractions(messages);

        ticket.setTicketKind(SupportTicketKind.GENERAL);
        var reply = new SupportCustomerMessage(); reply.setId("reply"); reply.setContent("Keep my reply");
        when(messages.findByTicketIdOrderByCreatedAtAsc("ticket")).thenReturn(List.of(reply));
        assertThat(service.getTicketById("ticket").getMessages().getFirst().getContent()).isEqualTo("Keep my reply");
    }

    @Test
    void creatingResultRevisionDoesNotWriteAnOpeningMessage() {
        var tickets = mock(SupportCustomerTicketRepository.class);
        var messages = mock(SupportCustomerMessageRepository.class);
        var service = new SupportTicketService(tickets, messages,
                mock(SupportFaqFeedbackRepository.class), mock(SupportFaqArticleRepository.class),
                mock(AuthenticatedUserResolver.class), mock(ISupportAuthorizationService.class), mock(IStaffDirectoryService.class), mock(ResultRevisionCaseRepository.class));
        when(tickets.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var customer = new com.ondemandmonitoring.user.domain.User();
        customer.setId("customer"); customer.setFullName("Customer");

        var ticket = service.createResultRevisionTicket(customer, "order", "Customer note");

        assertThat(ticket.getTicketKind()).isEqualTo(SupportTicketKind.RESULT_REVISION);
        assertThat(ticket.getDescription()).isEqualTo("Customer note");
        verifyNoInteractions(messages);
    }

    @Test
    void outOfScopeStillWaitsForCustomerWithoutWritingAChatMessage() {
        var tickets = mock(SupportCustomerTicketRepository.class);
        var messages = mock(SupportCustomerMessageRepository.class);
        var service = new SupportTicketService(tickets, messages,
                mock(SupportFaqFeedbackRepository.class), mock(SupportFaqArticleRepository.class),
                mock(AuthenticatedUserResolver.class), mock(ISupportAuthorizationService.class), mock(IStaffDirectoryService.class), mock(ResultRevisionCaseRepository.class));
        var ticket = new SupportCustomerTicket();
        ticket.setId("ticket"); ticket.setTicketKind(SupportTicketKind.RESULT_REVISION);
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));

        service.markResultRevisionAwaitingCustomer("ticket");

        assertThat(ticket.getStatus()).isEqualTo("WAITING_FOR_CUSTOMER");
        verify(tickets).save(ticket);
        verifyNoInteractions(messages);
    }

    @Test
    void rejectsChatRepliesOnResultRevisionTickets() {
        var tickets = mock(SupportCustomerTicketRepository.class);
        var messages = mock(SupportCustomerMessageRepository.class);
        var service = new SupportTicketService(tickets, messages,
                mock(SupportFaqFeedbackRepository.class), mock(SupportFaqArticleRepository.class),
                mock(AuthenticatedUserResolver.class), mock(ISupportAuthorizationService.class), mock(IStaffDirectoryService.class), mock(ResultRevisionCaseRepository.class));
        var ticket = new SupportCustomerTicket(); ticket.setTicketKind(SupportTicketKind.RESULT_REVISION);
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.addMessage("ticket", new com.ondemandmonitoring.support.dto.AddMessageRequest()))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(messages);
    }

    @Test
    void preventsManualTicketClosureWhileChecklistCaseIsActive() {
        var tickets = mock(SupportCustomerTicketRepository.class);
        var messages = mock(SupportCustomerMessageRepository.class);
        var caseRepository = mock(ResultRevisionCaseRepository.class);
        var service = new SupportTicketService(tickets, messages,
                mock(SupportFaqFeedbackRepository.class), mock(SupportFaqArticleRepository.class),
                mock(AuthenticatedUserResolver.class), mock(ISupportAuthorizationService.class), mock(IStaffDirectoryService.class), caseRepository);
        var ticket = new SupportCustomerTicket();
        ticket.setId("ticket");
        ticket.setTicketKind(SupportTicketKind.RESULT_REVISION);
        var activeCase = new ResultRevisionCase();
        activeCase.setActive(true);
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));
        when(caseRepository.findByTicketId("ticket")).thenReturn(Optional.of(activeCase));
        var request = new com.ondemandmonitoring.support.dto.UpdateTicketStatusRequest();
        request.setStatus("CLOSED");

        assertThatThrownBy(() -> service.updateTicketStatus("ticket", request))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_DELIVERY_STATE);
        verify(tickets, never()).save(any());
    }
}
