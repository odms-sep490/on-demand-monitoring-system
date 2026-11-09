package com.ondemandmonitoring.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

@Component
public class RefreshTokenCookieService {

    private static final String COOKIE_NAME = "OMSS_REFRESH_TOKEN";

    @Value("${auth.refresh-token-cookie.secure:true}")
    private boolean secure;

    @Value("${auth.refresh-token-cookie.max-age-seconds:2592000}")
    private int maxAgeSeconds;

    @Value("${auth.refresh-token-cookie.same-site:Lax}")
    private String sameSite;

    public void write(HttpServletResponse response, String refreshToken, String username, boolean rememberMe) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(refreshToken + "::" + username, rememberMe ? maxAgeSeconds : -1));
    }

    public Optional<RefreshToken> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .map(value -> value.split("::", 2))
                .filter(parts -> parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank())
                .map(parts -> new RefreshToken(parts[0], parts[1]))
                .findFirst();
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0));
    }

    private String cookie(String value, int maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(maxAge)
                .sameSite(sameSite)
                .build()
                .toString();
    }

    public record RefreshToken(String token, String username) {
    }
}
