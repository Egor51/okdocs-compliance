package io.okdocs.compliance.api.service;

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
        when(balanceRepository.findByUserId(1L)).thenReturn(Optional.of(balance));
        UUID scanId = UUID.randomUUID();

        service.debit(1L, scanId);

        assertThat(balance.getUsedThisPeriod()).isEqualTo(1);
        assertThat(balance.available()).isEqualTo(4);

        ArgumentCaptor<ScanBalanceTransaction> txn = ArgumentCaptor.forClass(ScanBalanceTransaction.class);
        verify(txnRepository).save(txn.capture());
        assertThat(txn.getValue().getType()).isEqualTo(BalanceTxnType.DEBIT);
        assertThat(txn.getValue().getAmount()).isEqualTo(-1);
        assertThat(txn.getValue().getBalanceAfter()).isEqualTo(4);
    }

    @Test
    void debitThrowsWhenNoBalance() {
        balance.setMonthlyQuota(0);
        when(balanceRepository.findByUserId(1L)).thenReturn(Optional.of(balance));

        assertThatThrownBy(() -> service.debit(1L, UUID.randomUUID()))
                .isInstanceOf(InsufficientScanBalanceException.class);
        verify(txnRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refundIsIdempotentByScanId() {
        UUID scanId = UUID.randomUUID();
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.REFUND)).thenReturn(true);

        service.refund(1L, scanId);

        verify(balanceRepository, never()).findByUserId(org.mockito.ArgumentMatchers.anyLong());
        verify(txnRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refundRestoresMonthlyQuota() {
        balance.setUsedThisPeriod(2);
        UUID scanId = UUID.randomUUID();
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.REFUND)).thenReturn(false);
        when(balanceRepository.findByUserId(1L)).thenReturn(Optional.of(balance));

        service.refund(1L, scanId);

        assertThat(balance.getUsedThisPeriod()).isEqualTo(1);
        ArgumentCaptor<ScanBalanceTransaction> txn = ArgumentCaptor.forClass(ScanBalanceTransaction.class);
        verify(txnRepository).saveAndFlush(txn.capture());
        assertThat(txn.getValue().getType()).isEqualTo(BalanceTxnType.REFUND);
        assertThat(txn.getValue().getAmount()).isEqualTo(1);
    }

    @Test
    void refundSwallowsUniqueViolationFromConcurrentRefund() {
        UUID scanId = UUID.randomUUID();
        when(txnRepository.existsByScanIdAndType(scanId, BalanceTxnType.REFUND)).thenReturn(false);
        when(balanceRepository.findByUserId(1L)).thenReturn(Optional.of(balance));
        when(txnRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        // Не должно бросить наружу — гонка проиграна, идемпотентность сохранена.
        service.refund(1L, scanId);
    }
}
