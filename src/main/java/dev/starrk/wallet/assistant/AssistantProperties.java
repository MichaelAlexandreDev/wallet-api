package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do assistente. O provedor é escolhido por {@code spring.ai.model.chat}: {@code ollama}
 * (padrão, local e gratuito) ou {@code anthropic} (Claude, exige chave e é cobrado).
 */
@ConfigurationProperties("wallet.assistant")
public record AssistantProperties(
        Duration timeout,
        Anthropic anthropic,
        Ollama ollama,
        CircuitBreaker circuitBreaker) {

    /** Os preços servem só para estimar custo nas métricas; confira a tabela oficial ao trocar de modelo. */
    public record Anthropic(String model, String chatEffort, String categorizationEffort, int maxTokens,
            BigDecimal inputUsdPerMillionTokens, BigDecimal outputUsdPerMillionTokens) {
    }

    /**
     * {@code numCtx} precisa caber o prompt de sistema, as ferramentas e os resultados; o padrão do
     * Ollama é menor e corta o início da conversa sem avisar. {@code think} desligado deixa modelos
     * com raciocínio (como o qwen3) muito mais rápidos na CPU.
     */
    public record Ollama(String model, int numCtx, int maxTokens, boolean think, String keepAlive) {
    }

    public record CircuitBreaker(float failureRateThreshold, int slidingWindowSize, int minimumNumberOfCalls,
            Duration waitDurationInOpenState) {
    }
}
