package dev.starrk.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.ObjectMapper;
import dev.starrk.wallet.auth.TokenService;
import dev.starrk.wallet.integration.AuthorizationClient;
import dev.starrk.wallet.integration.NotificationClient;
import dev.starrk.wallet.notification.NotificationService;
import dev.starrk.wallet.shared.ApiException;
import dev.starrk.wallet.transfer.TransferRepository;
import dev.starrk.wallet.transfer.TransferRequest;
import dev.starrk.wallet.transfer.TransferService;
import dev.starrk.wallet.user.User;
import dev.starrk.wallet.user.UserRepository;
import dev.starrk.wallet.user.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"notifications.enabled=false", "security.jwt.secret=" + TestSecrets.JWT_SECRET})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Testcontainers
class WalletApiIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired TransferRepository transfers;
    @Autowired TransferService service;
    @Autowired NotificationService notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @MockitoBean AuthorizationClient authorization;
    @MockitoBean NotificationClient notification;

    private User payer;
    private User payee;

    @BeforeEach
    void setUp() {
        reset(authorization, notification);
        transfers.deleteAll();
        users.deleteAll();
        payer = createUser("11111111111", UserType.COMMON, "100.00");
        payee = createUser("22222222222222", UserType.MERCHANT, "0.00");
    }

    @Test
    void transfersAndExposesAReadableReceipt() throws Exception {
        var result = mvc.perform(transferPost(request("25.10"), newIdempotencyKey()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.notificationStatus").value("PENDING"))
                .andReturn();
        mvc.perform(get(result.getResponse().getHeader("Location")).header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(25.10));
        assertBalances("74.90", "25.10");
        assertThat(transfers.count()).isEqualTo(1);
        verify(authorization).authorize();
        verifyNoInteractions(notification);
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
        mvc.perform(post("/transfer").header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request("10.00"))))
                .andExpect(status().isBadRequest());

        assertBalances("100.00", "0.00");
        assertThat(transfers.count()).isZero();
        verifyNoInteractions(authorization);
    }

    @Test
    void rejectsBlankOrOverlongIdempotencyKeys() throws Exception {
        mvc.perform(transferPost(request("10.00"), "   ")).andExpect(status().isBadRequest());
        mvc.perform(transferPost(request("10.00"), "x".repeat(129))).andExpect(status().isBadRequest());

        assertBalances("100.00", "0.00");
        assertThat(transfers.count()).isZero();
        verifyNoInteractions(authorization);
    }

    @Test
    void replaysTheSameReceiptForNumericallyEquivalentAmounts() throws Exception {
        String key = "k".repeat(128);
        var first = mvc.perform(transferPost(request("10.0"), key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notificationStatus").value("PENDING"))
                .andReturn();
        String location = first.getResponse().getHeader("Location");
        var originalReceipt = json.readTree(first.getResponse().getContentAsString());
        String transferId = originalReceipt.get("id").asString();
        String createdAt = originalReceipt.get("createdAt").asString();

        mvc.perform(transferPost(request("10.00"), key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", location))
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.value").value(10.0))
                .andExpect(jsonPath("$.notificationStatus").value("PENDING"));

        assertBalances("90.00", "10.00");
        assertThat(transfers.count()).isEqualTo(1);
        var storedTransfer = transfers.findByPayerIdAndIdempotencyKey(payer.getId(), key).orElseThrow();
        assertThat(storedTransfer.getRequestFingerprint()).matches("[0-9a-f]{64}");
        verify(authorization).authorize();
    }

    @Test
    void storesTheOptionalDescriptionOnTheReceipt() throws Exception {
        var created = mvc.perform(transferPost(new TransferRequest(new BigDecimal("12.00"), payee.getId(),
                        "Almoço de sexta"), newIdempotencyKey()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Almoço de sexta"))
                .andReturn();

        mvc.perform(get(created.getResponse().getHeader("Location")).header(HttpHeaders.AUTHORIZATION, bearer(payee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Almoço de sexta"));
        mvc.perform(transferPost(request("1.00"), newIdempotencyKey()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "linha 1\nlinha 2", "tab\there", "bell\u0007"})
    void rejectsBlankOrControlCharacterDescriptions(String description) throws Exception {
        mvc.perform(transferPost(new TransferRequest(BigDecimal.ONE, payee.getId(), description), newIdempotencyKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"));
        assertBalances("100.00", "0.00");
        verifyNoInteractions(authorization);
    }

    @Test
    void rejectsDescriptionsLongerThan140Characters() throws Exception {
        mvc.perform(transferPost(new TransferRequest(BigDecimal.ONE, payee.getId(), "x".repeat(141)), newIdempotencyKey()))
                .andExpect(status().isBadRequest());
        mvc.perform(transferPost(new TransferRequest(BigDecimal.ONE, payee.getId(), "x".repeat(140)), newIdempotencyKey()))
                .andExpect(status().isCreated());
    }

    @Test
    void reusingAKeyWithAnotherDescriptionIsAConflict() throws Exception {
        String key = "description-key";
        mvc.perform(transferPost(new TransferRequest(BigDecimal.TEN, payee.getId(), "Aluguel"), key))
                .andExpect(status().isCreated());

        mvc.perform(transferPost(new TransferRequest(BigDecimal.TEN, payee.getId(), "Outra coisa"), key))
                .andExpect(status().isConflict());
        mvc.perform(transferPost(new TransferRequest(BigDecimal.TEN, payee.getId(), "Aluguel"), key))
                .andExpect(status().isCreated());
        assertBalances("90.00", "10.00");
    }

    @Test
    void rejectsReusingAnIdempotencyKeyWithDifferentPayload() throws Exception {
        String key = "conflicting-key";
        mvc.perform(transferPost(request("10.00"), key)).andExpect(status().isCreated());

        mvc.perform(transferPost(request("20.00"), key)).andExpect(status().isConflict());

        assertBalances("90.00", "10.00");
        assertThat(transfers.count()).isEqualTo(1);
        verify(authorization).authorize();
    }

    @Test
    void scopesIdempotencyKeysByPayer() {
        var otherPayer = createUser("33333333333", UserType.COMMON, "50.00");
        String key = "shared-key";

        var first = service.transfer(payer.getId(), request("10.00"), key);
        var second = service.transfer(otherPayer.getId(), new TransferRequest(new BigDecimal("10.00"), payee.getId()), key);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(transfers.count()).isEqualTo(2);
        assertBalances("90.00", "20.00");
        assertThat(balance(otherPayer)).isEqualByComparingTo("40.00");
        verify(authorization, times(2)).authorize();
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneTransfer() throws Exception {
        String key = "concurrent-key";
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<dev.starrk.wallet.transfer.TransferResponse> attempt = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not start");
            }
            return service.transfer(payer.getId(), request("30.00"), key);
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstReceipt = first.get(15, TimeUnit.SECONDS);
            var secondReceipt = second.get(15, TimeUnit.SECONDS);
            assertThat(firstReceipt).isEqualTo(secondReceipt);
        }

        assertBalances("70.00", "30.00");
        assertThat(transfers.count()).isEqualTo(1);
        verify(authorization).authorize();
    }

    @Test
    void authorizationFailureRollsBackTheKeySoItCanBeRetried() throws Exception {
        String key = "retry-after-authorization-failure";
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Autorizador indisponível"))
                .doNothing().when(authorization).authorize();

        mvc.perform(transferPost(request("30.00"), key)).andExpect(status().isServiceUnavailable());
        assertBalances("100.00", "0.00");
        assertThat(transfers.count()).isZero();

        mvc.perform(transferPost(request("30.00"), key)).andExpect(status().isCreated());

        assertBalances("70.00", "30.00");
        assertThat(transfers.count()).isEqualTo(1);
        verify(authorization, times(2)).authorize();
    }

    @Test
    void transfersBetweenCommonUsersAndAllowsExactBalance() {
        var other = createUser("33333333333", UserType.COMMON, "0.00");
        service.transfer(payer.getId(), new TransferRequest(new BigDecimal("100.00"), other.getId()), newIdempotencyKey());
        assertThat(balance(payer)).isZero();
        assertThat(balance(other)).isEqualByComparingTo("100.00");
    }

    @Test
    void rejectsMerchantAsPayer() throws Exception {
        mvc.perform(transferPost(payee, new TransferRequest(BigDecimal.ONE, payer.getId()), newIdempotencyKey()))
                .andExpect(status().isUnprocessableContent());
        assertBalances("100.00", "0.00");
        assertThat(transfers.count()).isZero();
        verifyNoInteractions(authorization);
    }

    @Test
    void rejectsInsufficientBalance() throws Exception {
        mvc.perform(transferPost(request("100.01"), newIdempotencyKey()))
                .andExpect(status().isUnprocessableContent());
        assertBalances("100.00", "0.00");
        assertThat(transfers.count()).isZero();
        verifyNoInteractions(authorization);
    }

    @Test
    void rejectsSelfTransfer() throws Exception {
        mvc.perform(transferPost(new TransferRequest(BigDecimal.ONE, payer.getId()), newIdempotencyKey()))
                .andExpect(status().isUnprocessableContent());
        assertBalances("100.00", "0.00");
        verifyNoInteractions(authorization);
    }

    @Test
    void rejectsUnknownUser() throws Exception {
        mvc.perform(transferPost(new TransferRequest(BigDecimal.ONE, Long.MAX_VALUE), newIdempotencyKey()))
                .andExpect(status().isNotFound());
        assertBalances("100.00", "0.00");
        verifyNoInteractions(authorization);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 503})
    void rollsBackBothWalletsWhenAuthorizationFails(int statusCode) throws Exception {
        doThrow(new ApiException(HttpStatus.valueOf(statusCode), "Falha do autorizador"))
                .when(authorization).authorize();
        mvc.perform(transferPost(request("50.00"), newIdempotencyKey()))
                .andExpect(status().is(statusCode));
        assertBalances("100.00", "0.00");
        assertThat(transfers.count()).isZero();
        assertThat(notifications.deliverNext()).isFalse();
        verifyNoInteractions(notification);
    }

    @Test
    void rollsBackDebitWhenDestinationWouldOverflow() {
        jdbc.update("UPDATE users SET balance = 99999999999999999.99 WHERE id = ?", payee.getId());
        assertThatThrownBy(() -> service.transfer(payer.getId(), request("0.01"), newIdempotencyKey())).isInstanceOf(ApiException.class);
        assertBalances("100.00", "99999999999999999.99");
        assertThat(transfers.count()).isZero();
        verifyNoInteractions(authorization);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "100000000000000000.00", "null"})
    void rejectsInvalidMoneyAtTheHttpBoundary(String value) throws Exception {
        mvc.perform(transferPost("{\"value\":" + value + ",\"payee\":" + payee.getId() + "}", newIdempotencyKey()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertBalances("100.00", "0.00");
        verifyNoInteractions(authorization);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "{\"value\":1,\"payee\":1.5}", "{\"value\":1,\"payee\":-1}",
            "{\"value\":1,\"payee\":2,\"extra\":true}"})
    void rejectsMalformedOrIncompleteRequests(String body) throws Exception {
        mvc.perform(transferPost(body, newIdempotencyKey()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authorization);
    }

    @Test
    void concurrentPaymentsCannotSpendTheSameBalanceTwice() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not start");
            }
            try {
                service.transfer(payer.getId(), request("80.00"), newIdempotencyKey());
                return true;
            } catch (ApiException exception) {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertBalances("20.00", "80.00");
        assertThat(transfers.count()).isEqualTo(1);
        verify(authorization).authorize();
    }

    @Test
    void oppositeConcurrentTransfersCompleteWithoutDeadlock() throws Exception {
        var other = createUser("33333333333", UserType.COMMON, "100.00");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return service.transfer(payer.getId(), new TransferRequest(new BigDecimal("10.00"), other.getId()), newIdempotencyKey());
            });
            var second = executor.submit(() -> {
                start.await();
                return service.transfer(other.getId(), new TransferRequest(new BigDecimal("20.00"), payer.getId()), newIdempotencyKey());
            });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(15, TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(balance(payer)).isEqualByComparingTo("110.00");
        assertThat(balance(other)).isEqualByComparingTo("90.00");
        assertThat(transfers.count()).isEqualTo(2);
    }

    @Test
    void retriesNotificationsWithoutRepeatingThePayment() {
        var receipt = service.transfer(payer.getId(), request("30.00"), newIdempotencyKey());
        doThrow(new ResourceAccessException("timeout")).when(notification).send(any(), anyString());
        assertThat(notifications.deliverNext()).isTrue();
        var pending = transfers.findById(receipt.id()).orElseThrow();
        assertThat(pending.getNotifiedAt()).isNull();
        assertThat(pending.getNotificationAttempts()).isEqualTo(1);
        assertThat(pending.getNotificationStatus().name()).isEqualTo("PENDING");
        assertThat(pending.getNextNotificationAt()).isAfter(pending.getCreatedAt());
        assertThat(notifications.deliverNext()).isFalse();
        assertBalances("70.00", "30.00");

        jdbc.update("UPDATE transfers SET next_notification_at = CURRENT_TIMESTAMP WHERE id = ?", receipt.id());
        doNothing().when(notification).send(any(), anyString());
        assertThat(notifications.deliverNext()).isTrue();
        var delivered = transfers.findById(receipt.id()).orElseThrow();
        assertThat(delivered.getNotifiedAt()).isNotNull();
        assertThat(delivered.getNotificationAttempts()).isEqualTo(2);
        assertThat(delivered.getNotificationStatus().name()).isEqualTo("SENT");
        assertThat(service.find(receipt.id(), payer.getId()).notificationStatus()).isEqualTo("SENT");
        assertThat(notifications.deliverNext()).isFalse();
        assertBalances("70.00", "30.00");
        assertThat(transfers.count()).isEqualTo(1);
        verify(notification, times(2)).send(any(), anyString());
    }

    @Test
    void marksNotificationFailedAfterMaximumAttemptsWithoutRepeatingTransfer() throws Exception {
        var receipt = service.transfer(payer.getId(), request("30.00"), newIdempotencyKey());
        doThrow(new ResourceAccessException("timeout")).when(notification).send(any(), anyString());

        for (int attempt = 1; attempt <= 5; attempt++) {
            if (attempt > 1) {
                jdbc.update("UPDATE transfers SET next_notification_at = CURRENT_TIMESTAMP WHERE id = ?", receipt.id());
            }
            assertThat(notifications.deliverNext()).isTrue();
            var saved = transfers.findById(receipt.id()).orElseThrow();
            assertThat(saved.getNotificationAttempts()).isEqualTo(attempt);
            if (attempt < 5) {
                assertThat(saved.getNotificationStatus().name()).isEqualTo("PENDING");
            }
        }

        var failed = transfers.findById(receipt.id()).orElseThrow();
        assertThat(failed.getNotificationStatus().name()).isEqualTo("FAILED");
        assertThat(failed.getNotifiedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT notification_status FROM transfers WHERE id = ?", String.class,
                receipt.id())).isEqualTo("FAILED");
        mvc.perform(get("/transfer/{id}", receipt.id()).header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationStatus").value("FAILED"));
        assertThat(notifications.deliverNext()).isFalse();
        assertBalances("70.00", "30.00");
        assertThat(transfers.count()).isEqualTo(1);
        verify(notification, times(5)).send(any(), anyString());
    }

    @Test
    void createsUserWithZeroBalanceAndHashedPassword() throws Exception {
        var result = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(registration("33333333333", "ANA@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ana@example.com"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.document").doesNotExist())
                .andReturn();
        long createdId = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get(result.getResponse().getHeader("Location"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(createdId).value()))
                .andExpect(status().isOk());
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = 'ana@example.com'", String.class);
        assertThat(hash).isNotEqualTo("senha-segura");
        assertThat(new BCryptPasswordEncoder().matches("senha-segura", hash)).isTrue();
    }

    @Test
    void rejectsDuplicateDocumentAndCaseInsensitiveEmail() throws Exception {
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content(registration("11111111111", "new@example.com")))
                .andExpect(status().isConflict());
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content(registration("33333333333", "11111111111@EXAMPLE.COM")))
                .andExpect(status().isConflict());
    }

    @Test
    void databaseAlsoEnforcesUniqueDocumentsAndEmails() {
        assertThatThrownBy(() -> createUser("11111111111", UserType.COMMON, "0.00"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> users.saveAndFlush(new User("Outra pessoa", "33333333333",
                payer.getEmail(), "test-hash", UserType.COMMON)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidRegistration() throws Exception {
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":" ","document":"123","email":"invalid","password":"123","type":"COMMON"}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void demoDepositFundsTheWallet() throws Exception {
        mvc.perform(post("/users/{id}/deposits", payer.getId()).header(HttpHeaders.AUTHORIZATION, bearer(payer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":50.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(150));
        assertBalances("150.00", "0.00");
    }

    @Test
    void unknownReceiptReturns404() throws Exception {
        mvc.perform(get("/transfer/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(payer)))
                .andExpect(status().isNotFound());
    }

    private User createUser(String document, UserType type, String balance) {
        var user = new User("Pessoa de teste", document, document + "@example.com", "test-hash", type);
        if (new BigDecimal(balance).signum() > 0) {
            user.getWallet().credit(new BigDecimal(balance));
        }
        return users.saveAndFlush(user);
    }

    private TransferRequest request(String value) {
        return new TransferRequest(new BigDecimal(value), payee.getId());
    }

    private MockHttpServletRequestBuilder transferPost(TransferRequest request, String idempotencyKey) throws Exception {
        return transferPost(payer, request, idempotencyKey);
    }

    private MockHttpServletRequestBuilder transferPost(User from, TransferRequest request, String idempotencyKey)
            throws Exception {
        return transferPost(from, json.writeValueAsString(request), idempotencyKey);
    }

    private MockHttpServletRequestBuilder transferPost(String body, String idempotencyKey) {
        return transferPost(payer, body, idempotencyKey);
    }

    private MockHttpServletRequestBuilder transferPost(User from, String body, String idempotencyKey) {
        return post("/transfer").header(HttpHeaders.AUTHORIZATION, bearer(from))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String bearer(User user) {
        return "Bearer " + tokens.issue(user.getId()).value();
    }

    private String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    private BigDecimal balance(User user) {
        return users.findById(user.getId()).orElseThrow().getWallet().getBalance();
    }

    private void assertBalances(String payerBalance, String payeeBalance) {
        assertThat(balance(payer)).isEqualByComparingTo(payerBalance);
        assertThat(balance(payee)).isEqualByComparingTo(payeeBalance);
    }

    private String registration(String document, String email) {
        return """
                {"fullName":"Ana Silva","document":"%s","email":"%s","password":"senha-segura","type":"COMMON"}
                """.formatted(document, email);
    }
}
