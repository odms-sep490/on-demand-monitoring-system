package com.ondemandmonitoring.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.dto.response.OrderDraftValidationResponse;
import com.ondemandmonitoring.order.service.IOrderService;
import com.ondemandmonitoring.service.repository.*;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderConversationServiceTest {
    OrderConversationRepository sessions=mock(OrderConversationRepository.class);
    AuthenticatedUserResolver users=mock(AuthenticatedUserResolver.class);
    IOrderService orders=mock(IOrderService.class);
    ServiceRepository services=mock(ServiceRepository.class);
    ServiceChecklistRepository checklists=mock(ServiceChecklistRepository.class);
    OrderConversationService useCase=new OrderConversationService(sessions,users,services,checklists,
        mock(ServiceDeliverableRepository.class),mock(PreferredTimeRepository.class),orders,new ObjectMapper().findAndRegisterModules());
    OrderConversationSession session;
    @BeforeEach void setup() {
        when(users.getCurrentUserId()).thenReturn("customer");
        session=new OrderConversationSession();session.setId("session");session.setOwnerId("customer");session.setRevision(3);
        session.setExpiresAt(Instant.now().plusSeconds(3600));
        session.setState(new LinkedHashMap<>(Map.of("messages",List.of(),"confirmedFields",Map.of("serviceId","old","checklistItems",List.of("old")),"pendingChanges",Map.of())));
        when(sessions.findById("session")).thenReturn(Optional.of(session));
        when(sessions.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
    }
    @Test void ownershipAndExpiryApplyToReads() {
        when(users.getCurrentUserId()).thenReturn("other");assertThrows(ApiException.class,()->useCase.read("session"));
        when(users.getCurrentUserId()).thenReturn("customer");session.setExpiresAt(Instant.now().minusSeconds(1));
        assertThrows(ApiException.class,()->useCase.read("session"));verifyNoInteractions(orders);
    }
    @Test void modelOutputRejectionIsNotReportedAsNetworkFailure() throws Exception {
        // Protocol error fixture only; real-LLM release cases use the real worker.
        var stub=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        stub.createContext("/conversation/turn",exchange->{
            byte[] body="{\"detail\":\"ORDER_AGENT_OUTPUT_INVALID\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        stub.start();
        try {
            ReflectionTestUtils.setField(useCase,"workerUrl","http://127.0.0.1:"+stub.getAddress().getPort());
            ReflectionTestUtils.setField(useCase,"workerToken","unit-test-only");
            ApiException error=assertThrows(ApiException.class,()->ReflectionTestUtils.invokeMethod(useCase,"infer",Map.of()));
            assertTrue(error.getMessage().contains("chưa hiểu đúng"));
            assertFalse(error.getMessage().contains("Chưa kết nối"));
            verifyNoInteractions(orders);verify(sessions,never()).saveAndFlush(any());
        } finally {stub.stop(0);}
    }
    @Test void staleRevisionNeverOverwritesConfirmedFields() {
        assertThrows(ApiException.class,()->useCase.confirm("session",2));verify(sessions,never()).saveAndFlush(any());
    }
    @Test void duplicateMessageReturnsPersistedTurnWithoutInference() {
        session.getState().put("messages",List.of(Map.of("requestId","request-1","role","user","content","goal")));
        var result=useCase.message("session",new OrderConversationService.Message(0,"request-1","goal"));
        assertEquals(3,result.revision());verify(sessions,never()).saveAndFlush(any());
    }
    @Test void serviceCorrectionClearsDependentChecklistOnlyAfterConfirmation() {
        var selected=com.ondemandmonitoring.service.domain.Service.builder().isActive(true).build();selected.setId("new");
        when(services.findById("new")).thenReturn(Optional.of(selected));
        session.getState().put("pendingChanges",Map.of("serviceId","new"));
        var result=useCase.confirm("session",3);
        var confirmed=(Map<?,?>)result.state().get("confirmedFields");
        assertEquals("new",confirmed.get("serviceId"));assertFalse(confirmed.containsKey("checklistItems"));assertEquals(4,result.revision());
        verifyNoInteractions(orders);
    }
    @Test void validationUsesSharedBoundaryWithoutCallingCreateOrder() {
        var draft=new OrderCreateRequest();draft.setPreferredDateFrom(java.time.LocalDate.of(2026,12,2));
        when(orders.validateDraftForReview(draft)).thenReturn(new OrderDraftValidationResponse(true,draft,Map.of()));
        var result=useCase.validate("session",new OrderConversationService.Validation(3,draft));
        assertEquals(true,result.state().get("readyForReview"));assertNotNull(result.state().get("canonicalDraft"));
        assertEquals("2026-12-02",((Map<?,?>)result.state().get("canonicalDraft")).get("preferredDateFrom"));
        verify(orders,never()).createOrder(any());
    }
    @Test void invalidValidationClearsOldReadiness() {
        session.getState().put("canonicalDraft",Map.of("serviceId","old"));
        var draft=new OrderCreateRequest();when(orders.validateDraftForReview(draft)).thenReturn(new OrderDraftValidationResponse(false,null,Map.of("serviceId","required")));
        var result=useCase.validate("session",new OrderConversationService.Validation(3,draft));
        assertEquals(false,result.state().get("readyForReview"));assertFalse(result.state().containsKey("canonicalDraft"));
    }
    @Test void pendingProposalCannotBeDeclaredReady() {
        session.getState().put("pendingChanges",Map.of("description","Unconfirmed proposal"));
        assertThrows(ApiException.class,()->useCase.validate("session",new OrderConversationService.Validation(3,new OrderCreateRequest())));
        verifyNoInteractions(orders);
    }
    @Test void invalidEnumCannotBeConfirmedEvenIfWorkerReturnsIt() {
        session.getState().put("pendingChanges",Map.of("monitoringRelationship","INVENTED"));
        assertThrows(ApiException.class,()->useCase.confirm("session",3));
        verify(sessions,never()).saveAndFlush(any());
    }
    @Test void checklistFromAnotherServiceCannotBeConfirmed() {
        var foreignService=com.ondemandmonitoring.service.domain.Service.builder().isActive(true).build();foreignService.setId("foreign");
        var definition=new com.ondemandmonitoring.checklist.domain.ChecklistDefinition();definition.setId("c");definition.setIsActive(true);definition.setVersion(2L);
        var link=new com.ondemandmonitoring.checklist.domain.ServiceChecklist();link.setService(foreignService);link.setChecklist(definition);
        when(checklists.findAll()).thenReturn(List.of(link));
        session.getState().put("pendingChanges",Map.of("checklistItems",List.of(Map.of("sourceChecklistId","c","expectedChecklistVersion",2,
            "requestedImageCount",20,"requestedVideoCount",0))));
        assertThrows(ApiException.class,()->useCase.confirm("session",3));
        verify(sessions,never()).saveAndFlush(any());verifyNoInteractions(orders);
    }
    @Test void speechProxyAuthenticatesAndPreservesWorkerFailureReason() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/conversation/transcribe",exchange->{
            assertEquals("internal-test-only",exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body="{\"detail\":\"ASR_TIMEOUT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(504,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            ReflectionTestUtils.setField(useCase,"workerUrl","http://127.0.0.1:"+server.getAddress().getPort());
            ReflectionTestUtils.setField(useCase,"workerToken","internal-test-only");
            var audio=new org.springframework.mock.web.MockMultipartFile("audio","sample.webm","audio/webm",new byte[]{1,2});
            var error=assertThrows(ApiException.class,()->useCase.transcribe(audio));
            assertTrue(error.getMessage().contains("quá lâu"));
        }finally{server.stop(0);}
    }
    @Test void inventedServiceCannotBeConfirmed() {
        session.getState().put("pendingChanges",Map.of("serviceId","invented"));
        when(services.findById("invented")).thenReturn(Optional.empty());
        assertThrows(ApiException.class,()->useCase.confirm("session",3));
        verify(sessions,never()).saveAndFlush(any());
    }
    @Test void inventedChecklistCannotBeConfirmed() {
        session.getState().put("pendingChanges",Map.of("checklistItems",List.of(Map.of("sourceChecklistId","invented","expectedChecklistVersion",1,"requestedImageCount",4,"requestedVideoCount",0))));
        when(checklists.findAll()).thenReturn(List.of());
        assertThrows(ApiException.class,()->useCase.confirm("session",3));
        verify(sessions,never()).saveAndFlush(any());
    }
}
