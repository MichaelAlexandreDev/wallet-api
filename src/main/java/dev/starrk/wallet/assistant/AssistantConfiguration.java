package dev.starrk.wallet.assistant;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
class AssistantConfiguration {

    /** Abre quando metade das chamadas recentes falha e, enquanto aberto, recusa sem chamar o provedor. */
    @Bean
    CircuitBreaker llmCircuitBreaker(AssistantProperties properties) {
        var settings = properties.circuitBreaker();
        return CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .build());
    }
}
