package io.modelgate.proxy.config;

import java.time.ZoneId;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelgate.proxy.service.UsageCollector;
import io.modelgate.usage.InMemoryUsageStore;
import io.modelgate.usage.JdbcUsageStore;
import io.modelgate.usage.UsageStore;

/**
 * Picks the accounting backend. {@code memory} keeps the gateway runnable with zero
 * infrastructure; {@code jdbc} batches into {@code t_usage_log}.
 */
@Configuration
public class UsageConfig {

    @Bean
    public UsageStore usageStore(UsageProperties properties,
                                 ObjectProvider<javax.sql.DataSource> dataSourceProvider) {
        if ("jdbc".equalsIgnoreCase(properties.getBackend())) {
            javax.sql.DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                throw new IllegalStateException(
                        "modelgate.usage.backend=jdbc requires a DataSource (spring.datasource.*)");
            }
            return new JdbcUsageStore(dataSource,
                    properties.getQueueCapacity(),
                    properties.getBatchSize(),
                    properties.getFlushIntervalMillis());
        }
        return new InMemoryUsageStore(properties.getMemoryMaxRecords());
    }

    @Bean
    public UsageCollector usageCollector(UsageStore store) {
        return new UsageCollector(store, ZoneId.systemDefault());
    }
}
