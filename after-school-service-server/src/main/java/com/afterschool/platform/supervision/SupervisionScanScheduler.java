package com.afterschool.platform.supervision;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.supervision.scan",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SupervisionScanScheduler {

    private final SupervisionService service;

    public SupervisionScanScheduler(SupervisionService service) {
        this.service = service;
    }

    @Scheduled(
            cron = "${app.supervision.scan.cron:0 0 2 * * *}",
            zone = "${app.supervision.scan.zone:Asia/Shanghai}")
    public void scanScheduled() {
        service.scanScheduled();
    }
}
