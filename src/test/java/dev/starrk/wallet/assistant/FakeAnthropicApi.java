package dev.starrk.wallet.assistant;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imita {@code POST /v1/messages} da API da Anthropic com respostas roteirizadas. Permite testar o
 * Spring AI de verdade (serialização, laço de ferramentas, timeouts) sem chave e sem custo, e
 * inspecionar exatamente o que a aplicação enviou ao modelo.
 */
final class FakeAnthropicApi implements AutoCloseable {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Scripted> script = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new ArrayList<>();

    FakeAnthropicApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        script.clear();
        synchronized (requests) {
            requests.clear();
        }
    }

    /** Próxima resposta: o modelo pede uma ferramenta. */
    void replyToolUse(String toolName, String inputJson) {
        script.add(new Scripted(200, message("tool_use",
                "{\"type\":\"tool_use\",\"id\":\"toolu_%d\",\"name\":\"%s\",\"input\":%s}"
                        .formatted(script.size() + 1, toolName, inputJson), 120, 30), 0));
    }

    /** Próxima resposta: o modelo encerra com texto. */
    void replyText(String text) {
        script.add(new Scripted(200, message("end_turn",
                "{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(text) + "}", 200, 40), 0));
    }

    void replyRefusal() {
        script.add(new Scripted(200, message("refusal", "{\"type\":\"text\",\"text\":\"\"}", 50, 1), 0));
    }

    void replyError(int status) {
        script.add(new Scripted(status,
                "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"falha simulada\"}}", 0));
    }

    void replyAfterDelay(long millis, String text) {
        script.add(new Scripted(200, message("end_turn",
                "{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(text) + "}", 10, 5), millis));
    }

    List<JsonNode> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** Conteúdo (texto) dos tool_result enviados na requisição de índice {@code index}. */
    List<String> toolResults(int index) {
        var results = new ArrayList<String>();
        for (JsonNode message : requests().get(index).get("messages")) {
            for (JsonNode block : message.path("content")) {
                if ("tool_result".equals(block.path("type").asString())) {
                    JsonNode content = block.get("content");
                    if (content.isString()) {
                        results.add(content.asString());
                    } else {
                        content.forEach(part -> results.add(part.path("text").asString()));
                    }
                }
            }
        }
        return results;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        synchronized (requests) {
            requests.add(JSON.readTree(body));
        }
        Scripted next = script.poll();
        if (next == null) {
            next = new Scripted(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"sem roteiro\"}}", 0);
        }
        if (next.delayMillis() > 0) {
            try {
                Thread.sleep(next.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] response = next.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(next.status(), response.length);
            exchange.getResponseBody().write(response);
        } catch (IOException clientGaveUp) {
            // O cliente desistiu por timeout: o teste verifica isso do lado da aplicação.
        } finally {
            exchange.close();
        }
    }

    private static String message(String stopReason, String contentBlock, int inputTokens, int outputTokens) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[%s],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":%d,"output_tokens":%d}}
                """.formatted(contentBlock, stopReason, inputTokens, outputTokens);
    }

    private record Scripted(int status, String body, long delayMillis) {
    }
}
