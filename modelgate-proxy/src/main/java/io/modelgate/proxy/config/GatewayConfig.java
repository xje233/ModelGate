package io.modelgate.proxy.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelgate.client.LlmClient;
import io.modelgate.client.ReactorLlmClient;
import io.modelgate.core.Deployment;
import io.modelgate.providers.ProviderRegistry;
import io.modelgate.router.InProcessRouterState;
import io.modelgate.router.RouterService;
import io.modelgate.router.RouterState;
import io.modelgate.proxy.metrics.MetricsRecorder;
import io.modelgate.proxy.security.ApiKeyRegistry;
import io.modelgate.proxy.service.GatewayService;

@Configuration
public class GatewayConfig {

    @Bean
    public ApiKeyRegistry apiKeyRegistry(SecurityProperties security, QuotaProperties quota) {
        return new ApiKeyRegistry(security, quota);
    }

    @Bean
    public ProviderRegistry providerRegistry() {
        return ProviderRegistry.ofDefaults();
    }

    @Bean
    public RouterState routerState(RouterProperties properties) {
        return new InProcessRouterState(
                properties.getAllowedFails(),
                properties.getCooldownSeconds(),
                properties.getFailureWindowSize(),
                properties.getFailureRateThreshold(),
                properties.getMinCallsForRate());
    }

    @Bean
    public RouterService routerService(RouterProperties properties, RouterState routerState) {
        List<Deployment> deployments = new ArrayList<>();
        properties.getGroups().forEach((group, groupConfig) ->
                groupConfig.getDeployments().forEach(dc -> deployments.add(new Deployment(
                        dc.getName(), group, dc.getProvider(), dc.getModelId(),
                        dc.getBaseUrl(), dc.getApiKey(), dc.getWeight(), dc.getHeaders()))));
        return new RouterService(deployments, Map.copyOf(properties.getFallbacks()), routerState);
    }

    @Bean
    public LlmClient llmClient(ProviderRegistry providers) {
        return new ReactorLlmClient(providers);
    }

    @Bean
    public GatewayService gatewayService(RouterService router, LlmClient client,
                                        MetricsRecorder metrics) {
        return new GatewayService(router, client, metrics);
    }
}
