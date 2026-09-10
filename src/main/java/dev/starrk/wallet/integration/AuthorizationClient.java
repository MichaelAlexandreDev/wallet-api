package dev.starrk.wallet.integration;

import dev.starrk.wallet.shared.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AuthorizationClient {
    private final RestClient client;
    private final String url;

    public AuthorizationClient(RestClient client, @Value("${services.authorization-url}") String url) {
        this.client = client;
        this.url = url;
    }

    public void authorize() {
        try {
            var response = client.get().uri(url).retrieve().body(AuthorizationResponse.class);
            if (response == null || !"success".equals(response.status()) || response.data() == null
                    || response.data().authorization() == null) {
                throw unavailable();
            }
            if (!response.data().authorization()) {
                throw denied();
            }
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 403) {
                throw denied();
            }
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "Transferência recusada pelo autorizador.");
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Serviço autorizador indisponível. Tente novamente mais tarde.");
    }

    record AuthorizationResponse(String status, AuthorizationData data) {
    }

    record AuthorizationData(Boolean authorization) {
    }
}
