package io.okdocs.compliance.api.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.exception.ComplianceRateLimitException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory rate limiting (Bucket4j) для локальной разработки без Redis ({@code @Profile("local")}).
 * Не распределён — для горизонтального масштабирования используется {@link RedisRateLimitService}.
 */
@Service
@Profile("local")
@RequiredArgsConstructor
public class InMemoryRateLimitService implements RateLimitService {

    private final ComplianceApiProperties properties;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void checkScanAllowed(CompliancePrincipal principal, String ipAddress) {
        if (principal.isUser()) {
            consumeOrThrow("user:" + principal.userId(), properties.rateLimit().userScansPerHour());
            return;
        }
        consumeOrThrow("ip:" + ipAddress, properties.rateLimit().guestScansPerIpPerHour());
    }

    @Override
    public void checkAuthAttemptAllowed(String ipAddress) {
        if (!bucket("auth:" + ipAddress, properties.rateLimit().authAttemptsPerIpPerHour())
                .tryConsume(1)) {
            throw new ComplianceRateLimitException("Слишком много попыток входа, попробуйте позже");
        }
    }

    private void consumeOrThrow(String key, int limitPerHour) {
        if (!bucket(key, limitPerHour).tryConsume(1)) {
            throw new ComplianceRateLimitException("Превышен лимит сканов, попробуйте позже");
        }
    }

    private Bucket bucket(String key, int limitPerHour) {
        return buckets.computeIfAbsent(key, k -> newBucket(limitPerHour));
    }

    private Bucket newBucket(int limitPerHour) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(limitPerHour)
                .refillGreedy(limitPerHour, Duration.ofHours(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
