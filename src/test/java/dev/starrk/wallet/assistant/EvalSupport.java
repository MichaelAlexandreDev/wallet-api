package dev.starrk.wallet.assistant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Utilitários comuns às avaliações com um modelo real. Por padrão usam o Ollama local (gratuito);
 * com AI_PROVIDER=anthropic usam o Claude e exigem ANTHROPIC_API_KEY.
 */
final class EvalSupport {
    static final Path REPORT_DIR = Path.of("target", "eval");

    private EvalSupport() {
    }

    static String provider() {
        return env("AI_PROVIDER", "ollama");
    }

    static String model() {
        return provider().equals("anthropic") ? env("ANTHROPIC_MODEL", "claude-opus-5-5") : env("OLLAMA_MODEL", "qwen3:8b");
    }

    static String ollamaBaseUrl() {
        return env("OLLAMA_BASE_URL", "http://localhost:11434");
    }

    /** Liga a avaliação ao provedor real, sobrescrevendo a porta fechada usada pelos outros testes. */
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.model.chat", EvalSupport::provider);
        registry.add("spring.ai.ollama.base-url", EvalSupport::ollamaBaseUrl);
    }

    /** Motivo para pular a avaliação, ou {@code null} quando o provedor está pronto. */
    static String skipReason() {
        if (provider().equals("anthropic")) {
            String key = System.getenv("ANTHROPIC_API_KEY");
            return key == null || key.isBlank() ? "AI_PROVIDER=anthropic sem ANTHROPIC_API_KEY: avaliação pulada." : null;
        }
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(ollamaBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            if (!response.body().contains("\"" + model() + "\"")) {
                return "Modelo %s não encontrado no Ollama (%s). Rode: ollama pull %s"
                        .formatted(model(), ollamaBaseUrl(), model());
            }
            return null;
        } catch (IOException exception) {
            return "Ollama não respondeu em " + ollamaBaseUrl() + ": avaliação pulada.";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Interrompido ao verificar o Ollama.";
        }
    }

    static void writeReport(String fileName, String markdown) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve(fileName), markdown);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Provedor, modelo, tokens e custo estimado, lidos das métricas que a aplicação já publica. */
    static String usage(MeterRegistry meters, String operation) {
        return "Provedor: %s, modelo: %s. Tokens: %.0f de entrada, %.0f de saída. Custo estimado: US$ %.4f."
                .formatted(provider(), model(),
                        sum(meters, "wallet.assistant.tokens", operation, "input"),
                        sum(meters, "wallet.assistant.tokens", operation, "output"),
                        sum(meters, "wallet.assistant.cost", operation, null));
    }

    private static double sum(MeterRegistry meters, String name, String operation, String type) {
        var search = meters.find(name).tag("operation", operation);
        if (type != null) {
            search = search.tag("type", type);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
