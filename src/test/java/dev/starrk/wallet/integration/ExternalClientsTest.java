package dev.starrk.wallet.integration;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import dev.starrk.wallet.shared.ApiException;
import dev.starrk.wallet.transfer.Transfer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalClientsTest {
    private HttpServer server;
    private ExecutorService executor;
    private RestClient client;
    private String url;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mock";
        client = new HttpClientConfiguration().externalRestClient(RestClient.builder(),
                Duration.ofMillis(200), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void acceptsExplicitAuthorization() {
        respond(200, "{\"status\":\"success\",\"data\":{\"authorization\":true}}", 0);
        assertThatCode(() -> new AuthorizationClient(client, url).authorize()).doesNotThrowAnyException();
        assertThat(method).hasValue("GET");
    }

    @Test
    void rejectsNegativeAuthorization() {
        respond(200, "{\"status\":\"success\",\"data\":{\"authorization\":false}}", 0);
        assertAuthorizationFailure(HttpStatus.FORBIDDEN);
    }

    @Test
    void treatsForbiddenAsDenial() {
        respond(403, "{}", 0);
        assertAuthorizationFailure(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "", "invalid-json", "{\"status\":\"success\",\"data\":{}}",
            "{\"status\":\"fail\",\"data\":{\"authorization\":true}}"})
    void failsClosedOnUnexpectedResponse(String response) {
        respond(200, response, 0);
        assertAuthorizationFailure(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void handlesAuthorizationServerFailure() {
        respond(500, "{}", 0);
        assertAuthorizationFailure(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void boundsAuthorizationWaitTime() {
        respond(200, "{\"status\":\"success\",\"data\":{\"authorization\":true}}", 1000);
        assertAuthorizationFailure(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void sendsRecipientAndTransferInNotification() {
        respond(204, "", 0);
        var transfer = new Transfer(1L, 2L, new BigDecimal("10.00"));
        new NotificationClient(client, url).send(transfer, "payee@example.com");
        assertThat(method).hasValue("POST");
        assertThat(body.get()).contains(transfer.getId().toString(), "payee@example.com", "\"payee\":2", "10.00");
    }

    @Test
    void propagatesNotificationFailureForRetry() {
        respond(503, "{}", 0);
        assertThatThrownBy(() -> new NotificationClient(client, url)
                .send(new Transfer(1L, 2L, BigDecimal.ONE), "payee@example.com"))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void boundsNotificationWaitTime() {
        respond(204, "", 1000);
        assertThatThrownBy(() -> new NotificationClient(client, url)
                .send(new Transfer(1L, 2L, BigDecimal.ONE), "payee@example.com"))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void doesNotTreatRedirectAsDeliveredNotification() {
        respond(302, "", 0);
        assertThatThrownBy(() -> new NotificationClient(client, url)
                .send(new Transfer(1L, 2L, BigDecimal.ONE), "payee@example.com"))
                .isInstanceOf(RestClientException.class);
    }

    private void assertAuthorizationFailure(HttpStatus status) {
        assertThatThrownBy(() -> new AuthorizationClient(client, url).authorize())
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getStatus()).isEqualTo(status));
    }

    private void respond(int status, String response, long delayMillis) {
        server.createContext("/mock", exchange -> {
            try (exchange) {
                method.set(exchange.getRequestMethod());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
    }
}
