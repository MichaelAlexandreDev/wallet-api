package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Modelo local via Ollama. Não precisa de chave nem gera custo; o padrão do projeto. */
@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama", matchIfMissing = true)
class OllamaModelProvider implements ModelProvider {
    private final AssistantProperties.Ollama settings;

    OllamaModelProvider(AssistantProperties properties) {
        this.settings = properties.ollama();
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String notConfiguredMessage() {
        return "";
    }

    @Override
    public ChatOptions.Builder<?> chatOptions() {
        return base().numPredict(settings.maxTokens());
    }

    /** O Ollama restringe a geração ao esquema JSON passado em {@code format}. */
    @Override
    public ChatOptions.Builder<?> structuredOptions(Map<String, Object> schema) {
        return base().numPredict(256).format(schema);
    }

    @Override
    public BigDecimal inputUsdPerMillionTokens() {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal outputUsdPerMillionTokens() {
        return BigDecimal.ZERO;
    }

    private OllamaChatOptions.Builder base() {
        var builder = OllamaChatOptions.builder()
                .model(settings.model())
                .numCtx(settings.numCtx())
                .keepAlive(settings.keepAlive());
        return settings.think() ? builder.enableThinking() : builder.disableThinking();
    }
}
