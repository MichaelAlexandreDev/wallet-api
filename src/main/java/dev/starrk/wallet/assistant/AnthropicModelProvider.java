package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Claude via API da Anthropic. Exige ANTHROPIC_API_KEY e é cobrado por token. */
@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "anthropic")
class AnthropicModelProvider implements ModelProvider {
    private final AssistantProperties.Anthropic settings;
    private final boolean configured;

    AnthropicModelProvider(AssistantProperties properties, @Value("${spring.ai.anthropic.api-key:}") String apiKey) {
        this.settings = properties.anthropic();
        this.configured = !apiKey.isBlank();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public String notConfiguredMessage() {
        return "Assistente não configurado: defina ANTHROPIC_API_KEY ou use AI_PROVIDER=ollama.";
    }

    @Override
    public ChatOptions.Builder<?> chatOptions() {
        return AnthropicChatOptions.builder()
                .model(settings.model())
                .maxTokens(settings.maxTokens())
                .effort(effort(settings.chatEffort()));
    }

    /** Saída estruturada nativa: a API só devolve JSON válido para o esquema. */
    @Override
    public ChatOptions.Builder<?> structuredOptions(Map<String, Object> schema) {
        var format = JsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> format.putAdditionalProperty(key, JsonValue.from(value)));
        return AnthropicChatOptions.builder()
                .model(settings.model())
                .maxTokens(4096)
                .outputConfig(OutputConfig.builder()
                        .effort(effort(settings.categorizationEffort()))
                        .format(JsonOutputFormat.builder().schema(format.build()).build())
                        .build());
    }

    @Override
    public BigDecimal inputUsdPerMillionTokens() {
        return settings.inputUsdPerMillionTokens();
    }

    @Override
    public BigDecimal outputUsdPerMillionTokens() {
        return settings.outputUsdPerMillionTokens();
    }

    private static OutputConfig.Effort effort(String value) {
        return OutputConfig.Effort.of(value.toLowerCase(Locale.ROOT));
    }
}
