package com.stss.online_testing.config;

import com.stss.online_testing.interceptor.RequestTraceInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestTraceInterceptor requestTraceInterceptor;

    public WebMvcConfig(RequestTraceInterceptor requestTraceInterceptor) {
        this.requestTraceInterceptor = requestTraceInterceptor;
    }

    @SuppressWarnings("null")
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(requestTraceInterceptor)
                .addPathPatterns("/api/ot/v1/**")
                .order(0);
    }
}
