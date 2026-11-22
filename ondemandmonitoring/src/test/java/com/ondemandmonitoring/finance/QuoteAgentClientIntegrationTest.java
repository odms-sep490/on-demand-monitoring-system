package com.ondemandmonitoring.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.finance.service.impl.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuoteAgentClientIntegrationTest {
    @Test void requestHelpUsesInternalAuthenticationAndServerContextOnly() throws Exception {
        var received = new AtomicReference<String>();
        var header = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/request-assistant/messages", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            header.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body = "{\"answer\":\"Chi phí là tạm tính.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var json = new ObjectMapper();
            var client = new QuoteAgentClient(mock(SmartQuoteService.class), json,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "internal-test");
            var answer = client.explainRequest("Vì sao?", java.util.Map.of("total", 500000), "customer-1");
            assertEquals("READY", answer.status());
            assertEquals("internal-test", header.get());
            var payload = json.readTree(received.get());
            assertEquals(500000, payload.path("context").path("total").asInt());
            assertFalse(payload.has("authorization"));
            assertEquals("BUSY", client.explainRequest("Vì sao?", java.util.Map.of(), "customer-1").status());
        } finally { server.stop(0); }
    }
    @Test void forwardsOnlyBoundReadContextAndInternalAuthentication() throws Exception {
        var service=mock(SmartQuoteService.class);
        var current=mock(SmartQuoteService.Analysis.class);
        when(current.revision()).thenReturn("r1");
        when(service.read("o","q",true)).thenReturn(current);
        var received=new AtomicReference<String>();
        var header=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/quote-assistant/messages",exchange->{
            received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            header.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] body="{\"answer\":\"Giá dựa trên hạng mục đã chọn.\",\"revision\":\"r1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var json=new ObjectMapper();
            var client=new QuoteAgentClient(service,json,"http://127.0.0.1:"+server.getAddress().getPort(),"test-internal-token");
            var result=client.ask("o","q",true,new QuoteAgentClient.Message("Giải thích báo giá","r1"),"Bearer test-customer");
            assertEquals("READY",result.status());
            assertEquals("test-internal-token",header.get());
            var request=json.readTree(received.get());
            assertEquals("/api/customer/orders/o/quotes/q",request.path("path").asText());
            assertEquals("Bearer test-customer",request.path("authorization").asText());
            verify(service,times(2)).read("o","q",true);
        } finally {server.stop(0);}
    }
    @Test void staleQuoteNeverContactsRuntime() {
        var service=mock(SmartQuoteService.class);var current=mock(SmartQuoteService.Analysis.class);
        when(current.revision()).thenReturn("r2");when(service.read("o","q",true)).thenReturn(current);
        var client=new QuoteAgentClient(service,new ObjectMapper(),"http://127.0.0.1:1","test-token");
        assertThrows(RuntimeException.class,()->client.ask("o","q",true,new QuoteAgentClient.Message("Giải thích","r1"),"Bearer test"));
    }
    @Test void permissionFailureNeverContactsRuntime() {
        var service=mock(SmartQuoteService.class);
        when(service.read("other","q",true)).thenThrow(new IllegalStateException("denied"));
        var client=new QuoteAgentClient(service,new ObjectMapper(),"http://127.0.0.1:1","test-token");
        assertThrows(IllegalStateException.class,()->client.ask("other","q",true,new QuoteAgentClient.Message("Giải thích","r1"),"Bearer test"));
    }
}
