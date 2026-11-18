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

import dev.starrk.wallet.transfer.TransferCategory;
import dev.starrk.wallet.user.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Classifica uma transferência em {@link TransferCategory} com saída estruturada: o provedor (Ollama
 * ou Anthropic) só gera JSON que segue o esquema, com a categoria restrita ao enum. Casos óbvios são
 * resolvidos por regra, sem chamar o modelo.
 */
@Component
public class TransactionCategorizer {
    private static final Logger log = LoggerFactory.getLogger(TransactionCategorizer.class);
    static final Map<String, Object> SCHEMA = schema();

    private final ChatClient chat;
    private final LlmGateway gateway;
    private final ModelProvider provider;
    private final ObjectMapper json;
    private final String systemPrompt;

    TransactionCategorizer(ChatClient.Builder chat, LlmGateway gateway, ModelProvider provider,
            ObjectMapper json, @Value("classpath:prompts/categorization-system.md") Resource systemPrompt) {
        this.chat = chat.build();
        this.gateway = gateway;
        this.provider = provider;
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
                .options(provider.structuredOptions(SCHEMA))
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

    private static Map<String, Object> schema() {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("type", "string");
        category.put("enum", Arrays.stream(TransferCategory.values()).map(Enum::name).toList());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("category", category));
        schema.put("required", List.of("category"));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
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
