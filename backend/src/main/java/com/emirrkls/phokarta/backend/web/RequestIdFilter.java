package com.emirrkls.phokarta.backend.web;

import com.emirrkls.phokarta.backend.observability.PlaceDetailObservation;
import com.emirrkls.phokarta.backend.observability.PlaceDetailObservability;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private PlaceDetailObservability placeDetailObservability;

    @Autowired
    public void setPlaceDetailObservability(PlaceDetailObservability placeDetailObservability) {
        this.placeDetailObservability = placeDetailObservability;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        long started = System.nanoTime();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        PlaceDetailObservation observation = null;
        if (placeDetailObservability != null) {
            try {
                observation = placeDetailObservability.begin(request, requestId, started);
            } catch (RuntimeException ignored) {
                // A diagnostic failure must never change the request contract.
            }
        }
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            if (observation != null) observation.failure(failure);
            throw failure;
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            try {
                if (observation != null) {
                    try {
                        observation.serializationEnd(); // fallback when MVC exits exceptionally
                        placeDetailObservability.finish(observation, response.getStatus(), response.isCommitted());
                    } catch (RuntimeException ignored) {
                        // Diagnostic logging/metrics must not replace a product response or exception.
                    }
                }
                log.atInfo()
                        .addKeyValue("http.request.method", request.getMethod())
                        .addKeyValue("url.path", request.getRequestURI())
                        .addKeyValue("http.response.status_code", response.getStatus())
                        .addKeyValue("event.duration_ms", durationMs)
                        .log("request completed");
            } finally {
                MDC.remove(MDC_KEY);
            }
        }
    }

    public static String from(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : null;
    }

    static String resolve(String candidate) {
        if (candidate != null && UUID_PATTERN.matcher(candidate).matches()) {
            return UUID.fromString(candidate).toString();
        }
        return UUID.randomUUID().toString();
    }
}
