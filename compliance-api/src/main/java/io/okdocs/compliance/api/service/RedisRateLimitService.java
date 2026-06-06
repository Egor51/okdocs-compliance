package io.okdocs.compliance.api.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
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

    public RedisRateLimitService(ComplianceApiProperties properties,
                                 @Value("${spring.data.redis.host:localhost}") String redisHost,
                                 @Value("${spring.data.redis.port:6379}") int redisPort) {
        this.properties = properties;
        this.redisClient = RedisClient.create("redis://" + redisHost + ":" + redisPort);
        this.proxyManager = LettuceBasedProxyManager
                .builderFor(redisClient)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(1)))
                .build();
    }

    @Override
    public void checkScanAllowed(CompliancePrincipal principal, String ipAddress) {
        // Списываем последовательно и ПРОВЕРЯЕМ результат каждого tryConsume (probe-then-consume
        // под конкуренцией пропускал лишнее). Два отдельных бакета нельзя атомарно зарезервировать,
        // поэтому осознанный trade-off: при отказе на ip-бакете уже списанный user-слот не
        // возвращается — лимит лишь чуть строже к абьюзеру, лишнего не пропускает.
        if (principal.isUser()) {
            consumeOrThrow("user:" + principal.userId(), properties.rateLimit().userScansPerHour());
        }
        consumeOrThrow("ip:" + ipAddress, properties.rateLimit().guestScansPerIpPerHour());
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
