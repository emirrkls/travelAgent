package com.emirrkls.phokarta.backend.observability;

import com.emirrkls.phokarta.backend.api.controller.PlaceController;
import com.emirrkls.phokarta.backend.api.dto.PlaceDetailResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Supported MVC lifecycle hooks; no security-chain or converter replacement. */
@ControllerAdvice(assignableTypes = PlaceController.class)
public class PlaceDetailMvcObservation implements WebMvcConfigurer, ResponseBodyAdvice<Object> {
    private final HandlerInterceptor boundary = new HandlerInterceptor() {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            PlaceDetailObservation trace = PlaceDetailObservation.current();
            if (trace != null) trace.mark(PlaceDetailObservation.Phase.SECURITY_CHAIN_COMPLETE);
            return true;
        }

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response,
                               Object handler, ModelAndView modelAndView) {
            PlaceDetailObservation trace = PlaceDetailObservation.current();
            if (trace != null) trace.serializationEnd();
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception failure) {
            PlaceDetailObservation trace = PlaceDetailObservation.current();
            if (trace != null) {
                if (failure != null) trace.failure(failure);
                trace.serializationEnd();
            }
        }
    };

    HandlerInterceptor boundaryForTest() { return boundary; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(boundary).addPathPatterns("/api/v1/places/*");
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getContainingClass() == PlaceController.class
                && returnType.getMethod() != null
                && returnType.getMethod().getName().equals("detail");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        PlaceDetailObservation trace = PlaceDetailObservation.current();
        if (trace != null && body instanceof PlaceDetailResponse) trace.serializationStart();
        return body;
    }
}
