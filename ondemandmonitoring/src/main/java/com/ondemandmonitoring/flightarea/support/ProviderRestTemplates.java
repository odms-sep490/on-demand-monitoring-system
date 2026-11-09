package com.ondemandmonitoring.flightarea.support;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplates for the external data providers.
 *
 * <p>The JDK's {@code HttpURLConnection} only tries the first resolved address, so a single unreachable
 * address of a multi-address host (seen with Overpass) makes every call time out even though the host is
 * fine. Apache HttpClient tries each resolved address in turn, each with its own connect timeout.
 */
public final class ProviderRestTemplates {

    private ProviderRestTemplates() {
    }

    public static RestTemplate create(int connectTimeoutMs, int readTimeoutMs) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .build())
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
    }
}
