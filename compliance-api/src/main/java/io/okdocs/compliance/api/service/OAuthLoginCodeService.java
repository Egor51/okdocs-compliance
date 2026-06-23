package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.persistence.auth.OAuthLoginCode;
import io.okdocs.compliance.persistence.auth.OAuthLoginCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One-time коды автологина (F.8): issue после OAuth-callback'а, redeem при обмене на JWT.
 * Код одноразовый и короткоживущий; в БД лежит только его SHA-256 hash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLoginCodeService {

    /** Короткий TTL: код живёт лишь на перелёт callback → фронт → exchange. */
    private static final Duration TTL = Duration.ofMinutes(5);

    private final OAuthLoginCodeRepository codeRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Сгенерировать одноразовый код для юзера, вернуть plain (его кладём в redirect фронту). */
    @Transactional
    public String issue(Long userId) {
        String plain = newCodeValue();
        OAuthLoginCode code = new OAuthLoginCode();
        code.setCodeHash(hash(plain));
        code.setUserId(userId);
        code.setExpiresAt(Instant.now().plus(TTL));
        codeRepository.save(code);
        return plain;
    }

    /**
     * Обменять код на userId, пометив его использованным. Невалидный/просроченный/уже использованный
     * код → ошибка (фронт показывает «войдите для активации»). Single-use: повторный обмен отвергается.
     */
    @Transactional
    public Long redeem(String plainCode) {
        if (plainCode == null || plainCode.isBlank()) {
            throw new ComplianceValidationException("Код автологина не указан");
        }
        String codeHash = hash(plainCode);
        // Атомарный claim: помечаем consumed одним conditional UPDATE. Победитель гонки получает
        // affected=1; параллельные exchange'ы получают 0 и не выдадут второй токен по тому же коду.
        int claimed = codeRepository.claim(codeHash, Instant.now());
        if (claimed == 1) {
            // UPDATE прошёл — читаем свежую строку за userId (JPQL update минует контекст).
            return codeRepository.findByCodeHash(codeHash)
                    .map(OAuthLoginCode::getUserId)
                    .orElseThrow(() -> new ComplianceValidationException("Код автологина недействителен"));
        }
        // affected=0 — диагностируем причину для осмысленного ответа (не найден / просрочен / занят).
        OAuthLoginCode code = codeRepository.findByCodeHash(codeHash)
                .orElseThrow(() -> new ComplianceValidationException("Код автологина недействителен"));
        if (code.getConsumedAt() != null) {
            throw new ComplianceValidationException("Код автологина уже использован");
        }
        throw new ComplianceValidationException("Код автологина просрочен");
    }

    private String newCodeValue() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
