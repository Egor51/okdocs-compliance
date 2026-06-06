package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.cabinet.ScanBalanceDto;
import io.okdocs.compliance.contracts.enums.BalanceTxnType;
import io.okdocs.compliance.contracts.exception.InsufficientScanBalanceException;
import io.okdocs.compliance.persistence.billing.ScanBalance;
import io.okdocs.compliance.persistence.billing.ScanBalanceRepository;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransaction;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Атомарное управление балансом сканов + append-only леджер (§4.2, §2.7).
 * <p>
 * Все мутации работают внутри транзакции вызывающего (например {@code startScan}); каждое
 * движение пишет {@link ScanBalanceTransaction}. {@code @Version} на {@link ScanBalance} ловит
 * конкурентное списание (oversell). В MVP {@code purchasedRemaining} всегда 0 (§2.7), поэтому
 * {@code purchase} и {@code EXPIRE} не задействованы.
 */
@Slf4j
@Service
public class ScanBalanceService {

    private static final int PERIOD_DAYS = 30;

    private final ScanBalanceRepository balanceRepository;
    private final ScanBalanceTransactionRepository txnRepository;
    /** Self-reference для вызова @Transactional doRefund через прокси (self-invocation иначе не AOP). */
    private final ScanBalanceService self;

    public ScanBalanceService(ScanBalanceRepository balanceRepository,
                              ScanBalanceTransactionRepository txnRepository,
                              @Lazy ScanBalanceService self) {
        this.balanceRepository = balanceRepository;
        this.txnRepository = txnRepository;
        this.self = self;
    }

    /** Создаёт баланс для нового юзера с месячной квотой по тарифу (вызывается из register). */
    @Transactional
    public void createForNewUser(Long userId, int monthlyQuota) {
        ScanBalance balance = new ScanBalance();
        balance.setUserId(userId);
        balance.setMonthlyQuota(monthlyQuota);
        balance.setUsedThisPeriod(0);
        balance.setPurchasedRemaining(0);
        balance.setPeriodResetAt(Instant.now().plus(PERIOD_DAYS, ChronoUnit.DAYS));
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.PLAN_GRANT, monthlyQuota, balance.available(), null,
                "Начальная месячная квота");
    }

    /** Списание 1 скана. Сначала месячная квота, затем докупленное (в MVP — только квота). */
    @Transactional
    public void debit(Long userId, UUID scanId) {
        ScanBalance balance = load(userId);
        if (balance.available() <= 0) {
            throw new InsufficientScanBalanceException(userId);
        }
        if (balance.getMonthlyQuota() - balance.getUsedThisPeriod() > 0) {
            balance.setUsedThisPeriod(balance.getUsedThisPeriod() + 1);
        } else {
            balance.setPurchasedRemaining(balance.getPurchasedRemaining() - 1);
        }
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.DEBIT, -1, balance.available(), scanId, null);
    }

    /**
     * Возврат 1 скана при FAILED, идемпотентно по scanId.
     * <p>
     * Двойная защита от гонки at-least-once Kafka: (1) быстрый пре-чек, (2) партиальный уникальный
     * индекс {@code uq_balance_txns_refund_per_scan} (V011) — второй параллельный REFUND по тому же
     * scanId падает с unique violation. Ловим её ВНЕ транзакции (она помечает tx rollback-only),
     * откатывая и изменение баланса, и benign-дубль леджера. Без этого два события вернули бы 1
     * дважды.
     */
    public void refund(Long userId, UUID scanId) {
        if (txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.REFUND)) {
            log.debug("Refund по скану {} уже выполнен — пропуск", scanId);
            return;
        }
        try {
            self.doRefund(userId, scanId);
        } catch (DataIntegrityViolationException e) {
            // Параллельное событие уже записало REFUND — наша транзакция откатилась целиком
            // (включая изменение баланса). Идемпотентность сохранена.
            log.debug("Refund по скану {} проиграл гонку (unique violation) — пропуск", scanId);
        }
    }

    @Transactional
    public void doRefund(Long userId, UUID scanId) {
        ScanBalance balance = load(userId);
        // В MVP всё списание идёт из месячной квоты, туда же и возвращаем.
        if (balance.getUsedThisPeriod() > 0) {
            balance.setUsedThisPeriod(balance.getUsedThisPeriod() - 1);
        } else {
            balance.setPurchasedRemaining(balance.getPurchasedRemaining() + 1);
        }
        balanceRepository.save(balance);
        // REFUND-строку пишем и флашим ПОСЛЕДНЕЙ с явным flush: на дубле unique-индекс
        // (uq_balance_txns_refund_per_scan) бросает DataIntegrityViolationException здесь,
        // откатывая всю транзакцию вместе с изменением баланса выше.
        ScanBalanceTransaction txn = buildTxn(userId, BalanceTxnType.REFUND, 1, balance.available(),
                scanId, "Возврат за неуспешный скан");
        txnRepository.saveAndFlush(txn);
    }

    /** Сброс месячной квоты в начале нового периода. */
    @Transactional
    public void grantMonthly(Long userId, int quota) {
        ScanBalance balance = load(userId);
        balance.setMonthlyQuota(quota);
        balance.setUsedThisPeriod(0);
        balance.setPeriodResetAt(Instant.now().plus(PERIOD_DAYS, ChronoUnit.DAYS));
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.PLAN_GRANT, quota, balance.available(), null, "Месячная квота тарифа");
    }

    /** Ручная корректировка админом (± сканов). Идёт в покупленный «карман» (не сгорает). */
    @Transactional
    public void adminAdjust(Long userId, int amount, String reason) {
        ScanBalance balance = load(userId);
        balance.setPurchasedRemaining(balance.getPurchasedRemaining() + amount);
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.ADMIN_ADJUST, amount, balance.available(), null, reason);
    }

    @Transactional(readOnly = true)
    public ScanBalanceDto getBalance(Long userId) {
        return toDto(load(userId));
    }

    private ScanBalance load(Long userId) {
        return balanceRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Баланс не найден для юзера " + userId));
    }

    private void writeTxn(Long userId, BalanceTxnType type, int amount, int balanceAfter,
                          UUID scanId, String note) {
        txnRepository.save(buildTxn(userId, type, amount, balanceAfter, scanId, note));
    }

    private ScanBalanceTransaction buildTxn(Long userId, BalanceTxnType type, int amount,
                                            int balanceAfter, UUID scanId, String note) {
        ScanBalanceTransaction txn = new ScanBalanceTransaction();
        txn.setUserId(userId);
        txn.setType(type);
        txn.setAmount(amount);
        txn.setBalanceAfter(balanceAfter);
        txn.setScanId(scanId);
        txn.setNote(note);
        return txn;
    }

    public static ScanBalanceDto toDto(ScanBalance b) {
        return new ScanBalanceDto(
                b.getMonthlyQuota(),
                b.getUsedThisPeriod(),
                b.getPurchasedRemaining(),
                b.available(),
                b.getPeriodResetAt());
    }
}
