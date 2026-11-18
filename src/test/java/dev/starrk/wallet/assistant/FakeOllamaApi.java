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

/** Imita {@code POST /api/chat} do Ollama com respostas roteirizadas e guarda o que foi enviado. */
final class FakeOllamaApi implements AutoCloseable {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Scripted> script = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new ArrayList<>();

    FakeOllamaApi() throws IOException {
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

    void replyToolCall(String toolName, String argumentsJson) {
        script.add(new Scripted(200, response("""
                {"role":"assistant","content":"","tool_calls":[{"function":{"name":"%s","arguments":%s}}]}"""
                .formatted(toolName, argumentsJson), 120, 30), 0));
    }

    void replyText(String text) {
        script.add(new Scripted(200, response(
                "{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(text) + "}", 200, 40), 0));
    }

    void replyError(int status) {
        script.add(new Scripted(status, "{\"error\":\"falha simulada\"}", 0));
    }

    void replyAfterDelay(long millis, String text) {
        script.add(new Scripted(200, response(
                "{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(text) + "}", 10, 5), millis));
    }

    List<JsonNode> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** Conteúdo das mensagens de ferramenta ({@code role: tool}) enviadas na requisição {@code index}. */
    List<String> toolResults(int index) {
        var results = new ArrayList<String>();
        for (JsonNode message : requests().get(index).get("messages")) {
            if ("tool".equals(message.path("role").asString())) {
                results.add(message.path("content").asString());
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
        if (body.length > 0) {
            synchronized (requests) {
                requests.add(JSON.readTree(body));
            }
        }
        Scripted next = script.poll();
        if (next == null) {
            next = new Scripted(500, "{\"error\":\"sem roteiro\"}", 0);
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

    private static String response(String message, int promptTokens, int outputTokens) {
        return """
                {"model":"qwen3:8b","created_at":"2026-11-18T00:00:00Z","message":%s,
                 "done":true,"done_reason":"stop","total_duration":1000,"load_duration":10,
                 "prompt_eval_count":%d,"prompt_eval_duration":100,"eval_count":%d,"eval_duration":100}
                """.formatted(message, promptTokens, outputTokens);
    }

    private record Scripted(int status, String body, long delayMillis) {
    }
}
