package com.stss.online_testing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proctor")
public class ProctorGrpcProperties {

    private GrpcServerConfig grpc = new GrpcServerConfig();
    private ControllerConfig controller = new ControllerConfig();
    private RedisConfig redis = new RedisConfig();

    public GrpcServerConfig getGrpc() {
        return grpc;
    }

    public void setGrpc(GrpcServerConfig grpc) {
        this.grpc = grpc;
    }

    public ControllerConfig getController() {
        return controller;
    }

    public void setController(ControllerConfig controller) {
        this.controller = controller;
    }

    public RedisConfig getRedis() {
        return redis;
    }

    public void setRedis(RedisConfig redis) {
        this.redis = redis;
    }

    public static class GrpcServerConfig {
        private int port = 50080;
        private boolean enabled = true;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ControllerConfig {
        private String url = "http://localhost:8081";
        private boolean enabled = false;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class RedisConfig {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
