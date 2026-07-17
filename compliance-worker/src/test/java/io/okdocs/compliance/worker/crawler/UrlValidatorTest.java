package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSRF reserved ranges — критичная защита worker'а (отдельный trust boundary). {@code isPrivateOrSpecial}
 * package-private и принимает {@link InetAddress}, поэтому проверяем reserved-диапазоны напрямую по
 * литеральным IP, без DNS-резолва.
 */
class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(new ComplianceWorkerProperties());

    // ── Блокируемые диапазоны (расширенная защита из okdocks) ──────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",        // loopback
            "10.0.0.1",         // 10/8 private
            "172.16.5.4",       // 172.16/12 private
            "192.168.1.1",      // 192.168/16 private
            "169.254.169.254",  // link-local / cloud metadata
            "0.0.0.0",          // any-local
            "100.64.0.1",       // CGNAT 100.64/10
            "100.127.255.255",  // CGNAT верхняя граница
            "192.0.0.1",        // 192.0.0/24 reserved
            "192.0.2.5",        // 192.0.2/24 TEST-NET-1 (b1==0)
            "198.18.0.1",       // 198.18/15 benchmarking
            "198.19.1.1",       // 198.18/15
            "198.51.100.1",     // 198.51.100/24 TEST-NET-2
            "203.0.113.1",      // 203.0.113/24 TEST-NET-3
            "240.0.0.1",        // 240/4 reserved
            "255.255.255.255"   // 240/4
    })
    void blocksPrivateAndReservedIpv4(String ip) throws UnknownHostException {
        assertThat(validator.isPrivateOrSpecial(InetAddress.getByName(ip)))
                .as("IPv4 %s must be blocked", ip)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::1",                  // loopback
            "fc00::1",              // ULA fc00::/7
            "fd12:3456::1",         // ULA fd::
            "2002:c0a8:0101::1",    // 6to4 2002::/16 (инкапсулирует 192.168.1.1)
            "::ffff:127.0.0.1",     // IPv4-mapped loopback
            "::ffff:10.0.0.1",      // IPv4-mapped 10/8
            "::ffff:192.168.0.1",   // IPv4-mapped 192.168/16
            "::ffff:169.254.0.1",   // IPv4-mapped link-local
            "::ffff:100.64.0.1"     // IPv4-mapped CGNAT
    })
    void blocksPrivateAndReservedIpv6(String ip) throws UnknownHostException {
        assertThat(validator.isPrivateOrSpecial(InetAddress.getByName(ip)))
                .as("IPv6 %s must be blocked", ip)
                .isTrue();
    }

    // ── Публичные адреса проходят ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",          // Google DNS
            "1.1.1.1",          // Cloudflare
            "93.184.216.34",    // публичный
            "100.63.255.255",   // прямо ниже CGNAT 100.64/10 — НЕ блокируется
            "100.128.0.1",      // прямо выше CGNAT — НЕ блокируется
            "198.20.1.1",       // вне 198.18/15
            "204.0.113.1"       // вне 203.0.0.0/16 over-block (см. blocksOverBroad203And192)
    })
    void allowsPublicIpv4(String ip) throws UnknownHostException {
        assertThat(validator.isPrivateOrSpecial(InetAddress.getByName(ip)))
                .as("public IPv4 %s must be allowed", ip)
                .isFalse();
    }

    @Test
    void blocksOverBroad203And192() throws UnknownHostException {
        // Перенос okdocks: проверка (b0==203 && b1==0) и (b0==192 && b1==0) режет ВЕСЬ /16,
        // хотя зарезервированы только 203.0.113.0/24 и 192.0.0.0/24+192.0.2.0/24. Это fail-safe
        // over-block (чуть больше публичного пространства IANA), не дыра. Фиксируем как осознанное
        // поведение; если потребуется сузить до /24 — менять production-логику отдельно.
        assertThat(validator.isPrivateOrSpecial(InetAddress.getByName("203.0.200.1"))).isTrue();
        assertThat(validator.isPrivateOrSpecial(InetAddress.getByName("192.0.100.1"))).isTrue();
    }

    @Test
    void allowsPublicIpv6() throws UnknownHostException {
        // 2606:4700:4700::1111 — Cloudflare public IPv6
        assertThat(validator.isPrivateOrSpecial(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    // ── validate(): схема и формат ─────────────────────────────────────────────────────────────

    @Test
    void rejectsNonHttpScheme() {
        assertThat(validator.validate("ftp://example.com").valid()).isFalse();
    }

    @Test
    void rejectsBlankUrl() {
        assertThat(validator.validate("   ").valid()).isFalse();
    }

    @Test
    void rejectsMissingHost() {
        assertThat(validator.validate("http://").valid()).isFalse();
    }

    @Test
    void rejectsLoopbackByName() {
        // localhost резолвится в 127.0.0.1 — должен отлететь на проверке адреса
        assertThat(validator.validate("http://localhost").valid()).isFalse();
    }

    // ── isHostSafe для redirect-хопов ──────────────────────────────────────────────────────────

    @Test
    void hostSafeRejectsBlankAndNull() {
        assertThat(validator.isHostSafe(null)).isFalse();
        assertThat(validator.isHostSafe("  ")).isFalse();
    }

    @Test
    void blockedDomainIsRejectedByValidate() {
        ComplianceWorkerProperties props = new ComplianceWorkerProperties();
        props.getSecurity().setBlockedDomains(java.util.List.of("example.com"));
        UrlValidator v = new UrlValidator(props);
        // blocked-домен отлетает до DNS-резолва
        assertThat(v.validate("https://example.com").valid()).isFalse();
        assertThat(v.validate("https://sub.example.com").valid()).isFalse();
    }

    // ── Allowlist (compliance.crawler.allowed-domains) ─────────────────────────────────────────

    // Детерминированные (без сети): allowlist отсекает ДО DNS-резолва.
    @Test
    void nonEmptyAllowlist_rejectsDomainNotInList() {
        ComplianceWorkerProperties props = new ComplianceWorkerProperties();
        props.getCrawler().setAllowedDomains(java.util.List.of("okdocs.io"));
        UrlValidator v = new UrlValidator(props);
        var result = v.validate("https://example.com");
        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).contains("разрешённых");
    }

    @Test
    void allowlist_appliesToRedirectHopsToo() {
        ComplianceWorkerProperties props = new ComplianceWorkerProperties();
        props.getCrawler().setAllowedDomains(java.util.List.of("okdocs.io"));
        UrlValidator v = new UrlValidator(props);
        // redirect-хоп на не-разрешённый хост → небезопасен (isHostSafe=false), без DNS.
        assertThat(v.isHostSafe("example.com")).isFalse();
    }
}
