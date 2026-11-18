package dev.starrk.wallet.assistant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;
import dev.starrk.wallet.transfer.TransferCategory;
import dev.starrk.wallet.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Classifica uma transferência em {@link TransferCategory} com saída estruturada: a API da Anthropic
 * só devolve JSON que segue o esquema, com a categoria restrita ao enum. Casos óbvios são resolvidos
 * por regra, sem chamar o modelo.
 */
@Component
public class TransactionCategorizer {
    private static final Logger log = LoggerFactory.getLogger(TransactionCategorizer.class);
    static final JsonOutputFormat SCHEMA = schema();

    private final ChatClient chat;
    private final LlmGateway gateway;
    private final AssistantProperties properties;
    private final ObjectMapper json;
    private final String systemPrompt;

    public TransactionCategorizer(ChatClient.Builder chat, LlmGateway gateway, AssistantProperties properties,
            ObjectMapper json, @Value("classpath:prompts/categorization-system.md") Resource systemPrompt) {
        this.chat = chat.build();
        this.gateway = gateway;
        this.properties = properties;
        this.json = json;
        this.systemPrompt = read(systemPrompt).replace("{categories}", Arrays.stream(TransferCategory.values())
                .map(category -> "- " + category.name() + ": " + category.meaning())
                .collect(Collectors.joining("\n")));
    }

    public Result categorize(Input input) {
        if (input.description() == null && input.payeeType() == UserType.COMMON) {
            return new Result(TransferCategory.PERSONAL_TRANSFER, Source.RULE);
        }
        var response = gateway.call("categorize", () -> chat.prompt()
                .system(systemPrompt)
                .user(json.writeValueAsString(input))
                .options(AnthropicChatOptions.builder()
                        .model(properties.model())
                        .maxTokens(4096)
                        .outputConfig(OutputConfig.builder()
                                .effort(AssistantService.effort(properties.categorizationEffort()))
                                .format(SCHEMA)
                                .build()))
                .call()
                .chatResponse());
        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        try {
            var parsed = json.readTree(text == null ? "" : text);
            return new Result(TransferCategory.valueOf(parsed.get("category").asString()), Source.MODEL);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            log.warn("Resposta de categorização fora do esquema; usando OTHER: {}", exception.toString());
            return new Result(TransferCategory.OTHER, Source.FALLBACK);
        }
    }

    private static JsonOutputFormat schema() {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("type", "string");
        category.put("enum", Arrays.stream(TransferCategory.values()).map(Enum::name).toList());
        return JsonOutputFormat.builder()
                .schema(JsonOutputFormat.Schema.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of("category", category)))
                        .putAdditionalProperty("required", JsonValue.from(List.of("category")))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Dados enviados ao modelo. Quem pagou não aparece: só importa para quem foi o gasto. */
    public record Input(String description, String payeeName, UserType payeeType, BigDecimal value) {
    }

    public record Result(TransferCategory category, Source source) {
    }

    public enum Source { MODEL, RULE, FALLBACK, STORED }
}
