package dev.starrk.wallet;

import java.time.Clock;

import dev.starrk.wallet.assistant.AssistantService;
import dev.starrk.wallet.assistant.CategorizationService;
import dev.starrk.wallet.auth.AuthService;
import dev.starrk.wallet.auth.JwtProperties;
import dev.starrk.wallet.auth.SecurityConfiguration;
import dev.starrk.wallet.auth.TokenService;
import dev.starrk.wallet.transfer.TransferService;
import dev.starrk.wallet.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(properties = "security.jwt.secret=" + TestSecrets.JWT_SECRET)
@Import(SecurityConfiguration.class)
class DemoDepositProfileTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService users;

    @MockitoBean
    private TransferService transfers;

    @MockitoBean
    private AuthService auth;

    @MockitoBean
    private AssistantService assistant;

    @MockitoBean
    private CategorizationService categorization;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JwtProperties properties;

    @Test
    void demoDepositEndpointIsNotRegisteredWithoutDemoProfile() throws Exception {
        String token = new TokenService(encoder, properties, Clock.systemUTC()).issue(1).value();

        mvc.perform(post("/users/{id}/deposits", 1)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"value\":50.00}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(users, transfers, auth, assistant, categorization);
    }
}
