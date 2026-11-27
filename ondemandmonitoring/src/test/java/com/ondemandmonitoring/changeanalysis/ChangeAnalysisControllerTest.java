package com.ondemandmonitoring.changeanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ondemandmonitoring.media.service.ICustomerMediaService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ChangeAnalysisControllerTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final ICustomerMediaService media = mock(ICustomerMediaService.class);
    private final Authentication auth = mock(Authentication.class);

    @Test
    void rejectsInvalidImageWithoutCreatingJob() throws Exception {
        var worker = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        worker.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        worker.start();
        try {
            var controller = new ChangeAnalysisController(db, transaction, media,
                    "http://127.0.0.1:" + worker.getAddress().getPort(), "test");
            var fake = new MockMultipartFile("before", "fake.png", "image/png", new byte[] {1, 2, 3});
            var error = assertThrows(ResponseStatusException.class,
                    () -> controller.create(auth, UUID.randomUUID(), fake, fake, null, null));
            assertEquals("UNSUPPORTED_INPUT", error.getReason());
            verifyNoInteractions(db, transaction, media);
        } finally {
            worker.stop(0);
        }
    }

    @Test
    void failsClosedWhenWorkerIsNotConfigured() {
        var controller = new ChangeAnalysisController(db, transaction, media, "", "");
        var error = assertThrows(ResponseStatusException.class,
                () -> controller.create(auth, UUID.randomUUID(), null, null, "before", "after"));
        assertEquals("NOT_CONFIGURED", error.getReason());
        verifyNoInteractions(db, transaction, media);
    }
    @Test
    void securityAppliesOnlyToCustomerEndpoints() {
        var type = ChangeAnalysisController.class;
        org.junit.jupiter.api.Assertions.assertNull(type.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class));
        for (var method : type.getDeclaredMethods()) {
            boolean endpoint = method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class);
            var security = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
            if (endpoint) {
                org.junit.jupiter.api.Assertions.assertNotNull(security, method.getName());
                assertEquals("hasRole('CUSTOMER')", security.value());
            } else {
                org.junit.jupiter.api.Assertions.assertNull(security, method.getName());
            }
        }
    }
}
