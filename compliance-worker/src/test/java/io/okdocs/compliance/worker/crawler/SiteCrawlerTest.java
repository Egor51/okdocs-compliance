package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteCrawlerTest {

    @Test
    void storesFinalUrlAfterRedirect() throws Exception {
        ComplianceWorkerProperties props = new ComplianceWorkerProperties();
        props.getCrawler().setRespectRobots(false);
        UrlValidator validator = mock(UrlValidator.class);
        PinnedHttpFetcher fetcher = mock(PinnedHttpFetcher.class);
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");

        when(validator.isHostSafe(anyString())).thenReturn(true);
        when(validator.resolvePublicHost(anyString()))
                .thenReturn(UrlValidator.ResolvedHost.ok("my-traffic.online", List.of(publicAddress)));
        when(fetcher.fetch(any(URI.class), any(InetAddress.class), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        SiteCrawler crawler = new SiteCrawler(props, validator, fetcher);

        SiteCrawler.CrawlResult result = crawler.crawl("http://my-traffic.online", 1);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).url()).isEqualTo("https://my-traffic.online/");
        assertThat(result.diagnostics().pagesFetched()).isEqualTo(1);
    }

    private static PinnedHttpFetcher.Response responseFor(URI uri) {
        String url = uri.toString();
        if ("http://my-traffic.online/".equals(url)) {
            return new PinnedHttpFetcher.Response(
                    301, Map.of("location", List.of("https://my-traffic.online/")), "");
        }
        if ("https://my-traffic.online/".equals(url)) {
            return new PinnedHttpFetcher.Response(200, Map.of(), """
                    <!doctype html>
                    <html>
                      <head><title>mytraffic</title></head>
                      <body>
                        <main>
                          <h1>mytraffic</h1>
                          <p>Создание сайтов, Telegram-боты, CRM и AI-интеграции для бизнеса.</p>
                          <p>Мы проектируем интерфейсы, настраиваем аналитику, подключаем формы заявок,
                          автоматизируем продажи и поддержку клиентов. На этой странице достаточно
                          содержимого, чтобы static crawler не считал её пустой soft-404 страницей.</p>
                          <p>Контакты, услуги, портфолио, описание подхода, разработка, дизайн,
                          интеграции, сопровождение, поддержка, внедрение, консультации.</p>
                        </main>
                      </body>
                    </html>
                    """);
        }
        return new PinnedHttpFetcher.Response(404, Map.of(), "");
    }
}
