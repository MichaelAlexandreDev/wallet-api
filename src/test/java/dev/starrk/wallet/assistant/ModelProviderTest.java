package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelProviderTest {
    private final AssistantProperties properties = new AssistantProperties(Duration.ofSeconds(30),
            new AssistantProperties.Anthropic("claude-opus-5-5", "medium", "low", 16000,
                    new BigDecimal("4.00"), new BigDecimal("20.00")),
            new AssistantProperties.Ollama("qwen3:8b", 8192, 1024, false, "10m"),
            new AssistantProperties.CircuitBreaker(50, 10, 5, Duration.ofSeconds(30)));

    @Test
    void anthropicNeedsAnApiKey() {
        var withoutKey = new AnthropicModelProvider(properties, "  ");

        assertThat(withoutKey.configured()).isFalse();
        assertThat(withoutKey.notConfiguredMessage()).contains("ANTHROPIC_API_KEY").contains("AI_PROVIDER=ollama");
        assertThat(new AnthropicModelProvider(properties, "sk-test").configured()).isTrue();
    }

    @Test
    void ollamaIsFreeAndNeedsNoCredentials() {
        var ollama = new OllamaModelProvider(properties);

        assertThat(ollama.configured()).isTrue();
        assertThat(ollama.inputUsdPerMillionTokens()).isZero();
        assertThat(ollama.outputUsdPerMillionTokens()).isZero();
    }
}
