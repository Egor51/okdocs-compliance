package io.okdocs.compliance.api.job;

import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.service.ScanBalanceService;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyQuotaSchedulerTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private ScanBalanceService balanceService;
    @Mock
    private ComplianceApiProperties properties;

    private MonthlyQuotaScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MonthlyQuotaScheduler(userRepository, balanceService, properties);
        lenient().when(properties.plan()).thenReturn(new ComplianceApiProperties.Plan(null)); // FREE=0
    }

    @Test
    void expiredPaidPlansGetFreeQuota() {
        // claim вернул двух истёкших платных юзеров (уже переведённых на FREE в UPDATE...RETURNING).
        when(userRepository.claimExpiredPaidPlans(any())).thenReturn(List.of(7L, 8L));

        scheduler.expirePaidPlans();

        verify(balanceService).grantMonthly(eq(7L), eq(0));
        verify(balanceService).grantMonthly(eq(8L), eq(0));
    }

    @Test
    void noExpiredPlansIsNoOp() {
        when(userRepository.claimExpiredPaidPlans(any())).thenReturn(List.of());

        scheduler.expirePaidPlans();

        verify(balanceService, never()).grantMonthly(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
