package com.ondemandmonitoring.finance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.finance.service.IVnPayCallbackService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VnPayWebhookControllerTest {
    private final IVnPayCallbackService service = mock(IVnPayCallbackService.class);
    private final VnPayWebhookController controller = new VnPayWebhookController(service);

    @Test
    void delegatesIpnProtocolHandlingToServiceLayer() {
        when(service.handleIpn(anyMap())).thenReturn(Map.of("RspCode", "04", "Message", "Invalid amount"));

        assertThat(controller.ipn(Map.of()).get("RspCode")).isEqualTo("04");
    }

    @Test
    void delegatesReturnRedirectConstructionToServiceLayer() {
        when(service.buildReturnRedirect(anyMap()))
                .thenReturn("http://localhost:5173/#payment/result?transactionReference=123456");

        assertThat(controller.returnFromVnPay(Map.of()).getUrl())
                .isEqualTo("http://localhost:5173/#payment/result?transactionReference=123456");
    }
}
