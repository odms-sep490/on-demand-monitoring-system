package com.ondemandmonitoring.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenCookieServiceTest {
    private RefreshTokenCookieService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenCookieService();
        ReflectionTestUtils.setField(service, "secure", true);
        ReflectionTestUtils.setField(service, "maxAgeSeconds", 3600);
        ReflectionTestUtils.setField(service, "sameSite", "Lax");
    }

    @Test
    void writeUsesSecureHttpOnlyCookie() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);

        service.write(response, "refresh-token", "cognito-user", true);

        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), header.capture());
        assertTrue(header.getValue().contains("OMSS_REFRESH_TOKEN="));
        assertTrue(header.getValue().contains("HttpOnly"));
        assertTrue(header.getValue().contains("Secure"));
        assertTrue(header.getValue().contains("SameSite=Lax"));
        assertTrue(header.getValue().contains("Max-Age=3600"));
    }

    @Test
    void uncheckedRememberMeUsesSessionCookie() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);

        service.write(response, "refresh-token", "cognito-user", false);

        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), header.capture());
        assertTrue(header.getValue().contains("HttpOnly"));
        assertTrue(header.getValue().contains("Secure"));
        assertTrue(header.getValue().contains("SameSite=Lax"));
        assertFalse(header.getValue().contains("Max-Age"));
        assertFalse(header.getValue().contains("Expires"));
    }

    @Test
    void clearExpiresTheCookie() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);
        service.clear(response);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), header.capture());
        assertTrue(header.getValue().contains("Max-Age=0"));
    }

    @Test
    void readReturnsRefreshTokenAndCognitoUsername() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("OMSS_REFRESH_TOKEN", "refresh-token::cognito-user")
        });

        var cookie = service.read(request).orElseThrow();

        assertEquals("refresh-token", cookie.token());
        assertEquals("cognito-user", cookie.username());
    }

    @Test
    void malformedCookieIsRejected() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("OMSS_REFRESH_TOKEN", "invalid")});

        assertTrue(service.read(request).isEmpty());
    }
}
