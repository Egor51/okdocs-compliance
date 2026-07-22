package io.okdocs.compliance.contracts.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpUrlNormalizerTest {

    @Test
    void normalizesUnicodeDomainPathAndQuery() {
        var result = HttpUrlNormalizer.normalize(
                "домен.рф/путь?q=тест#раздел", true);

        assertEquals("xn--d1acufc.xn--p1ai", result.host());
        assertEquals(
                "https://xn--d1acufc.xn--p1ai/%D0%BF%D1%83%D1%82%D1%8C?q=%D1%82%D0%B5%D1%81%D1%82",
                result.url());
        assertNull(result.uri().getFragment());
    }

    @Test
    void preservesPortForIdnDomain() {
        var result = HttpUrlNormalizer.normalize("http://домен.рф:8080", false);

        assertEquals("http://xn--d1acufc.xn--p1ai:8080", result.url());
        assertEquals(8080, result.uri().getPort());
    }

    @Test
    void addsDefaultSchemeToDomainWithPort() {
        var result = HttpUrlNormalizer.normalize("домен.рф:8443/path", true);

        assertEquals("https://xn--d1acufc.xn--p1ai:8443/path", result.url());
    }

    @Test
    void rejectsUserInfo() {
        assertThrows(IllegalArgumentException.class, () -> HttpUrlNormalizer.normalize(
                "https://user:password@домен.рф", false));
    }

    @Test
    void rejectsUnsupportedSchemeWithoutRewritingIt() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HttpUrlNormalizer.normalize("ftp://домен.рф", true));
        assertEquals("Поддерживаются только http и https", error.getMessage());
    }
}
