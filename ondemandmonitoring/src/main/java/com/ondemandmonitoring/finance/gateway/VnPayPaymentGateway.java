package com.ondemandmonitoring.finance.gateway;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.config.VnPayProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** VNPAY outbound adapter for payment URL creation, callback verification and QueryDR. */
@Component
public class VnPayPaymentGateway implements PaymentGateway, PaymentStatusGateway {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final VnPayProperties properties;
    private final VnPaySigner signer;
    private final RestTemplate restTemplate;

    @Autowired
    VnPayPaymentGateway(VnPayProperties properties, VnPaySigner signer) {
        this(properties, signer, buildRestTemplate(properties));
    }

    VnPayPaymentGateway(VnPayProperties properties, VnPaySigner signer, RestTemplate restTemplate) {
        this.properties = properties;
        this.signer = signer;
        this.restTemplate = restTemplate;
    }

    @Override
    public PaymentLinkResult createPaymentLink(PaymentLinkCommand command) {
        requireConfigured();
        if (command.amount() == null || command.amount().signum() <= 0
                || command.amount().stripTrailingZeros().scale() > 0) {
            throw new PaymentGatewayException("VNPAY requires a positive integer VND amount");
        }
        long encodedAmount;
        try {
            encodedAmount = command.amount().multiply(BigDecimal.valueOf(100)).longValueExact();
        } catch (ArithmeticException exception) {
            throw new PaymentGatewayException("VNPAY amount exceeds the supported numeric range", exception);
        }
        if (encodedAmount > 999_999_999_999L) {
            throw new PaymentGatewayException("VNPAY amount exceeds 12 numeric digits");
        }

        LocalDateTime createdAt = LocalDateTime.now(BUSINESS_ZONE);
        Map<String, String> fields = new TreeMap<>();
        fields.put("vnp_Version", properties.version());
        fields.put("vnp_Command", properties.command());
        fields.put("vnp_TmnCode", properties.tmnCode());
        fields.put("vnp_Amount", Long.toString(encodedAmount));
        fields.put("vnp_CurrCode", "VND");
        fields.put("vnp_TxnRef", command.transactionReference());
        fields.put("vnp_OrderInfo", command.description());
        fields.put("vnp_OrderType", properties.orderType());
        fields.put("vnp_Locale", properties.locale());
        fields.put("vnp_ReturnUrl", properties.returnUrl());
        fields.put("vnp_IpAddr", normalizeIp(command.clientIp()));
        fields.put("vnp_CreateDate", VNPAY_TIME.format(createdAt));
        fields.put("vnp_ExpireDate", VNPAY_TIME.format(createdAt.plusMinutes(properties.expireMinutes())));

        String signedData = signer.canonical(fields);
        return new PaymentLinkResult(null,
                properties.paymentUrl() + "?" + signedData + "&vnp_SecureHash=" + signer.sign(fields),
                fields.get("vnp_CreateDate"));
    }

