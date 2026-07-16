package io.okdocs.compliance.api.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.okdocs.compliance.api.config.ComplianceApiProperties;
import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.exception.ComplianceRateLimitException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Распределённый rate limiting на Redis (Bucket4j + Lettuce, {@code @Profile("!local")}).
 * Нужен для горизонтального масштабирования: лимит общий для всех инстансов api.
 */
@Service
@Profile("!local")
public class RedisRateLimitService implements RateLimitService {

    private final ComplianceApiProperties properties;
    private final RedisClient redisClient;
    private final ProxyManager<byte[]> proxyManager;

    public RedisRateLimitService(
            ComplianceApiProperties properties,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${spring.data.redis.username:}") String redisUsername,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean redisSsl) {
        this.properties = properties;
        // RedisURI, а не строка "redis://host:port": ручная сборка URI игнорировала бы auth/TLS.
        // Пароль/юзер/SSL берём из стандартных spring.data.redis.* (по умолчанию пусто = без auth,
        // для локали). В проде задаются env REDIS_USERNAME/REDIS_PASSWORD/REDIS_SSL_ENABLED.
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withSsl(redisSsl);
        if (!redisPassword.isBlank()) {
            if (redisUsername.isBlank()) {
                uri.withPassword(redisPassword.toCharArray());            // requirepass (default user)
            } else {
                uri.withAuthentication(redisUsername, redisPassword.toCharArray()); // Redis 6+ ACL user
            }
        }
        this.redisClient = RedisClient.create(uri.build());
        this.proxyManager = LettuceBasedProxyManager
                .builderFor(redisClient)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(1)))
                .build();
    }

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
        var bucket = proxyManager.builder()
                .build(("auth:" + ipAddress).getBytes(StandardCharsets.UTF_8),
                        config(properties.rateLimit().authAttemptsPerIpPerHour()));
        if (!bucket.tryConsume(1)) {
            throw new ComplianceRateLimitException("Слишком много попыток входа, попробуйте позже");
        }
    }

    @Override
    public void checkRemediationRequestAllowed(String ipAddress) {
        var bucket = proxyManager.builder()
                .build(("remediation:" + ipAddress).getBytes(StandardCharsets.UTF_8),
                        config(properties.rateLimit().remediationRequestsPerIpPerHour()));
        if (!bucket.tryConsume(1)) {
            throw new ComplianceRateLimitException(
                    "Слишком много заявок, попробуйте отправить форму позже");
        }
    }

    private void consumeOrThrow(String key, int limitPerHour) {
        var bucket = proxyManager.builder()
                .build(key.getBytes(StandardCharsets.UTF_8), config(limitPerHour));
        if (!bucket.tryConsume(1)) {
            throw new ComplianceRateLimitException("Превышен лимит сканов, попробуйте позже");
        }
    }

    private Supplier<BucketConfiguration> config(int limitPerHour) {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limitPerHour)
                        .refillGreedy(limitPerHour, Duration.ofHours(1))
                        .build())
                .build();
    }

    @PreDestroy
    void shutdown() {
        redisClient.shutdown();
    }
}
