package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.TlsInfo;
import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.naming.ldap.LdapName;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Осматривает TLS хоста на :443 ОТДЕЛЬНЫМ соединением, а не из успешного HTML-fetch. Причина (§ TLS,
 * Этап 2): при битом сертификате обычный {@link PinnedHttpFetcher#fetch} бросает исключение, страница
 * уходит в {@code pagesFailed} и до правил не доходит — а для paid-аудита плохой TLS должен стать
 * находкой. Поэтому неуспех handshake — это валидный наполненный {@link TlsInfo}
 * ({@code handshakeOk=false} + {@code handshakeError}), а не отсутствие данных.
 * <p>
 * Резолв хоста идёт через {@link UrlValidator#resolvePublicHost} — тот же SSRF-safe путь, что у
 * краулера. Если Этап 3 уже дал {@code DnsInfo.resolvedIps()}, инспектор использует только публичные
 * адреса из этого списка. Trust-chain проверяется JVM во время handshake, а hostname/SAN проверяется
 * вручную после получения сертификата, чтобы hostname mismatch был отдельной находкой, а не терялся
 * как общий handshake failure. Метод никогда не бросает: любая ошибка → {@code TlsInfo} с описанием.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TlsInspector {

    private final ComplianceWorkerProperties properties;
    private final UrlValidator urlValidator;

    /** Осмотр TLS одного хоста с собственным SSRF-safe резолвом. Всегда возвращает {@link TlsInfo}. */
    public TlsInfo inspect(String host) {
        return inspect(host, List.of());
    }

    /**
     * Осмотр TLS по уже разрешённым DNS-адресам (§ Этап 3): cert-finding относится к тому же IP, что
     * и DNS-анализ. {@code resolvedIps} пуст → fallback на собственный SSRF-safe резолв (одиночный
     * вызов/тесты). Всегда возвращает {@link TlsInfo} (в т.ч. при сбое — не бросает, не валит скан).
     */
    public TlsInfo inspect(String host, List<String> resolvedIps) {
        if (host == null || host.isBlank()) {
            return failed(host, "empty host");
        }
        InetAddress address = firstAddress(host, resolvedIps);
        if (address == null) {
            return failed(host, "host not resolvable");
        }
        int connectTimeout = properties.getCrawler().getConnectTimeoutMs();
        int readTimeout = properties.getCrawler().getPageTimeoutMs();
        try (SSLSocket socket = openTlsSocket(host, address, connectTimeout, readTimeout)) {
            socket.startHandshake();
            String negotiated = socket.getSession().getProtocol();
            List<String> supported = probeSupportedProtocols(host, address, connectTimeout, readTimeout, negotiated);
            return fromSession(host, socket.getSession(), supported);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.debug("TLS inspect failed host={}: {}", host, msg);
            return failed(host, msg);
        }
    }

    /** Версии протокола, по которым инспектор пробует отдельные probe-сокеты (легаси-детект). */
    private static final List<String> LEGACY_PROBE_PROTOCOLS = List.of("TLSv1.1", "TLSv1");

    /**
     * Какие версии TLS сервер реально принимает. Основной handshake договаривается на максимум
     * ({@code negotiated}), поэтому legacy (TLS 1.0/1.1) из него не видно — для каждой делаем
     * отдельный короткий probe-сокет, форсируя только эту версию. Успех handshake → версия принимается.
     * <p>
     * Возвращает согласованный протокол + все успешно подтверждённые legacy. Никогда не бросает: сбой
     * отдельного probe (сервер версию не принимает / закрыл соединение) — это и есть ожидаемый «нет».
     */
    private List<String> probeSupportedProtocols(String host, InetAddress address,
                                                 int connectTimeout, int readTimeout, String negotiated) {
        List<String> supported = new ArrayList<>();
        if (negotiated != null && !negotiated.isBlank()) {
            supported.add(negotiated);
        }
        for (String protocol : LEGACY_PROBE_PROTOCOLS) {
            if (protocol.equalsIgnoreCase(negotiated)) {
                continue; // уже подтверждён основным handshake — отдельный probe не нужен
            }
            if (acceptsProtocol(host, address, connectTimeout, readTimeout, protocol)) {
                supported.add(protocol);
            }
        }
        return supported;
    }

    /** Принимает ли сервер ровно эту версию протокола (форсируем её одну на probe-сокете). */
    private boolean acceptsProtocol(String host, InetAddress address,
                                    int connectTimeout, int readTimeout, String protocol) {
        try (SSLSocket socket = openTlsSocket(host, address, connectTimeout, readTimeout)) {
            socket.setEnabledProtocols(new String[]{protocol});
            socket.startHandshake();
            return true;
        } catch (IllegalArgumentException e) {
            // JVM не поддерживает эту legacy-версию (отключена в java.security) — проверить нельзя.
            log.debug("TLS probe {} unsupported by JVM for {}: {}", protocol, host, e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("TLS probe {} rejected by {}: {}", protocol, host, e.getMessage());
            return false;
        }
    }

    /**
     * Первый адрес для TLS-подключения. {@code resolvedIps} (из DnsInfo) непуст → используем его IP
     * напрямую (тот же адрес, что DNS-анализ; IP-литерал парсится без сетевого резолва). Пусто →
     * SSRF-safe резолв через {@link UrlValidator#resolvePublicHost}. null, если ничего не разрешилось.
     */
    private InetAddress firstAddress(String host, List<String> resolvedIps) {
        if (resolvedIps != null && !resolvedIps.isEmpty()) {
            for (String ip : resolvedIps) {
                try {
                    InetAddress address = InetAddress.getByName(ip);
                    if (urlValidator.isPublicAddress(address)) {
                        return address;
                    }
                    log.debug("TLS: unsafe resolved ip {} for {}", ip, host);
                } catch (Exception e) {
                    log.debug("TLS: bad resolved ip {} for {}: {}", ip, host, e.getMessage());
                }
            }
            return null;
        }
        UrlValidator.ResolvedHost resolved = urlValidator.resolvePublicHost(host);
        if (resolved.valid() && !resolved.addresses().isEmpty()) {
            return resolved.addresses().get(0);
        }
        return null;
    }

    private SSLSocket openTlsSocket(String host, InetAddress address, int connectTimeout, int readTimeout)
            throws java.io.IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(address, 443), connectTimeout);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, 443, true);
        ssl.setSoTimeout(readTimeout);
        SSLParameters params = ssl.getSSLParameters();
        if (isDnsName(host)) {
            params.setServerNames(List.of(new SNIHostName(host)));
        }
        ssl.setSSLParameters(params);
        return ssl;
    }

    private static TlsInfo fromSession(String host, SSLSession session, List<String> supportedProtocols) {
        String protocol = session.getProtocol();
        String cipher = session.getCipherSuite();
        Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (Exception e) {
            return new TlsInfo(host, true, null, true, false, false,
                    protocol, cipher, null, null, List.of(), null, null, supportedProtocols);
        }
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            return new TlsInfo(host, true, null, true, false, false,
                    protocol, cipher, null, null, List.of(), null, null, supportedProtocols);
        }
        String subject = leaf.getSubjectX500Principal() == null ? null : leaf.getSubjectX500Principal().getName();
        String issuer = leaf.getIssuerX500Principal() == null ? null : leaf.getIssuerX500Principal().getName();
        List<String> san = subjectAltNames(leaf);
        String cn = commonName(subject);
        List<String> hostnameNames = san.isEmpty() && cn != null ? List.of(cn) : san;
        return new TlsInfo(
                host, true, null, true, hostMatchesAny(host, hostnameNames), false,
                protocol, cipher, subject, issuer, san,
                leaf.getNotBefore() == null ? null : leaf.getNotBefore().toInstant(),
                leaf.getNotAfter() == null ? null : leaf.getNotAfter().toInstant(),
                supportedProtocols);
    }

    /** DNS-имена из SAN (type 2). Падение разбора → пустой список (host-mismatch правило не сработает). */
    private static List<String> subjectAltNames(X509Certificate cert) {
        List<String> names = new ArrayList<>();
        try {
            var sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> entry : sans) {
                    if (entry.size() >= 2 && entry.get(0) instanceof Integer type && type == 2
                            && entry.get(1) instanceof String name) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SAN parse failed: {}", e.getMessage());
        }
        return names;
    }

    private static TlsInfo failed(String host, String error) {
        // Handshake не состоялся — зондирование версий не выполнялось: supportedProtocols = null.
        return new TlsInfo(host, false, error, false, false,
                !isCertificateLikeError(error), null, null, null, null, List.of(), null, null, null);
    }

    private static boolean isDnsName(String host) {
        return host != null && !host.contains(":") && !host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private static boolean hostMatchesAny(String host, List<String> names) {
        if (host == null || names == null || names.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name != null && hostMatches(h, name.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hostMatches(String host, String name) {
        if (name.isBlank()) {
            return false;
        }
        if (name.startsWith("*.")) {
            String suffix = name.substring(1);
            int firstDot = host.indexOf('.');
            return firstDot > 0 && host.substring(firstDot).equals(suffix);
        }
        return host.equals(name);
    }

    private static String commonName(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        try {
            for (var rdn : new LdapName(subject).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType()) && rdn.getValue() instanceof String cn && !cn.isBlank()) {
                    return cn.toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception e) {
            log.debug("CN parse failed: {}", e.getMessage());
        }
        return null;
    }

    private static boolean isCertificateLikeError(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        String e = error.toLowerCase(Locale.ROOT);
        return e.contains("certificate") || e.contains("cert")
                || e.contains("pkix") || e.contains("validator")
                || e.contains("trust") || e.contains("unable to find valid certification")
                || e.contains("expired") || e.contains("revoked")
                || e.contains("self-signed") || e.contains("self signed")
                || e.contains("handshake_failure") || e.contains("no subject alternative");
    }
}
