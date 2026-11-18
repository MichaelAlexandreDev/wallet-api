package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do assistente. Os preços servem só para estimar custo nas métricas; confira a tabela
 * oficial da Anthropic ao trocar de modelo.
 */
@ConfigurationProperties("wallet.assistant")
public record AssistantProperties(
        String model,
        String chatEffort,
        String categorizationEffort,
        int maxTokens,
        Pricing pricing,
        CircuitBreaker circuitBreaker) {

    public record Pricing(BigDecimal inputUsdPerMillionTokens, BigDecimal outputUsdPerMillionTokens) {
    }

    public record CircuitBreaker(float failureRateThreshold, int slidingWindowSize, int minimumNumberOfCalls,
            Duration waitDurationInOpenState) {
    }
}
