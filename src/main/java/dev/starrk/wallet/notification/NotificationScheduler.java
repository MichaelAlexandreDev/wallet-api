package dev.starrk.wallet.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notifications.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {
    private final NotificationService service;

    public NotificationScheduler(NotificationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${notifications.poll-delay-ms}", initialDelayString = "${notifications.poll-delay-ms}")
    public void deliverPending() {
        for (int count = 0; count < 10; count++) {
            if (!service.deliverNext()) {
                break;
            }
        }
    }
}
