package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.function.Supplier;

import dev.starrk.wallet.shared.ApiException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Único ponto de saída para o LLM. Aplica o circuit breaker, mede latência, tokens e custo estimado,
 * e transforma qualquer falha do provedor em 503 sem afetar as rotas de carteira.
 */
@Component
public class LlmGateway {
    static final String UNAVAILABLE = "Assistente indisponível no momento. Suas operações de carteira continuam funcionando.";
    static final String NOT_CONFIGURED = "Assistente não configurado: defina ANTHROPIC_API_KEY para habilitá-lo.";
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meters;
    private final AssistantProperties properties;
    private final boolean configured;

    public LlmGateway(AssistantProperties properties, MeterRegistry meters, CircuitBreaker llmCircuitBreaker,
            @Value("${spring.ai.anthropic.api-key:}") String apiKey) {
        this.properties = properties;
        this.meters = meters;
        this.circuitBreaker = llmCircuitBreaker;
        this.configured = !apiKey.isBlank();
    }

    public ChatResponse call(String operation, Supplier<ChatResponse> request) {
        if (!configured) {
            record(operation, "not_configured", Timer.start(meters));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        var sample = Timer.start(meters);
        try {
            ChatResponse response = circuitBreaker.executeSupplier(request);
            recordUsage(operation, response);
            record(operation, "success", sample);
            return response;
        } catch (CallNotPermittedException exception) {
            record(operation, "circuit_open", sample);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        } catch (RuntimeException exception) {
            log.warn("Falha ao chamar o LLM na operação {}: {}", operation, exception.toString());
            record(operation, "error", sample);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }
    }

    private void record(String operation, String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder("wallet.assistant.requests")
                .description("Chamadas ao LLM, incluindo o laço de ferramentas")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meters));
    }

    private void recordUsage(String operation, ChatResponse response) {
        var usage = response.getMetadata().getUsage();
        long input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        tokens(operation, "input").increment(input);
        tokens(operation, "output").increment(output);

        var pricing = properties.pricing();
        BigDecimal cost = pricing.inputUsdPerMillionTokens().multiply(BigDecimal.valueOf(input))
                .add(pricing.outputUsdPerMillionTokens().multiply(BigDecimal.valueOf(output)))
                .divide(ONE_MILLION, MathContext.DECIMAL64);
        Counter.builder("wallet.assistant.cost")
                .description("Custo estimado das chamadas ao LLM")
                .baseUnit("usd")
                .tag("operation", operation)
                .register(meters)
                .increment(cost.doubleValue());
    }

    private Counter tokens(String operation, String type) {
        return Counter.builder("wallet.assistant.tokens")
                .description("Tokens consumidos nas chamadas ao LLM")
                .baseUnit("tokens")
                .tag("operation", operation)
                .tag("type", type)
                .register(meters);
    }
}
