package com.ondemandmonitoring.aiops.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Wrap security as well as MVC so pre-controller rejections are audited without requested resource IDs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ChecklistVisionAuditFilter extends OncePerRequestFilter {
    private final ChecklistVisionRuntime runtime;

    public ChecklistVisionAuditFilter(ChecklistVisionRuntime runtime) { this.runtime = runtime; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return true;
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/admin/ai-ops/checklist-vision/playground")
                && !path.matches("/api/missions/[^/]+/checklist-executions/[^/]+/evidence/[^/]+/content-assessment");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var context = ChecklistVisionRuntime.RequestContext.anonymous();
        request.setAttribute(ChecklistVisionRuntime.REQUEST_CONTEXT_ATTRIBUTE, context);
        response.setHeader("X-Correlation-ID", context.traceId());
        // RequestContextHolder may not yet be installed this early in the servlet chain.
        try (var scope = runtime.openRequest(context, request.getRequestURI().endsWith("/playground"))) {
            try {
                chain.doFilter(request, response);
                scope.finishHttp(response.getStatus());
            } catch (IOException | ServletException | RuntimeException failure) {
                scope.failed(failure);
                throw failure;
            } finally {
                request.setAttribute(ChecklistVisionRuntime.AUDIT_RECORDED_ATTRIBUTE, true);
            }
        }
    }
}
