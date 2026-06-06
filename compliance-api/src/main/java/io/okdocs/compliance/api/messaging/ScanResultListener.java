package io.okdocs.compliance.api.messaging;

import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.contracts.event.ScanCompletedEvent;
import io.okdocs.compliance.contracts.event.ScanFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Api слушает результаты worker'а (§4.6).
 * <ul>
 *   <li>{@code ScanFailedEvent} → возврат списанного скана юзеру ({@code refund}, идемпотентно);</li>
 *   <li>{@code ScanCompletedEvent} (включая PARTIAL) → возврата нет (результат получен).</li>
 * </ul>
 * Списание делает api в {@code startScan}, поэтому и возврат держим в api, ближе к балансу.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanResultListener {

    private final ScanBalanceService balanceService;

    @KafkaListener(
            topics = "${compliance.kafka.topic.scan-failed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onScanFailed(ScanFailedEvent event) {
        log.info("Получен ScanFailedEvent для скана {}: {}", event.scanId(), event.errorMessage());
        if (event.userId() != null) {
            balanceService.refund(event.userId(), event.scanId());
        }
    }

    @KafkaListener(
            topics = "${compliance.kafka.topic.scan-completed}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onScanCompleted(ScanCompletedEvent event) {
        log.debug("Получен ScanCompletedEvent для скана {} (status={}, score={})",
                event.scanId(), event.status(), event.score());
        // Возврата нет: результат сформирован (COMPLETED/PARTIAL).
    }
}
