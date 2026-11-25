package com.ondemandmonitoring.inspectionreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ReportWorkerAvailabilityTest {
    private ReportGenerationJob worker(int port) {
        var job = new ReportGenerationJob(null, new ObjectMapper(), null, null, null, null);
        ReflectionTestUtils.setField(job, "worker", "http://localhost:" + port);
        ReflectionTestUtils.setField(job, "token", "qa-only-token");
        return job;
    }
    @Test void disconnectedWorkerHasActionableServiceUnavailable() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var exception = assertThrows(ResponseStatusException.class, () -> worker(port).call(Map.of()));
        assertEquals(503, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("Start AI Services"));
    }
    @Test void rejectedOutputIsNotReportedAsDisconnectedWorker() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/inspection-reports/generate", exchange -> {
            exchange.sendResponseHeaders(422, -1); exchange.close();
        });
        server.start();
        try {
            var exception = assertThrows(ResponseStatusException.class,
                    () -> worker(server.getAddress().getPort()).call(Map.of()));
            assertEquals(503, exception.getStatusCode().value());
            assertTrue(exception.getReason().contains("đề xuất hợp lệ"));
            assertFalse(exception.getReason().contains("Start AI Services"));
        } finally { server.stop(0); }
    }
}
