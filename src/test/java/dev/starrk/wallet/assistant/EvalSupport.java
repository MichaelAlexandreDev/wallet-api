package dev.starrk.wallet.assistant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Utilitários comuns às avaliações com o modelo real. */
final class EvalSupport {
    static final Path REPORT_DIR = Path.of("target", "eval");

    private EvalSupport() {
    }

    static boolean hasApiKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    static void writeReport(String fileName, String markdown) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve(fileName), markdown);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Tokens e custo estimado de uma operação, lidos das métricas que a aplicação já publica. */
    static String usage(MeterRegistry meters, String operation) {
        return "Tokens: %.0f de entrada, %.0f de saída. Custo estimado: US$ %.4f."
                .formatted(sum(meters, "wallet.assistant.tokens", operation, "input"),
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
}
