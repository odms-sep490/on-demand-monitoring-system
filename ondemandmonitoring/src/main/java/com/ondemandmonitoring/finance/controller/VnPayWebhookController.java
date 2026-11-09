package com.ondemandmonitoring.finance.controller;

import com.ondemandmonitoring.finance.service.IVnPayCallbackService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/** Public VNPAY HTTP adapter. Signature and state logic live in the service layer. */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class VnPayWebhookController {
    private final IVnPayCallbackService callbackService;

    @GetMapping("/vnpay/ipn")
    public Map<String, String> ipn(@RequestParam Map<String, String> parameters) {
        return callbackService.handleIpn(parameters);
    }

    @GetMapping("/vnpay/return")
    public RedirectView returnFromVnPay(@RequestParam Map<String, String> parameters) {
        return new RedirectView(callbackService.buildReturnRedirect(parameters));
    }
}
