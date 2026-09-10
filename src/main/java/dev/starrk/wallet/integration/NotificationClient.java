package dev.starrk.wallet.integration;

import java.math.BigDecimal;
import java.util.UUID;

import dev.starrk.wallet.transfer.Transfer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class NotificationClient {
    private final RestClient client;
    private final String url;

    public NotificationClient(RestClient client, @Value("${services.notification-url}") String url) {
        this.client = client;
        this.url = url;
    }

    public void send(Transfer transfer, String email) {
        var response = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .body(new NotificationRequest(transfer.getId(), transfer.getPayeeId(), email, transfer.getAmount()))
                .retrieve().toBodilessEntity();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RestClientException("Notificador não confirmou o recebimento.");
        }
    }

    record NotificationRequest(UUID transferId, Long payee, String email, BigDecimal value) {
    }
}
