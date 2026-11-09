package com.ondemandmonitoring.finance.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.finance.config.VnPayProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpMethod;

class VnPayPaymentGatewayTest {
    private static final String SECRET = "sandbox-secret";
    private final VnPayProperties properties = new VnPayProperties(
            "TESTCODE", SECRET, "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
            "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction",
            "http://localhost:8080/api/payments/vnpay/return",
            "http://localhost:8080/api/payments/vnpay/ipn",
            "http://localhost:5173/#payment/result",
            "2.1.0", "pay", "other", "vn", 15, 3000, 5000);
    private final VnPayPaymentGateway gateway = new VnPayPaymentGateway(properties, new VnPaySigner(properties));

    @Test
    void createsSignedSandboxPaymentUrlUsingVndMinorUnits() {
        PaymentLinkResult result = gateway.createPaymentLink(
                new PaymentLinkCommand("123456", new java.math.BigDecimal("300000"),
                        "OMSS DEP 123456", "127.0.0.1"));

        assertThat(result.providerTransactionId()).isNull();
        assertThat(result.paymentUrl())
                .startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?")
                .contains("vnp_Amount=30000000")
                .contains("vnp_TxnRef=123456")
                .contains("vnp_SecureHash=");
    }

    @Test
    void verifiesSuccessfulSignedIpn() throws Exception {
        Map<String, String> payload = signedIpn("30000000");

        VerifiedWebhook result = gateway.verifyWebhook(payload);

        assertThat(result.transactionReference()).isEqualTo("123456");
        assertThat(result.amount()).isEqualByComparingTo("300000");
        assertThat(result.providerTransactionId()).isEqualTo("14587452");
        assertThat(result.successful()).isTrue();
    }

    @Test
    void rejectsTamperedIpn() throws Exception {
        Map<String, String> payload = signedIpn("30000000");
        payload.put("vnp_Amount", "40000000");

        assertThatThrownBy(() -> gateway.verifyWebhook(payload))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void queriesAndVerifiesAuthoritativeTransactionStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        VnPaySigner signer = new VnPaySigner(properties);
        VnPayPaymentGateway queryGateway = new VnPayPaymentGateway(properties, signer, restTemplate);
        String signedData = String.join("|", "response-1", "querydr", "00", "Success", "TESTCODE",
                "123456", "30000000", "NCB", "20261007090000", "14587452", "01", "00",
                "Query transaction 123456");
        String body = """
                {"vnp_ResponseId":"response-1","vnp_Command":"querydr","vnp_ResponseCode":"00",
                "vnp_Message":"Success","vnp_TmnCode":"TESTCODE","vnp_TxnRef":"123456",
                "vnp_Amount":"30000000","vnp_BankCode":"NCB","vnp_PayDate":"20261007090000",
                "vnp_TransactionNo":"14587452","vnp_TransactionType":"01","vnp_TransactionStatus":"00",
                "vnp_OrderInfo":"Query transaction 123456","vnp_SecureHash":"%s"}
                """.formatted(signer.signRaw(signedData));
        server.expect(requestTo(properties.queryUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        VerifiedWebhook result = queryGateway.queryPayment(
                new PaymentQueryCommand("123456", null, "20261007085000", "127.0.0.1"));

        assertThat(result.successful()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo("300000");
        assertThat(result.providerTransactionId()).isEqualTo("14587452");
        server.verify();
    }

    private Map<String, String> signedIpn(String amount) throws Exception {
        Map<String, String> payload = new TreeMap<>();
        payload.put("vnp_Amount", amount);
        payload.put("vnp_BankCode", "NCB");
        payload.put("vnp_PayDate", "20261006220000");
        payload.put("vnp_ResponseCode", "00");
        payload.put("vnp_TmnCode", "TESTCODE");
        payload.put("vnp_TransactionNo", "14587452");
        payload.put("vnp_TransactionStatus", "00");
        payload.put("vnp_TxnRef", "123456");
        String canonical = payload.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        payload.put("vnp_SecureHash", HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        return payload;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
