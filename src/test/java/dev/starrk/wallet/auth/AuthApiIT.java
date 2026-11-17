package dev.starrk.wallet.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starrk.wallet.TestSecrets;
import dev.starrk.wallet.transfer.TransferRepository;
import dev.starrk.wallet.user.UserRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"notifications.enabled=false", "security.jwt.secret=" + TestSecrets.JWT_SECRET})
@AutoConfigureMockMvc
@Testcontainers
class AuthApiIT {
    private static final String EMAIL = "ana@example.com";
    private static final String PASSWORD = "senha-segura-123";

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
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties properties;

    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        transfers.deleteAll();
        users.deleteAll();
        var created = mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Ana Silva","document":"11111111111","email":"%s","password":"%s","type":"COMMON"}
                        """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        userId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void loginReturnsAnExpiringBearerTokenForTheUser() throws Exception {
        login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isString());

        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void loginNormalizesTheEmailLikeRegistration() throws Exception {
        login("  ANA@Example.COM ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String wrongPassword = login(EMAIL, "senha-errada-123")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos."))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("ninguem@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void passwordsAboveTheBcryptLimitAreRejectedAsInvalidCredentials() throws Exception {
        // 40 caracteres, mas 80 bytes em UTF-8: BCrypt só considera os primeiros 72.
        login(EMAIL, "ç".repeat(40)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedLoginRequests() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void protectedEndpointRequiresAToken() throws Exception {
        mvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/users/me"));
    }

    @Test
    void rejectsExpiredTokens() throws Exception {
        var issuedAnHourAgo = new TokenService(encoder, properties,
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));

        assertRejected(issuedAnHourAgo.issue(userId).value());
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() throws Exception {
        var forgedProperties = new JwtProperties("an-attacker-controlled-secret-of-32-bytes",
                properties.issuer(), properties.ttl());
        var forger = new TokenService(new SecurityConfiguration().jwtEncoder(forgedProperties),
                forgedProperties, Clock.systemUTC());

        assertRejected(forger.issue(userId).value());
    }

    @Test
    void rejectsTamperedTokens() throws Exception {
        String[] parts = tokenFor(EMAIL, PASSWORD).split("\\.");
        String otherUser = base64Url("{\"iss\":\"wallet-api\",\"sub\":\"999\",\"exp\":%d}"
                .formatted(Instant.now().plus(Duration.ofHours(1)).getEpochSecond()));

        assertRejected(parts[0] + "." + otherUser + "." + parts[2]);
    }

    @Test
    void rejectsUnsignedTokens() throws Exception {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String claims = base64Url("{\"iss\":\"wallet-api\",\"sub\":\"%d\",\"exp\":%d}"
                .formatted(userId, Instant.now().plus(Duration.ofHours(1)).getEpochSecond()));

        assertRejected(header + "." + claims + ".");
    }

    @Test
    void rejectsTokensFromAnotherIssuer() throws Exception {
        var otherIssuer = new JwtProperties(TestSecrets.JWT_SECRET, "another-service", properties.ttl());

        assertRejected(new TokenService(encoder, otherIssuer, Clock.systemUTC()).issue(userId).value());
    }

    @Test
    void rejectsTokensWithoutExpiration() throws Exception {
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .issuedAt(Instant.now())
                .build();
        String neverExpires = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertRejected(neverExpires);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new LoginRequest(email, password))));
    }

    private String tokenFor(String email, String password) throws Exception {
        String body = login(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private void assertRejected(String token) throws Exception {
        mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.status").value(401));
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
