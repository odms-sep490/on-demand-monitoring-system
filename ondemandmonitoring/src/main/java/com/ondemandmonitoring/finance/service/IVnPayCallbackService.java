package com.ondemandmonitoring.finance.service;

import java.util.Map;

/**
 * Translates VNPAY's public callback protocol into application-service calls.
 * This keeps response-code mapping and redirect construction out of controllers.
 */
public interface IVnPayCallbackService {
    Map<String, String> handleIpn(Map<String, String> parameters);
    String buildReturnRedirect(Map<String, String> parameters);
}
