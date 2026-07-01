package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.cabinet.ScanBalanceDto;
import io.okdocs.compliance.contracts.enums.BalanceTxnSource;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Атомарное управление балансом сканов + append-only леджер (§4.2, §2.7).
 * <p>
 * Все мутации работают внутри транзакции вызывающего; каждое движение пишет
 * {@link ScanBalanceTransaction}. {@code @Version} на {@link ScanBalance} ловит конкурентное списание
 * (oversell). Пополнение из оплаты — идемпотентный {@link #purchaseFromPayment} (Balance-first,
 * docs/PLAN-payments.md); {@code EXPIRE} пока не задействован.
 */
@Slf4j
@Service
public class ScanBalanceService {

    private static final int PERIOD_DAYS = 30;

    /**
     * Конец периода квоты, синхронный с {@code planRenewsAt}: {@code null} когда квоты нет (FREE,
     * quota=0 — сбрасывать нечего), иначе {@code now+30d} (активный PRO/BUSINESS). Держит одну
     * дату-правду и не показывает фронту «сброс», которого не будет.
     */
    private static Instant periodResetFor(int quota) {
        return quota > 0 ? Instant.now().plus(PERIOD_DAYS, ChronoUnit.DAYS) : null;
    }

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
        balance.setPeriodResetAt(periodResetFor(monthlyQuota)); // FREE (quota=0) → null
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.PLAN_GRANT, monthlyQuota, balance.available(), null, null,
                "Начальная месячная квота");
    }

    /**
     * Пополнение докупленными сканами без привязки к платежу — для ручных/internal-сценариев.
     * <p>
     * Кладёт в {@code purchasedRemaining} (не сгорает) и пишет {@link BalanceTxnType#PURCHASE} без
     * source (покупка — не списание из кармана).
     * <p>
     * <b>НЕ идемпотентен:</b> повторный вызов прибавит amount ещё раз. Для пополнения из оплаты
     * используйте {@link #purchaseFromPayment} (дедуп по {@code paymentId}); прямой вызов {@code purchase}
     * из webhook-flow привёл бы к двойному пополнению.
     */
    @Transactional
    public void purchase(Long userId, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Сумма покупки должна быть > 0, передано: " + amount);
        }
        ScanBalance balance = loadForUpdate(userId);
        balance.setPurchasedRemaining(balance.getPurchasedRemaining() + amount);
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.PURCHASE, amount, balance.available(), null, null,
                "Покупка " + amount + " скан(ов)");
    }

    /**
     * Идемпотентное пополнение баланса докупленными кредитами по факту оплаты (Balance-first,
     * docs/PLAN-payments.md). Вызывается из webhook'а оплаты ровно по одному платежу.
     * <p>
     * <b>Идемпотентность по {@code paymentId}</b> (в отличие от {@link #purchase}): повторная доставка
     * webhook'а того же платежа не пополнит баланс второй раз. Защита двойная, как в {@link #refund}:
     * (1) быстрый пре-чек по наличию PURCHASE; (2) партиальный уникальный индекс
     * {@code uq_balance_txns_purchase_per_payment} (V028) — параллельный второй PURCHASE по тому же
     * платежу падает с unique violation.
     * <p>
     * {@code doPurchaseFromPayment} — {@link Propagation#REQUIRES_NEW}: unique violation откатывает
     * только эту суб-транзакцию, а НЕ внешнюю транзакцию активации платежа ({@code PaymentActivationService}),
     * иначе outer tx стала бы rollback-only и упала бы на commit. Ловим исключение ВНЕ суб-транзакции.
     */
    public void purchaseFromPayment(Long userId, int credits, UUID paymentId) {
        if (credits <= 0) {
            throw new IllegalArgumentException("Кредитов к зачислению должно быть > 0, передано: " + credits);
        }
        if (txnRepository.existsByPaymentIdAndType(paymentId, BalanceTxnType.PURCHASE)) {
            log.debug("Пополнение по платежу {} уже выполнено — пропуск", paymentId);
            return;
        }
        try {
            self.doPurchaseFromPayment(userId, credits, paymentId);
        } catch (DataIntegrityViolationException e) {
            // Параллельный webhook уже записал PURCHASE — суб-транзакция откатилась (REQUIRES_NEW),
            // внешняя tx активации не задета. Идемпотентность сохранена.
            log.debug("Пополнение по платежу {} проиграло гонку (unique violation) — пропуск", paymentId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void doPurchaseFromPayment(Long userId, int credits, UUID paymentId) {
        ScanBalance balance = loadForUpdate(userId);
        balance.setPurchasedRemaining(balance.getPurchasedRemaining() + credits);
        balanceRepository.save(balance);
        // PURCHASE-строку пишем и флашим с явным flush: на дубле unique-индекс
        // (uq_balance_txns_purchase_per_payment) бросает DataIntegrityViolationException здесь,
        // откатывая всю транзакцию вместе с пополнением баланса выше.
        ScanBalanceTransaction txn = buildTxn(userId, BalanceTxnType.PURCHASE, credits,
                balance.available(), null, null, "Покупка " + credits + " скан(ов)");
        txn.setPaymentId(paymentId);
        txnRepository.saveAndFlush(txn);
    }

    /** Списание 1 скана. Сначала месячная квота, затем докупленное (в MVP — только квота). */
    @Transactional
    public void debit(Long userId, UUID scanId) {
        ScanBalance balance = loadForUpdate(userId);
        if (balance.available() <= 0) {
            throw new InsufficientScanBalanceException(userId);
        }
        BalanceTxnSource source;
        if (balance.getMonthlyQuota() - balance.getUsedThisPeriod() > 0) {
            balance.setUsedThisPeriod(balance.getUsedThisPeriod() + 1);
            source = BalanceTxnSource.MONTHLY;
        } else {
            balance.setPurchasedRemaining(balance.getPurchasedRemaining() - 1);
            source = BalanceTxnSource.PURCHASED;
        }
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.DEBIT, -1, balance.available(), scanId, source, null);
    }

    /**
     * Возврат 1 скана при FAILED, идемпотентно по scanId.
     * <p>
     * <b>Возвращаем только реально списанное</b> (есть DEBIT по этому scanId): FREE_MARKETING-скан
     * (в т.ч. запущенный залогиненным юзером) баланс не трогал, поэтому при его FAILED возврата быть
     * не должно — иначе юзер получил бы кредит, которого не платил. Refund = «отмена фактического
     * списания», а не «реакция на любое ScanFailedEvent с userId».
     * <p>
     * Двойная защита от гонки at-least-once Kafka: (1) быстрый пре-чек, (2) партиальный уникальный
     * индекс {@code uq_balance_txns_refund_per_scan} (V011) — второй параллельный REFUND по тому же
     * scanId падает с unique violation. Ловим её ВНЕ транзакции (она помечает tx rollback-only),
     * откатывая и изменение баланса, и benign-дубль леджера. Без этого два события вернули бы 1
     * дважды.
     */
    public void refund(Long userId, UUID scanId) {
        if (!txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.DEBIT)) {
            // Списания по этому скану не было (FREE_MARKETING / гость) — возвращать нечего.
            log.debug("Refund по скану {} пропущен: нет DEBIT (не chargeable-скан)", scanId);
            return;
        }
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
        ScanBalance balance = loadForUpdate(userId);
        // Возвращаем в тот же карман, из которого списали: source исходного DEBIT — источник правды,
        // а не эвристика «usedThisPeriod>0» (она вернула бы в monthly даже PURCHASED-списание).
        BalanceTxnSource source = txnRepository.findFirstByScanIdAndType(scanId, BalanceTxnType.DEBIT)
                .map(ScanBalanceTransaction::getSource)
                // Старые DEBIT-строки до V017 могли не иметь source — fallback на monthly.
                .orElse(BalanceTxnSource.MONTHLY);
        if (source == BalanceTxnSource.MONTHLY) {
            balance.setUsedThisPeriod(balance.getUsedThisPeriod() - 1);
        } else {
            balance.setPurchasedRemaining(balance.getPurchasedRemaining() + 1);
        }
        balanceRepository.save(balance);
        // REFUND-строку пишем и флашим ПОСЛЕДНЕЙ с явным flush: на дубле unique-индекс
        // (uq_balance_txns_refund_per_scan) бросает DataIntegrityViolationException здесь,
        // откатывая всю транзакцию вместе с изменением баланса выше.
        ScanBalanceTransaction txn = buildTxn(userId, BalanceTxnType.REFUND, 1, balance.available(),
                scanId, source, "Возврат за неуспешный скан");
        txnRepository.saveAndFlush(txn);
    }

    /** Сброс месячной квоты в начале нового периода. */
    @Transactional
    public void grantMonthly(Long userId, int quota) {
        ScanBalance balance = loadForUpdate(userId);
        balance.setMonthlyQuota(quota);
        balance.setUsedThisPeriod(0);
        balance.setPeriodResetAt(periodResetFor(quota)); // FREE/expire (quota=0) → null
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.PLAN_GRANT, quota, balance.available(), null, null, "Месячная квота тарифа");
    }

    /**
     * Месячная квота тарифа, выданная по оплате (активация PRO/BUSINESS, docs/PLAN-payments.md Этап 2).
     * Сбрасывает только monthly-карман (как {@link #grantMonthly}) — {@code purchasedRemaining} от
     * докупленных ONE_REPORT не трогается.
     * <p>
     * <b>Идемпотентно по {@code paymentId}</b> (как {@link #purchaseFromPayment}): повторный
     * webhook/status-poll не выдаст квоту дважды. Защита двойная: (1) пре-чек на наличие PLAN_GRANT по
     * платежу; (2) партиальный уникальный индекс {@code uq_balance_txns_plan_per_payment} (V029).
     * {@code doGrantMonthlyFromPayment} — {@link Propagation#REQUIRES_NEW}: unique violation откатывает
     * только суб-транзакцию, а не внешнюю tx активации платежа.
     */
    public void grantMonthlyFromPayment(Long userId, int quota, UUID paymentId) {
        if (txnRepository.existsByPaymentIdAndType(paymentId, BalanceTxnType.PLAN_GRANT)) {
            log.debug("Квота по платежу {} уже выдана — пропуск", paymentId);
            return;
        }
        try {
            self.doGrantMonthlyFromPayment(userId, quota, paymentId);
        } catch (DataIntegrityViolationException e) {
            // Параллельная активация уже записала PLAN_GRANT — суб-транзакция откатилась (REQUIRES_NEW),
            // внешняя tx активации не задета. Идемпотентность сохранена.
            log.debug("Выдача квоты по платежу {} проиграла гонку (unique violation) — пропуск", paymentId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void doGrantMonthlyFromPayment(Long userId, int quota, UUID paymentId) {
        ScanBalance balance = loadForUpdate(userId);
        balance.setMonthlyQuota(quota);
        balance.setUsedThisPeriod(0);
        balance.setPeriodResetAt(periodResetFor(quota)); // активный тариф → now+30d
        balanceRepository.save(balance);
        // saveAndFlush: на дубле unique-индекс бросает DataIntegrityViolationException здесь.
        ScanBalanceTransaction txn = buildTxn(userId, BalanceTxnType.PLAN_GRANT, quota,
                balance.available(), null, null, "Месячная квота тарифа (оплата)");
        txn.setPaymentId(paymentId);
        txnRepository.saveAndFlush(txn);
    }

    /** Ручная корректировка админом (± сканов). Идёт в покупленный «карман» (не сгорает). */
    @Transactional
    public void adminAdjust(Long userId, int amount, String reason) {
        ScanBalance balance = loadForUpdate(userId);
        balance.setPurchasedRemaining(balance.getPurchasedRemaining() + amount);
        balanceRepository.save(balance);
        writeTxn(userId, BalanceTxnType.ADMIN_ADJUST, amount, balance.available(), null, null, reason);
    }

    @Transactional(readOnly = true)
    public ScanBalanceDto getBalance(Long userId) {
        return toDto(load(userId));
    }

    private ScanBalance load(Long userId) {
        return balanceRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Баланс не найден для юзера " + userId));
    }

    private ScanBalance loadForUpdate(Long userId) {
        return balanceRepository.findWithLockByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Баланс не найден для юзера " + userId));
    }

    private void writeTxn(Long userId, BalanceTxnType type, int amount, int balanceAfter,
                          UUID scanId, BalanceTxnSource source, String note) {
        txnRepository.save(buildTxn(userId, type, amount, balanceAfter, scanId, source, note));
    }

    private ScanBalanceTransaction buildTxn(Long userId, BalanceTxnType type, int amount,
                                            int balanceAfter, UUID scanId, BalanceTxnSource source,
                                            String note) {
        ScanBalanceTransaction txn = new ScanBalanceTransaction();
        txn.setUserId(userId);
        txn.setType(type);
        txn.setAmount(amount);
        txn.setBalanceAfter(balanceAfter);
        txn.setScanId(scanId);
        txn.setSource(source);
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
