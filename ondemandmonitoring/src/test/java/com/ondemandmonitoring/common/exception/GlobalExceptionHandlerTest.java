package com.ondemandmonitoring.common.exception;

import com.ondemandmonitoring.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/media/123/file");
    }

    @Test
    void testNormalJsonApiException() {
        Exception ex = new Exception("Normal error");
        when(response.isCommitted()).thenReturn(false);

        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpectedException(ex, request, response);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR.name(), res.getBody().getCode());
    }

    @Test
    void testConfirmedClientAbortException() {
        ClientAbortException ex = new ClientAbortException("Broken pipe");
        when(response.isCommitted()).thenReturn(false);

        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpectedException(ex, request, response);

        assertNull(res); // Should return null to avoid HttpMessageNotWritableException
    }

    @Test
    void testGenericIOExceptionNotClientDisconnect() {
        IOException ex = new IOException("Some other IO error");
        when(response.isCommitted()).thenReturn(false);

        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpectedException(ex, request, response);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
    }

    @Test
    void testAlreadyCommittedResponse() {
        Exception ex = new Exception("Error during streaming");
        when(response.isCommitted()).thenReturn(true);

        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpectedException(ex, request, response);

        assertNull(res);
    }

    @Test
    void accessDeniedReturnsForbiddenInsteadOfInternalServerError() throws Exception {
        denialMvc().perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()))
                .andExpect(jsonPath("$.message").value(ErrorCode.ACCESS_DENIED.getMessage()))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void methodAuthorizationDeniedUsesAccessDeniedHandler() throws Exception {
        denialMvc().perform(get("/test/authorization-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void accessDeniedLogsRequestAndAuthoritiesWithoutCredentials() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("cognito-sub", "secret-token",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        when(request.getRequestURI()).thenReturn("/api/support-tickets/analytics");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleAccessDeniedException(new AccessDeniedException("Access Denied"), request);
            var event = appender.list.getFirst();
            assertEquals(Level.WARN, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("method=GET"));
            assertTrue(message.contains("path=/api/support-tickets/analytics"));
            assertTrue(message.contains("principal=cognito-sub"));
            assertTrue(message.contains("authorities=[ROLE_STAFF]"));
            assertFalse(message.contains("secret-token"));
            assertNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private MockMvc denialMvc() {
        return MockMvcBuilders.standaloneSetup(new DeniedController())
                .setControllerAdvice(handler)
                .build();
    }

    @RestController
    static class DeniedController {
        @GetMapping("/test/access-denied")
        public void accessDenied() {
            throw new AccessDeniedException("Internal authorization details");
        }

        @GetMapping("/test/authorization-denied")
        public void authorizationDenied() {
            throw new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));
        }
    }
}
