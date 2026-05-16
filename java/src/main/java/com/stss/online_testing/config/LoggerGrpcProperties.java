package com.stss.online_testing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "logger.grpc")
public class LoggerGrpcProperties {

    private String host = "localhost";
    private int port = 50061;
    private boolean plaintext = true;
    private long timeoutMs = 500L;
    private String apiServiceName = "apiserver-service";
    private String proctorServiceName = "proctor-controller-service";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isPlaintext() {
        return plaintext;
    }

    public void setPlaintext(boolean plaintext) {
        this.plaintext = plaintext;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getApiServiceName() {
        return apiServiceName;
    }

    public void setApiServiceName(String apiServiceName) {
        this.apiServiceName = apiServiceName;
    }

    public String getProctorServiceName() {
        return proctorServiceName;
    }

    public void setProctorServiceName(String proctorServiceName) {
        this.proctorServiceName = proctorServiceName;
    }
}
