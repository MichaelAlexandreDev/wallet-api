package dev.starrk.wallet.assistant;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

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

    /**
     * O cliente do Ollama é construído a partir do RestClient.Builder do Spring, que não tem timeout de
     * leitura por padrão: um modelo travado prenderia a requisição para sempre. Os clientes externos do
     * pagamento definem a própria fábrica, com timeouts menores, e não são afetados.
     */
    @Bean
    RestClientCustomizer llmHttpTimeouts(AssistantProperties properties) {
        return builder -> {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(properties.timeout());
            builder.requestFactory(factory);
        };
    }
}
