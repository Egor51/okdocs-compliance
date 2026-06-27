package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.OAuthLoginCode;
import io.okdocs.compliance.persistence.auth.OAuthLoginCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginCodeServiceTest {

    @Mock
    private OAuthLoginCodeRepository codeRepository;

    private OAuthLoginCodeService service;

    @BeforeEach
    void setUp() {
        service = new OAuthLoginCodeService(codeRepository);
    }

    @Test
    void issueStoresHashedCodeAndReturnsPlain() {
        String plain = service.issue(7L);

        assertThat(plain).isNotBlank();
        ArgumentCaptor<OAuthLoginCode> saved = ArgumentCaptor.forClass(OAuthLoginCode.class);
        verify(codeRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        // В БД лежит hash, не plain.
        assertThat(saved.getValue().getCodeHash()).isNotEqualTo(plain);
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void redeemClaimsAtomicallyAndReturnsUserId() {
        String plain = service.issue(7L);
        String hash = capturedHash();
        // Победитель гонки: claim затронул 1 строку, читаем её за userId.
        when(codeRepository.claim(eq(hash), any())).thenReturn(1);
        when(codeRepository.findByCodeHash(hash))
                .thenReturn(Optional.of(code(7L, Instant.now().plusSeconds(120), Instant.now())));

        Long userId = service.redeem(plain);

        assertThat(userId).isEqualTo(7L);
        verify(codeRepository).claim(eq(hash), any());
    }

    @Test
    void redeemRejectsUnknownCode() {
        // claim=0 и строки нет → недействителен.
        when(codeRepository.claim(any(), any())).thenReturn(0);
        when(codeRepository.findByCodeHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem("nope"))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("недействителен");
    }

    @Test
    void redeemRejectsAlreadyConsumedCode() {
        // claim=0 (проиграл гонку / уже использован), строка несёт consumedAt.
        String plain = service.issue(7L);
        String hash = capturedHash();
        when(codeRepository.claim(eq(hash), any())).thenReturn(0);
        when(codeRepository.findByCodeHash(hash))
                .thenReturn(Optional.of(code(7L, Instant.now().plusSeconds(120), Instant.now())));

        assertThatThrownBy(() -> service.redeem(plain))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("использован");
    }

    @Test
    void redeemRejectsExpiredCode() {
        // claim=0, строка не consumed → просрочена.
        String plain = service.issue(7L);
        String hash = capturedHash();
        when(codeRepository.claim(eq(hash), any())).thenReturn(0);
        when(codeRepository.findByCodeHash(hash))
                .thenReturn(Optional.of(code(7L, Instant.now().minusSeconds(1), null)));

        assertThatThrownBy(() -> service.redeem(plain))
                .isInstanceOf(ComplianceValidationException.class)
                .hasMessageContaining("просрочен");
    }

    @Test
    void redeemRejectsBlankInput() {
        assertThatThrownBy(() -> service.redeem("  "))
                .isInstanceOf(ComplianceValidationException.class);
        verify(codeRepository, never()).claim(any(), any());
    }

    private String capturedHash() {
        ArgumentCaptor<OAuthLoginCode> saved = ArgumentCaptor.forClass(OAuthLoginCode.class);
        verify(codeRepository).save(saved.capture());
        return saved.getValue().getCodeHash();
    }

    private OAuthLoginCode code(Long userId, Instant expiresAt, Instant consumedAt) {
        OAuthLoginCode c = new OAuthLoginCode();
        c.setUserId(userId);
        c.setExpiresAt(expiresAt);
        c.setConsumedAt(consumedAt);
        return c;
    }
}