    /**
     * Calls VNPAY QueryDR when the public IPN could not reach the application.
     * The response checksum is verified before it is converted to a domain result.
     */
    @Override
    public VerifiedWebhook queryPayment(PaymentQueryCommand command) {
        if (!properties.queryConfigured()) {
            throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, "VNPAY QueryDR is not configured");
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String createDate = VNPAY_TIME.format(LocalDateTime.now(BUSINESS_ZONE));
        String orderInfo = "Query transaction " + command.transactionReference();
        String ipAddress = normalizeIp(command.requestIp());

        Map<String, String> request = new LinkedHashMap<>();
        request.put("vnp_RequestId", requestId);
        request.put("vnp_Version", properties.version());
        request.put("vnp_Command", "querydr");
        request.put("vnp_TmnCode", properties.tmnCode());
        request.put("vnp_TxnRef", command.transactionReference());
        request.put("vnp_OrderInfo", orderInfo);
        request.put("vnp_TransactionNo", blankToEmpty(command.providerTransactionId()));
        request.put("vnp_TransactionDate", command.providerRequestDate());
        request.put("vnp_CreateDate", createDate);
        request.put("vnp_IpAddr", ipAddress);
        request.put("vnp_SecureHash", signer.signRaw(String.join("|", requestId, properties.version(),
                "querydr", properties.tmnCode(), command.transactionReference(), command.providerRequestDate(),
                createDate, ipAddress, orderInfo)));

        Map<?, ?> raw;
        try {
            RequestEntity<Map<String, String>> entity = RequestEntity.post(properties.queryUrl())
                    .contentType(MediaType.APPLICATION_JSON).body(request);
            raw = restTemplate.exchange(entity, Map.class).getBody();
        } catch (RestClientException exception) {
            throw new PaymentGatewayException("Unable to query VNPAY transaction status", exception);
        }
        if (raw == null) throw new PaymentGatewayException("VNPAY QueryDR returned an empty response");
        Map<String, String> response = raw.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                entry -> entry.getValue() == null ? "" : String.valueOf(entry.getValue()),
                (left, right) -> right, LinkedHashMap::new));
        verifyQueryResponse(response);

        String responseCode = required(response, "vnp_ResponseCode");
        if (!"00".equals(responseCode)) {
            throw new PaymentGatewayException("VNPAY QueryDR rejected the request: " + responseCode);
        }
        if (!properties.tmnCode().equals(required(response, "vnp_TmnCode"))
                || !command.transactionReference().equals(required(response, "vnp_TxnRef"))) {
            throw invalid("VNPAY QueryDR identity mismatch");
        }
        try {
            long encodedAmount = Long.parseLong(required(response, "vnp_Amount"));
            if (encodedAmount <= 0 || encodedAmount % 100 != 0) throw invalid("Invalid VNPAY amount");
            String transactionStatus = required(response, "vnp_TransactionStatus");
            return new VerifiedWebhook(command.transactionReference(), BigDecimal.valueOf(encodedAmount, 2), "VND",
                    required(response, "vnp_TransactionNo"), response.get("vnp_PayDate"), responseCode,
                    transactionStatus, "00".equals(transactionStatus));
        } catch (NumberFormatException exception) {
            throw invalid("Invalid numeric VNPAY QueryDR response");
        }
    }

    @Override
    public VerifiedWebhook verifyWebhook(Map<String, String> payload) {
        requireConfigured();
        if (payload == null) throw invalid("Missing VNPAY callback parameters");
        String suppliedSignature = payload.get("vnp_SecureHash");
        Map<String, String> signedFields = payload.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("vnp_"))
                .filter(entry -> !entry.getKey().equals("vnp_SecureHash"))
                .filter(entry -> !entry.getKey().equals("vnp_SecureHashType"))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> right, TreeMap::new));
        if (!signer.verify(signedFields, suppliedSignature)) throw invalid("Invalid VNPAY signature");
        if (!properties.tmnCode().equals(signedFields.get("vnp_TmnCode"))) {
            throw invalid("Unexpected VNPAY terminal code");
        }

        try {
            long encodedAmount = Long.parseLong(required(signedFields, "vnp_Amount"));
            if (encodedAmount <= 0 || encodedAmount % 100 != 0) throw invalid("Invalid VNPAY amount");
            String reference = required(signedFields, "vnp_TxnRef");
            if (!reference.matches("[A-Za-z0-9]{1,100}")) throw invalid("Invalid VNPAY transaction reference");
            String responseCode = required(signedFields, "vnp_ResponseCode");
            String transactionStatus = required(signedFields, "vnp_TransactionStatus");
            String currency = signedFields.getOrDefault("vnp_CurrCode", "VND");
            boolean successful = "00".equals(responseCode) && "00".equals(transactionStatus);
            return new VerifiedWebhook(reference, BigDecimal.valueOf(encodedAmount, 2), currency,
                    required(signedFields, "vnp_TransactionNo"), signedFields.get("vnp_PayDate"),
                    responseCode, transactionStatus, successful);
        } catch (NumberFormatException exception) {
            throw invalid("Invalid numeric VNPAY parameter");
        }
    }

    private String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) throw invalid("Missing " + name);
        return value;
    }

    private String normalizeIp(String value) {
        if (value == null || value.isBlank() || value.length() > 45) return "127.0.0.1";
        if (value.equals("::1") || value.equals("0:0:0:0:0:0:0:1")) return "127.0.0.1";
        return value;
    }

    private void verifyQueryResponse(Map<String, String> response) {
        String signedData = String.join("|",
                value(response, "vnp_ResponseId"), value(response, "vnp_Command"),
                value(response, "vnp_ResponseCode"), value(response, "vnp_Message"),
                value(response, "vnp_TmnCode"), value(response, "vnp_TxnRef"),
                value(response, "vnp_Amount"), value(response, "vnp_BankCode"),
                value(response, "vnp_PayDate"), value(response, "vnp_TransactionNo"),
                value(response, "vnp_TransactionType"), value(response, "vnp_TransactionStatus"),
                value(response, "vnp_OrderInfo"));
        String signedDataWithPromotions = signedData + "|" + value(response, "vnp_PromotionCode")
                + "|" + value(response, "vnp_PromotionAmount");
        String signature = response.get("vnp_SecureHash");
        if (!signer.verifyRaw(signedData, signature)
                && !signer.verifyRaw(signedDataWithPromotions, signature)) {
            throw invalid("Invalid VNPAY QueryDR signature");
        }
    }

    private String value(Map<String, String> values, String key) {
        return values.getOrDefault(key, "");
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static RestTemplate buildRestTemplate(VnPayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.queryConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.queryReadTimeoutMs()));
        return new RestTemplate(factory);
    }

    private ApiException invalid(String message) {
        return new ApiException(ErrorCode.PAYMENT_WEBHOOK_INVALID, message);
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, "VNPAY is not configured");
        }
    }
}
