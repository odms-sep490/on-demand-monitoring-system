package com.ondemandmonitoring.description;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import org.junit.jupiter.api.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DescriptionAssistantServiceTest {
    DescriptionSessionRepository repository;
    AuthenticatedUserResolver users;
    DescriptionAssistantService service;
    ObjectMapper json=new ObjectMapper();
    DescriptionSession session;
    @BeforeEach void setup() throws Exception {
        repository=mock(DescriptionSessionRepository.class);users=mock(AuthenticatedUserResolver.class);
        service=new DescriptionAssistantService(repository,users,mock(ServiceRepository.class),mock(ChecklistDefinitionRepository.class),mock(ServiceChecklistRepository.class),json);
        when(users.getCurrentUserId()).thenReturn("customer");
        session=new DescriptionSession();session.setId("session");session.setOwnerId("customer");
        session.setServiceId("service");session.setStatus("READY_FOR_PREVIEW");session.setRevision(3);
        session.setState(new java.util.HashMap<>(java.util.Map.of("context",java.util.Map.of(),"answers",java.util.Map.of(),"output",java.util.Map.of())));
        when(repository.findById("session")).thenReturn(Optional.of(session));
        when(repository.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
    }
    @Test void crossCustomerReadAndConfirmAreBlocked() {
        when(users.getCurrentUserId()).thenReturn("other");
        assertThrows(ApiException.class,()->service.read("session"));
        assertThrows(ApiException.class,()->service.confirm("session",new DescriptionAssistantService.Confirmation(3,"description","purpose")));
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void staleRevisionDoesNotOverwrite() {
        assertThrows(ApiException.class,()->service.confirm("session",new DescriptionAssistantService.Confirmation(2,"description","purpose")));
        assertEquals("READY_FOR_PREVIEW",session.getStatus());
    }
    @Test void confirmPersistsEditedDraftsWithoutCreatingOrderOrPermit() {
        var result=service.confirm("session",new DescriptionAssistantService.Confirmation(3,"Edited description","Edited purpose"));
        assertEquals("CUSTOMER_CONFIRMED",result.status());assertEquals(4,result.revision());
        assertEquals("Edited purpose",json.valueToTree(session.getState()).path("confirmedFlightPurpose").asText());
        assertNotNull(result.confirmedAt());assertNull(session.getOrderId());
        assertEquals("Edited description",json.valueToTree(session.getState()).path("output").path("orderDescription").asText());
    }
    @Test void collectingSessionCannotBeConfirmed() {
        session.setStatus("COLLECTING_INFORMATION");
        assertThrows(ApiException.class,()->service.confirm("session",new DescriptionAssistantService.Confirmation(3,"description","purpose")));
    }
    @Test void confirmedPurposeBindsToCreatedOrderWithoutPermitMutation() {
        service.confirm("session",new DescriptionAssistantService.Confirmation(3,"description","purpose"));
        var order=new com.ondemandmonitoring.order.domain.Order();
        var catalog=new com.ondemandmonitoring.service.domain.Service();catalog.setId("service");
        order.setId("created-order");order.setService(catalog);order.setDescription("description");
        service.bindToOrder("session",order,new com.ondemandmonitoring.order.dto.request.OrderCreateRequest());
        assertEquals("purpose",order.getCustomerFlightPurposeDraft());
        assertEquals("session",order.getDescriptionAssistantSessionId());
        assertEquals("created-order",session.getOrderId());
        assertNull(order.getStaffReviewedFlightPurpose());
    }
    @Test void changedServiceCannotReuseConfirmedPurpose() {
        service.confirm("session",new DescriptionAssistantService.Confirmation(3,"description","purpose"));
        var order=new com.ondemandmonitoring.order.domain.Order();
        var catalog=new com.ondemandmonitoring.service.domain.Service();catalog.setId("changed-service");
        order.setService(catalog);order.setDescription("description");
        assertThrows(ApiException.class,()->service.bindToOrder("session",order,new com.ondemandmonitoring.order.dto.request.OrderCreateRequest()));
        assertNull(order.getCustomerFlightPurposeDraft());
    }
    @Test void generateDoesNotRequireAreaOrPriceCalculation() throws Exception {
        var services=mock(ServiceRepository.class);
        var definitions=mock(ChecklistDefinitionRepository.class);
        var assignments=mock(ServiceChecklistRepository.class);
        var catalog=new com.ondemandmonitoring.service.domain.Service();catalog.setId("service");catalog.setName("Monitoring");catalog.setIsActive(true);
        when(services.findById("service")).thenReturn(Optional.of(catalog));
        when(assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc("service")).thenReturn(java.util.List.of());
        var testService=new DescriptionAssistantService(repository,users,services,definitions,assignments,json);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/description/generate",exchange->{
            byte[] body="{\"status\":\"READY_FOR_PREVIEW\",\"followUpQuestions\":[],\"orderDescription\":\"Monitoring request\",\"flightPurposeDraft\":\"Collect imagery for monitoring\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(testService,"url","http://127.0.0.1:"+server.getAddress().getPort());
            org.springframework.test.util.ReflectionTestUtils.setField(testService,"token","test-only");
            var input=json.readValue("{\"serviceId\":\"service\",\"description\":\"Monitor construction\",\"mode\":\"GENERATE\",\"selection\":{\"serviceId\":\"service\",\"items\":[{\"content\":\"Customer requested focus\",\"requestedImageCount\":4}]}}",DescriptionAssistantService.Input.class);
            var result=testService.generate(input);
            assertEquals("READY_FOR_PREVIEW",result.status());
            assertEquals("CUSTOMER REQUESTED FOCUS",json.valueToTree(result.state()).path("context").path("checklists").get(0).path("content").asText().toUpperCase());
        } finally {server.stop(0);}
    }
}
