package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.time.Duration;

import dev.starrk.wallet.TestSecrets;
import dev.starrk.wallet.auth.TokenService;
import dev.starrk.wallet.integration.AuthorizationClient;
import dev.starrk.wallet.integration.NotificationClient;
import dev.starrk.wallet.transfer.TransferCategory;
import dev.starrk.wallet.transfer.TransferRepository;
import dev.starrk.wallet.transfer.TransferRequest;
import dev.starrk.wallet.transfer.TransferService;
import dev.starrk.wallet.user.User;
import dev.starrk.wallet.user.UserRepository;
import dev.starrk.wallet.user.UserType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * O caminho do Ollama, provedor padrão: formato da requisição, laço de ferramentas, saída
 * estruturada, métricas sem custo, falhas e timeout. Não precisa de Ollama instalado.
 */
@SpringBootTest(properties = {
        "notifications.enabled=false",
        "security.jwt.secret=" + TestSecrets.JWT_SECRET,
        "spring.ai.model.chat=ollama",
        "wallet.assistant.timeout=1s"})
@AutoConfigureMockMvc
@Testcontainers
class OllamaProviderIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    static final FakeOllamaApi ollama = newFakeApi();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
    }

    @AfterAll
    static void stopFakeApi() {
        ollama.close();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired TransferRepository transfers;
    @Autowired TransferService transferService;
    @Autowired TokenService tokens;
    @Autowired CircuitBreaker llmCircuitBreaker;
    @Autowired MeterRegistry meters;
    @MockitoBean AuthorizationClient authorization;
    @MockitoBean NotificationClient notification;

    private User ana;
    private User bia;
    private User store;

    @BeforeEach
    void setUp() {
        ollama.reset();
        llmCircuitBreaker.reset();
        transfers.deleteAll();
        users.deleteAll();
        ana = createUser("Ana Silva", "11111111111", "ana@example.com", UserType.COMMON, "100.00");
        bia = createUser("Bia Souza", "33333333333", "bia@example.com", UserType.COMMON, "50.00");
        store = createUser("Loja Exemplo", "22222222222222", "loja@example.com", UserType.MERCHANT, "0.00");
    }

    @Test
    void runsTheToolLoopWithTheConfiguredLocalModel() throws Exception {
        ollama.replyToolCall("getBalance", "{}");
        ollama.replyText("Seu saldo é R$ 100,00.");

        chat(ana, "Qual é o meu saldo?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Seu saldo é R$ 100,00."));

        JsonNode first = ollama.requests().getFirst();
        assertThat(first.get("model").asString()).isEqualTo("qwen3:8b");
        assertThat(first.get("think").asBoolean()).isFalse();
        assertThat(first.at("/options/num_ctx").asInt()).isEqualTo(8192);
        assertThat(first.at("/options/num_predict").asInt()).isEqualTo(1024);
        assertThat(first.get("keep_alive").asString()).isEqualTo("10m");
        var toolNames = new java.util.ArrayList<String>();
        first.get("tools").forEach(tool -> toolNames.add(tool.at("/function/name").asString()));
        assertThat(toolNames).containsExactlyInAnyOrder("getBalance", "getStatement", "getTransfer", "proposeTransfer");
        assertThat(ollama.toolResults(1)).singleElement().asString().contains("\"balance\":100.00");
    }

    @Test
    void proposalsStillNeverMoveMoney() throws Exception {
        ollama.replyToolCall("proposeTransfer", "{\"payeeEmail\":\"bia@example.com\",\"value\":30}");
        ollama.replyText("Proposta pronta para confirmação.");

        chat(ana, "Manda 30 pra Bia")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposals.length()").value(1))
                .andExpect(jsonPath("$.proposals[0].payeeId").value(bia.getId()));

        assertThat(balance(ana)).isEqualByComparingTo("100.00");
        assertThat(transfers.count()).isZero();
    }

    @Test
    void categorizesWithTheJsonSchemaInFormat() throws Exception {
        var transfer = transferService.transfer(ana.getId(),
                new TransferRequest(new BigDecimal("28.50"), store.getId(), "uber pra casa"), "k");
        ollama.replyText("{\"category\":\"TRANSPORT\"}");

        mvc.perform(post("/transfer/{id}/category", transfer.id()).header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("TRANSPORT"))
                .andExpect(jsonPath("$.source").value("MODEL"));

        JsonNode request = ollama.requests().getFirst();
        assertThat(request.at("/format/type").asString()).isEqualTo("object");
        assertThat(request.at("/format/properties/category/enum")).hasSize(TransferCategory.values().length);
        assertThat(request.at("/options/num_predict").asInt()).isEqualTo(256);
        // O Spring AI envia "tools": [] no Ollama; o que importa é não expor nenhuma ferramenta aqui.
        assertThat(request.path("tools").isEmpty()).as("ferramentas na categorização: %s", request.get("tools")).isTrue();
    }

    @Test
    void recordsLocalTokensWithZeroCost() throws Exception {
        double inputBefore = counter("wallet.assistant.tokens", "input");
        double costBefore = counter("wallet.assistant.cost", null);
        ollama.replyToolCall("getBalance", "{}");
        ollama.replyText("R$ 100,00.");

        chat(ana, "Saldo?").andExpect(status().isOk());

        assertThat(counter("wallet.assistant.tokens", "input") - inputBefore).isEqualTo(320);
        assertThat(counter("wallet.assistant.cost", null) - costBefore).isZero();
        assertThat(meters.get("wallet.assistant.requests").tag("provider", "ollama").tag("outcome", "success")
                .timer().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ollamaErrorReturns503() throws Exception {
        ollama.replyError(500);

        chat(ana, "Saldo?")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(LlmGateway.UNAVAILABLE));
    }

    @Test
    void slowLocalModelTimesOut() throws Exception {
        ollama.replyAfterDelay(3000, "tarde demais");

        long start = System.nanoTime();
        chat(ana, "Saldo?").andExpect(status().isServiceUnavailable());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    private ResultActions chat(User user, String message) throws Exception {
        return mvc.perform(post("/assistant/chat").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AssistantController.ChatRequest(message))));
    }

    private double counter(String name, String type) {
        var search = meters.find(name).tag("operation", "chat");
        if (type != null) {
            search = search.tag("type", type);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private String bearer(User user) {
        return "Bearer " + tokens.issue(user.getId()).value();
    }

    private User createUser(String name, String document, String email, UserType type, String balance) {
        var user = new User(name, document, email, "test-hash", type);
        if (new BigDecimal(balance).signum() > 0) {
            user.getWallet().credit(new BigDecimal(balance));
        }
        return users.saveAndFlush(user);
    }

    private BigDecimal balance(User user) {
        return users.findById(user.getId()).orElseThrow().getWallet().getBalance();
    }

    private static FakeOllamaApi newFakeApi() {
        try {
            return new FakeOllamaApi();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
