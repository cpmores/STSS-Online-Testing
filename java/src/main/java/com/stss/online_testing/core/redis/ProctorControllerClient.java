package com.stss.online_testing.core.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.config.ProctorGrpcProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "proctor.controller.enabled", havingValue = "true", matchIfMissing = false)
public class ProctorControllerClient {

    private static final Logger log = LoggerFactory.getLogger(ProctorControllerClient.class);

    private final ProctorGrpcProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ProctorControllerClient(ProctorGrpcProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public ProctorStartResult startProctor(Long examId) {
        if (!properties.getController().isEnabled()) {
            return null;
        }

        String url = properties.getController().getUrl() + "/api/proctor/v1/start?examId=" + examId;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Started proctor container for examId={}", examId);
                JsonNode body = objectMapper.readTree(response.body());
                String wsEndpoint = body.path("ws_endpoint").asText(null);
                return new ProctorStartResult(examId, wsEndpoint);
            } else {
                throw ApiBusinessException.unprocessable(
                        "启动 proctor 失败: status="
                                + response.statusCode()
                                + ", body="
                                + response.body());
            }
        } catch (Exception e) {
            log.error("Failed to call proctor controller start for examId={}: {}", examId, e.getMessage());
            if (e instanceof ApiBusinessException apiBusinessException) {
                throw apiBusinessException;
            }
            throw ApiBusinessException.unprocessable("调用 proctor controller 启动失败: " + e.getMessage());
        }
    }

    public void stopProctor(Long examId) {
        if (!properties.getController().isEnabled()) return;

        String url = properties.getController().getUrl() + "/api/proctor/v1/stop/" + examId;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Stopped proctor container for examId={}", examId);
            } else {
                log.warn("Failed to stop proctor for examId={}, status={}, body={}",
                        examId, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Failed to call proctor controller stop for examId={}: {}", examId, e.getMessage());
        }
    }

    public record ProctorStartResult(Long examId, String wsEndpoint) {
    }
}
