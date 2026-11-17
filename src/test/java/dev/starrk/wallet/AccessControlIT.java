package dev.starrk.wallet;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starrk.wallet.auth.JwtProperties;
import dev.starrk.wallet.auth.TokenService;
import dev.starrk.wallet.integration.AuthorizationClient;
import dev.starrk.wallet.integration.NotificationClient;
import dev.starrk.wallet.transfer.TransferRepository;
import dev.starrk.wallet.user.User;
import dev.starrk.wallet.user.UserRepository;
import dev.starrk.wallet.user.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Prova que cada usuário só enxerga e movimenta a própria carteira. */
@SpringBootTest(properties = {"notifications.enabled=false", "security.jwt.secret=" + TestSecrets.JWT_SECRET})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Testcontainers
class AccessControlIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

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
    @Autowired TokenService tokens;
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties properties;
    @MockitoBean AuthorizationClient authorization;
    @MockitoBean NotificationClient notification;

    private User ana;
    private User bia;
    private User store;

    @BeforeEach
    void setUp() {
        transfers.deleteAll();
        users.deleteAll();
        ana = createUser("11111111111", UserType.COMMON, "100.00");
        bia = createUser("33333333333", UserType.COMMON, "50.00");
        store = createUser("22222222222222", UserType.MERCHANT, "0.00");
    }

    @Test
    void everyWalletEndpointRequiresAToken() throws Exception {
        var anonymous = new MockHttpServletRequestBuilder[] {
                get("/users/me"),
                get("/users/{id}", ana.getId()),
                get("/transfer/{id}", UUID.randomUUID()),
                transferBody(store.getId(), "1.00"),
                post("/users/{id}/deposits", ana.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":10.00}"),
        };
        for (var request : anonymous) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertBalances("100.00", "50.00", "0.00");
        verifyNoInteractions(authorization);
    }

    @Test
    void transferAlwaysDebitsTheAuthenticatedUser() throws Exception {
        mvc.perform(transferAs(ana, store.getId(), "25.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payer").value(ana.getId()));

        assertBalances("75.00", "50.00", "25.00");
    }

    @Test
    void cannotSpendFromAnotherWalletByNamingItAsPayer() throws Exception {
        String attack = "{\"value\":50.00,\"payer\":%d,\"payee\":%d}".formatted(bia.getId(), ana.getId());

        mvc.perform(post("/transfer").header(HttpHeaders.AUTHORIZATION, bearer(ana))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(attack))
                .andExpect(status().isBadRequest());

        assertBalances("100.00", "50.00", "0.00");
        assertThat(transfers.count()).isZero();
        verifyNoInteractions(authorization);
    }

    @Test
    void usersOnlySeeTheirOwnWallet() throws Exception {
        mvc.perform(get("/users/{id}", ana.getId()).header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.0));
        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, bearer(bia)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bia.getId()));

        String othersWallet = mvc.perform(get("/users/{id}", bia.getId()).header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String missingUser = mvc.perform(get("/users/{id}", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, bearer(ana)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Mesma resposta: não dá para descobrir quais IDs existem.
        assertThat(withoutInstance(othersWallet)).isEqualTo(withoutInstance(missingUser));
    }

    @Test
    void receiptIsVisibleOnlyToPayerAndPayee() throws Exception {
        var created = mvc.perform(transferAs(ana, store.getId(), "10.00"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);

        mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, bearer(ana))).andExpect(status().isOk());
        mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, bearer(store))).andExpect(status().isOk());

        String stranger = mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, bearer(bia)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.value").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get("/transfer/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(bia)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutInstance(stranger)).isEqualTo(withoutInstance(missing));
    }

    @Test
    void reusingAnotherUsersIdempotencyKeyDoesNotReturnTheirReceipt() throws Exception {
        String key = "same-key-for-everyone";
        String anasReceipt = mvc.perform(transferAs(ana, store.getId(), "10.00", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String biasReceipt = mvc.perform(transferAs(bia, store.getId(), "10.00", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payer").value(bia.getId()))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(biasReceipt).get("id")).isNotEqualTo(json.readTree(anasReceipt).get("id"));
        assertBalances("90.00", "40.00", "20.00");
    }

    @Test
    void cannotDepositIntoAnotherWallet() throws Exception {
        mvc.perform(post("/users/{id}/deposits", bia.getId()).header(HttpHeaders.AUTHORIZATION, bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":10.00}"))
                .andExpect(status().isNotFound());

        assertBalances("100.00", "50.00", "0.00");
    }

    @Test
    void readOnlyTokenCanQueryButCannotMoveMoney() throws Exception {
        String readOnly = "Bearer " + tokenWithScopes(ana, "wallet:read");

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, readOnly)).andExpect(status().isOk());
        mvc.perform(transferBody(store.getId(), "10.00").header(HttpHeaders.AUTHORIZATION, readOnly))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("insufficient_scope")));
        mvc.perform(post("/users/{id}/deposits", ana.getId()).header(HttpHeaders.AUTHORIZATION, readOnly)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":10.00}"))
                .andExpect(status().isForbidden());

        assertBalances("100.00", "50.00", "0.00");
        verifyNoInteractions(authorization);
    }

    @Test
    void tokenWithoutScopesCannotReadOrMoveMoney() throws Exception {
        String noScopes = "Bearer " + tokenWithScopes(ana, "");

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, noScopes)).andExpect(status().isForbidden());
        mvc.perform(transferBody(store.getId(), "10.00").header(HttpHeaders.AUTHORIZATION, noScopes))
                .andExpect(status().isForbidden());
        assertBalances("100.00", "50.00", "0.00");
    }

    @Test
    void rejectsTokensWhoseSubjectIsNotAUserId() throws Exception {
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject("ana@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
                .claim("scope", "wallet:read transfer:write")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder transferAs(User from, long payeeId, String value) {
        return transferAs(from, payeeId, value, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder transferAs(User from, long payeeId, String value, String key) {
        return transferBody(payeeId, value, key).header(HttpHeaders.AUTHORIZATION, bearer(from));
    }

    private static MockHttpServletRequestBuilder transferBody(long payeeId, String value) {
        return transferBody(payeeId, value, UUID.randomUUID().toString());
    }

    private static MockHttpServletRequestBuilder transferBody(long payeeId, String value, String key) {
        return post("/transfer").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":%s,\"payee\":%d}".formatted(value, payeeId));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.issue(user.getId()).value();
    }

    private String tokenWithScopes(User user, String scopes) {
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(user.getId()))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
                .claim("scope", scopes)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String withoutInstance(String problem) throws Exception {
        var node = (ObjectNode) json.readTree(problem);
        node.remove("instance");
        return node.toString();
    }

    private User createUser(String document, UserType type, String balance) {
        var user = new User("Pessoa de teste", document, document + "@example.com", "test-hash", type);
        if (new BigDecimal(balance).signum() > 0) {
            user.getWallet().credit(new BigDecimal(balance));
        }
        return users.saveAndFlush(user);
    }

    private void assertBalances(String anaBalance, String biaBalance, String storeBalance) {
        assertThat(balance(ana)).isEqualByComparingTo(anaBalance);
        assertThat(balance(bia)).isEqualByComparingTo(biaBalance);
        assertThat(balance(store)).isEqualByComparingTo(storeBalance);
    }

    private BigDecimal balance(User user) {
        return users.findById(user.getId()).orElseThrow().getWallet().getBalance();
    }
}
