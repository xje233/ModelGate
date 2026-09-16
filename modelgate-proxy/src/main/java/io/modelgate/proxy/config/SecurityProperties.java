package io.modelgate.proxy.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * modelgate.security.keys — API keys plus the identity they map to.
 * W3 nodes move this to MySQL (t_api_key, SHA-256 hashed token) behind the same lookup.
 */
@ConfigurationProperties(prefix = "modelgate.security")
public class SecurityProperties {

    private List<KeyConfig> keys = new ArrayList<>();

    public static class KeyConfig {

        private String value;
        private String id;
        private String tenant;
        private List<String> models = new ArrayList<>();
        private Integer rpm;
        private Long tpm;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models;
        }

        public Integer getRpm() {
            return rpm;
        }

        public void setRpm(Integer rpm) {
            this.rpm = rpm;
        }

        public Long getTpm() {
            return tpm;
        }

        public void setTpm(Long tpm) {
            this.tpm = tpm;
        }
    }

    public List<KeyConfig> getKeys() {
        return keys;
    }

    public void setKeys(List<KeyConfig> keys) {
        this.keys = keys;
    }
}
