package com.ondemandmonitoring.finance.gateway;

import com.ondemandmonitoring.finance.config.VnPayProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class VnPaySigner {
    private final VnPayProperties properties;

    public VnPaySigner(VnPayProperties properties) {
        this.properties = properties;
    }

    public String sign(Map<String, String> parameters) {
        return signRaw(canonical(parameters));
    }

    /** Signs the pipe-delimited payload required by VNPAY QueryDR. */
    public String signRaw(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(properties.hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new PaymentGatewayException("Unable to calculate VNPAY signature", exception);
        }
    }

    public boolean verifyRaw(String data, String receivedSignature) {
        if (receivedSignature == null || receivedSignature.isBlank()) return false;
        return MessageDigest.isEqual(signRaw(data).getBytes(StandardCharsets.US_ASCII),
                receivedSignature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public boolean verify(Map<String, String> parameters, String receivedSignature) {
        if (receivedSignature == null || receivedSignature.isBlank()) return false;
        byte[] expected = sign(parameters).getBytes(StandardCharsets.US_ASCII);
        byte[] received = receivedSignature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, received);
    }

    public String canonical(Map<String, String> parameters) {
        return new TreeMap<>(parameters).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
