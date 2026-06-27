package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.BalanceTxnSource;
import io.okdocs.compliance.contracts.enums.BalanceTxnType;
import io.okdocs.compliance.contracts.exception.InsufficientScanBalanceException;
import io.okdocs.compliance.persistence.billing.ScanBalance;
import io.okdocs.compliance.persistence.billing.ScanBalanceRepository;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransaction;
import io.okdocs.compliance.persistence.billing.ScanBalanceTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanBalanceServiceTest {

    @Mock
    private ScanBalanceRepository balanceRepository;
    @Mock
    private ScanBalanceTransactionRepository txnRepository;

    private ScanBalanceService service;

    private ScanBalance balance;

    @BeforeEach
    void setUp() {
        // self = сам сервис: в проде это @Lazy-прокси для @Transactional doRefund,
        // в юнит-тесте достаточно прямой ссылки на инстанс.
        service = new ScanBalanceService(balanceRepository, txnRepository, null);
        service = new ScanBalanceService(balanceRepository, txnRepository, service);
        balance = new ScanBalance();
        balance.setUserId(1L);
        balance.setMonthlyQuota(5);
        balance.setUsedThisPeriod(0);
        balance.setPurchasedRemaining(0);
        balance.setPeriodResetAt(Instant.now().plusSeconds(3600));
    }

    @Test
    void debitConsumesMonthlyQuotaAndWritesLedger() {
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));
        UUID scanId = UUID.randomUUID();

        service.debit(1L, scanId);

        assertThat(balance.getUsedThisPeriod()).isEqualTo(1);
        assertThat(balance.available()).isEqualTo(4);

        ArgumentCaptor<ScanBalanceTransaction> txn = ArgumentCaptor.forClass(ScanBalanceTransaction.class);
        verify(balanceRepository).findWithLockByUserId(1L);
        verify(txnRepository).save(txn.capture());
        assertThat(txn.getValue().getType()).isEqualTo(BalanceTxnType.DEBIT);
        assertThat(txn.getValue().getAmount()).isEqualTo(-1);
        assertThat(txn.getValue().getBalanceAfter()).isEqualTo(4);
        assertThat(txn.getValue().getSource()).isEqualTo(BalanceTxnSource.MONTHLY);
    }

    @Test
    void debitFallsBackToPurchasedWhenMonthlyExhausted() {
        // Месячная квота исчерпана, остаются только докупленные — списываем из PURCHASED.
        balance.setMonthlyQuota(2);
        balance.setUsedThisPeriod(2);
        balance.setPurchasedRemaining(3);
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));

        service.debit(1L, UUID.randomUUID());

        assertThat(balance.getUsedThisPeriod()).isEqualTo(2); // monthly не тронут
        assertThat(balance.getPurchasedRemaining()).isEqualTo(2);
        assertThat(balance.available()).isEqualTo(2);

        ArgumentCaptor<ScanBalanceTransaction> txn = ArgumentCaptor.forClass(ScanBalanceTransaction.class);
        verify(txnRepository).save(txn.capture());
        assertThat(txn.getValue().getType()).isEqualTo(BalanceTxnType.DEBIT);
        assertThat(txn.getValue().getSource()).isEqualTo(BalanceTxnSource.PURCHASED);
    }

    @Test
    void purchaseAddsToPurchasedPocketAndWritesLedger() {
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));

        service.purchase(1L, 1);

        assertThat(balance.getPurchasedRemaining()).isEqualTo(1);
        assertThat(balance.getUsedThisPeriod()).isEqualTo(0); // monthly не трогаем
        assertThat(balance.available()).isEqualTo(6); // 5 monthly + 1 purchased

        ArgumentCaptor<ScanBalanceTransaction> txn = ArgumentCaptor.forClass(ScanBalanceTransaction.class);
        verify(txnRepository).save(txn.capture());
        assertThat(txn.getValue().getType()).isEqualTo(BalanceTxnType.PURCHASE);
        assertThat(txn.getValue().getAmount()).isEqualTo(1);
        assertThat(txn.getValue().getBalanceAfter()).isEqualTo(6);
        assertThat(txn.getValue().getSource()).isNull(); // покупка — не списание из кармана
    }

    @Test
    void purchaseRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service.purchase(1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.purchase(1L, -5))
                .isInstanceOf(IllegalArgumentException.class);
        verify(balanceRepository, never()).findWithLockByUserId(org.mockito.ArgumentMatchers.anyLong());
        verify(txnRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void debitThrowsWhenNoBalance() {
        balance.setMonthlyQuota(0);
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));

        assertThatThrownBy(() -> service.debit(1L, UUID.randomUUID()))
                .isInstanceOf(InsufficientScanBalanceException.class);
        verify(txnRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refundSkippedWhenNoDebit_freeMarketingOrGuest() {
        // FREE_MARKETING-скан (в т.ч. от залогиненного юзера) баланс не списывал → возврата нет.
        UUID scanId = UUID.randomUUID();
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.DEBIT)).thenReturn(false);

        service.refund(1L, scanId);

        verify(balanceRepository, never()).findWithLockByUserId(org.mockito.ArgumentMatchers.anyLong());
        verify(txnRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refundIsIdempotentByScanId() {
        UUID scanId = UUID.randomUUID();
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.DEBIT)).thenReturn(true);
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.REFUND)).thenReturn(true);

        service.refund(1L, scanId);

        verify(balanceRepository, never()).findWithLockByUserId(org.mockito.ArgumentMatchers.anyLong());
        verify(txnRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refundRestoresMonthlyWhenDebitSourceWasMonthly() {
        balance.setUsedThisPeriod(2);
        UUID scanId = UUID.randomUUID();
        stubRefundable(scanId, BalanceTxnSource.MONTHLY);
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));

        service.refund(1L, scanId);

        assertThat(balance.getUsedThisPeriod()).isEqualTo(1); // вернули в monthly
        assertThat(balance.getPurchasedRemaining()).isEqualTo(0);
        ArgumentCaptor<ScanBalanceTransaction> txn = ArgumentCaptor.forClass(ScanBalanceTransaction.class);
        verify(txnRepository).saveAndFlush(txn.capture());
        assertThat(txn.getValue().getType()).isEqualTo(BalanceTxnType.REFUND);
        assertThat(txn.getValue().getAmount()).isEqualTo(1);
        assertThat(txn.getValue().getSource()).isEqualTo(BalanceTxnSource.MONTHLY);
    }

    @Test
    void refundRestoresPurchasedWhenDebitSourceWasPurchased() {
        // Списали из докупленного — возврат должен идти в purchased, а НЕ в monthly,
        // даже если usedThisPeriod>0 (прежняя эвристика вернула бы ошибочно в monthly).
        balance.setUsedThisPeriod(3);
        balance.setPurchasedRemaining(0);
        UUID scanId = UUID.randomUUID();
        stubRefundable(scanId, BalanceTxnSource.PURCHASED);
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));

        service.refund(1L, scanId);

        assertThat(balance.getUsedThisPeriod()).isEqualTo(3); // monthly не тронут
        assertThat(balance.getPurchasedRemaining()).isEqualTo(1); // вернули в purchased
        ArgumentCaptor<ScanBalanceTransaction> txn = ArgumentCaptor.forClass(ScanBalanceTransaction.class);
        verify(txnRepository).saveAndFlush(txn.capture());
        assertThat(txn.getValue().getSource()).isEqualTo(BalanceTxnSource.PURCHASED);
    }

    @Test
    void mixedBalanceDebitThenRefundReturnsToCorrectPockets() {
        // Сценарий смешанного баланса: monthly=2, purchased=2. Два списания:
        // 1-е из monthly, 2-е (после исчерпания monthly) из purchased. Возврат каждого —
        // в свой карман по source.
        balance.setMonthlyQuota(2);
        balance.setUsedThisPeriod(1); // в monthly остался 1
        balance.setPurchasedRemaining(2);
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));

        UUID monthlyScan = UUID.randomUUID();
        service.debit(1L, monthlyScan); // последний monthly → used=2
        assertThat(balance.getUsedThisPeriod()).isEqualTo(2);
        assertThat(balance.getPurchasedRemaining()).isEqualTo(2);

        UUID purchasedScan = UUID.randomUUID();
        service.debit(1L, purchasedScan); // monthly исчерпан → purchased=1
        assertThat(balance.getUsedThisPeriod()).isEqualTo(2);
        assertThat(balance.getPurchasedRemaining()).isEqualTo(1);

        // Возврат purchased-скана → обратно в purchased.
        stubRefundable(purchasedScan, BalanceTxnSource.PURCHASED);
        service.refund(1L, purchasedScan);
        assertThat(balance.getUsedThisPeriod()).isEqualTo(2);
        assertThat(balance.getPurchasedRemaining()).isEqualTo(2);

        // Возврат monthly-скана → обратно в monthly.
        stubRefundable(monthlyScan, BalanceTxnSource.MONTHLY);
        service.refund(1L, monthlyScan);
        assertThat(balance.getUsedThisPeriod()).isEqualTo(1);
        assertThat(balance.getPurchasedRemaining()).isEqualTo(2);
    }

    @Test
    void refundSwallowsUniqueViolationFromConcurrentRefund() {
        UUID scanId = UUID.randomUUID();
        stubRefundable(scanId, BalanceTxnSource.MONTHLY);
        when(balanceRepository.findWithLockByUserId(1L)).thenReturn(Optional.of(balance));
        when(txnRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        // Не должно бросить наружу — гонка проиграна, идемпотентность сохранена.
        service.refund(1L, scanId);
    }

    /** Стабит проверки идемпотентности (есть DEBIT, нет REFUND) и source исходного DEBIT. */
    private void stubRefundable(UUID scanId, BalanceTxnSource debitSource) {
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.DEBIT)).thenReturn(true);
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.REFUND)).thenReturn(false);
        ScanBalanceTransaction debit = new ScanBalanceTransaction();
        debit.setType(BalanceTxnType.DEBIT);
        debit.setSource(debitSource);
        when(txnRepository.findFirstByScanIdAndType(scanId, BalanceTxnType.DEBIT))
                .thenReturn(Optional.of(debit));
    }
}
