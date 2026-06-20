package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.worker.config.ComplianceWorkerProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteCrawlerTest {

    private static final String DOMAIN = "example.com";
    private static final String BASE = "https://example.com";

    @Test
    void storesFinalUrlAfterRedirect() throws Exception {
        SiteCrawler crawler = crawler(props -> props.getCrawler().setRespectRobots(false),
                uri -> {
                    String url = uri.toString();
                    if ("http://my-traffic.online/".equals(url)) {
                        return new PinnedHttpFetcher.Response(
                                301, Map.of("location", List.of("https://my-traffic.online/")), "");
                    }
                    if ("https://my-traffic.online/".equals(url)) {
                        return htmlResponse("mytraffic", List.of());
                    }
                    return notFound();
                }, "my-traffic.online");

        SiteCrawler.CrawlResult result = crawler.crawl("http://my-traffic.online", 1);

        assertThat(result.pages()).hasSize(1);
        // Стартовая помечается по depth==0, не по исходному URL: после redirect финальный URL иной.
        assertThat(result.pages().get(0).url()).isEqualTo("https://my-traffic.online/");
        assertThat(result.diagnostics().pagesFetched()).isEqualTo(1);
    }

    @Test
    void maxPagesOneDoesNotFetchSeedHints() {
        // maxPages=1 (free/static-only): seed-хинты (/privacy, /contact, ...) НЕ должны фетчиться —
        // иначе лишние запросы и ложный PARTIAL из-за pagesFailed>0 на успешной главной.
        SiteCrawler crawler = crawler(noRobotsMax(1),
                uri -> "https://example.com/".equals(uri.toString())
                        ? htmlResponse("home", List.of())
                        : notFound(),
                DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl(BASE, 1);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.diagnostics().pagesAttempted()).isEqualTo(1);
        assertThat(result.diagnostics().pagesFailed()).isZero();
    }

    @Test
    void missingPriorityHintsDoNotCountAsFailedPages() {
        // Для premium/maxPages>1 priority hints остаются полезным fallback без sitemap, но 404 на
        // угаданный /privacy|/contact|... не означает, что реальная страница сайта сорвалась.
        SiteCrawler crawler = crawler(noRobotsMax(100),
                uri -> "https://example.com/".equals(uri.toString())
                        ? htmlResponse("home", List.of())
                        : notFound(),
                DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl(BASE, 100);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.diagnostics().pagesAttempted()).isEqualTo(32);
        assertThat(result.diagnostics().pagesFetched()).isEqualTo(1);
        assertThat(result.diagnostics().pagesFailed()).isZero();
        assertThat(result.diagnostics().priorityHintsAttempted()).isEqualTo(31);
        assertThat(result.diagnostics().priorityHintsMissed()).isEqualTo(31);
    }

    @Test
    void startPageFirstEvenAfterRedirectAndSlowerHomepage() {
        // Замечание 3: homepage редиректит и отвечает медленнее детей; всё равно должна быть первой.
        SiteCrawler crawler = crawler(noRobotsMax(100), uri -> {
            String url = uri.toString();
            if ("http://example.com/".equals(url)) {
                return new PinnedHttpFetcher.Response(
                        301, Map.of("location", List.of("https://example.com/")), "");
            }
            if ("https://example.com/".equals(url)) {
                sleepQuietly(120); // homepage медленнее детей → завершится позже
                return htmlResponse("home", childUrls(5));
            }
            List<String> children = childUrls(5);
            if (children.contains(url)) {
                return htmlResponse("child-" + url, List.of());
            }
            return notFound();
        }, DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl("http://example.com", 100);

        assertThat(result.pages()).hasSizeGreaterThan(1);
        assertThat(result.pages().get(0).url()).isEqualTo("https://example.com/");
    }

    @Test
    void crawlsAllLinkedPagesConcurrently() {
        // Главная ссылается на 20 внутренних страниц; каждая — валидный HTML-лист без ссылок.
        List<String> children = childUrls(20);
        Map<String, List<String>> g = new java.util.HashMap<>();
        g.put(BASE + "/", children);
        for (String u : children) {
            g.put(u, List.of()); // лист: валидная страница, дальше не ведёт
        }
        SiteCrawler crawler = crawler(noRobots(), graph(g), DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl(BASE, 100);

        // Все 20 детей + главная найдены (seed-хинты графа = 404 здесь не считаем — это поведение
        // сидирования, проверяется отдельно). Главное: ни одна реальная страница не потеряна.
        Set<String> got = result.pages().stream().map(p -> p.url()).collect(Collectors.toSet());
        assertThat(got).contains(BASE + "/");
        assertThat(got).containsAll(children);
        // Нет дублей — visited без гонок.
        assertThat(distinctUrls(result)).isEqualTo(result.pages().size());
    }

    @Test
    void respectsMaxPagesUnderConcurrency() {
        // 50 валидных детей (200), maxPages=10. Лимит = ровно 10 ПРИНЯТЫХ страниц, homepage включён
        // (не 11). Дети валидны, иначе тест вырождался бы в pages=1 и ничего не проверял.
        List<String> children = childUrls(50);
        Map<String, List<String>> g = new java.util.HashMap<>();
        g.put(BASE + "/", children);
        for (String u : children) {
            g.put(u, List.of());
        }
        SiteCrawler crawler = crawler(noRobots(), graph(g), DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl(BASE, 10);

        // Жёсткий потолок: ровно maxPages, ни на одну больше (homepage входит в лимит).
        assertThat(result.pages()).hasSize(10);
        assertThat(result.pages().get(0).url()).isEqualTo(BASE + "/");
        assertThat(distinctUrls(result)).isEqualTo(10);
    }

    @Test
    void temporarilyHeldSlotsDoNotStopCrawlEarly() {
        // P1: часть детей — медленные soft-404 (держат слот в работе, потом возвращают его). Краул
        // НЕ должен трактовать «слоты заняты сейчас» как «лимит достигнут» и остановиться рано —
        // должен добрать валидные страницы до ровно maxPages.
        List<String> bad = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> BASE + "/bad" + i).collect(Collectors.toList());
        List<String> good = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> BASE + "/good" + i).collect(Collectors.toList());
        List<String> all = new java.util.ArrayList<>();
        all.addAll(bad);   // плохие идут первыми в ссылках главной — займут слоты раньше
        all.addAll(good);

        SiteCrawler crawler = crawler(noRobotsMax(100), uri -> {
            String url = uri.toString();
            if ((BASE + "/").equals(url)) {
                return htmlResponse("home", all);
            }
            if (bad.contains(url)) {
                sleepQuietly(60);          // дольше держим слот «в работе»
                return notFound();          // soft-fail → слот возвращается
            }
            if (good.contains(url)) {
                return htmlResponse("good-" + url, List.of());
            }
            return notFound();
        }, DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl(BASE, 10);

        // Ровно maxPages принятых, несмотря на временно занятые слоты сбойными страницами.
        assertThat(result.pages()).hasSize(10);
        assertThat(result.pages().get(0).url()).isEqualTo(BASE + "/");
        // Все принятые (кроме homepage) — валидные good-страницы, ни одной bad.
        assertThat(result.pages().stream().skip(1).map(p -> p.url()))
                .allMatch(u -> u.contains("/good"));
    }

    @Test
    void startPageIsFirstInResults() {
        // Инвариант для selectDynamicTargets: pages.get(0) == стартовая страница даже при
        // недетерминированном параллельном порядке обхода.
        List<String> children = childUrls(15);
        Map<String, List<String>> g = new java.util.HashMap<>();
        g.put(BASE + "/", children);
        for (String u : children) {
            g.put(u, List.of());
        }
        SiteCrawler crawler = crawler(noRobots(), graph(g), DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl(BASE, 100);

        assertThat(result.pages()).hasSizeGreaterThan(1);
        assertThat(result.pages().get(0).url()).isEqualTo(BASE + "/");
    }

    @Test
    void noDuplicatePagesUnderConcurrency() {
        // Перекрёстные ссылки: каждая страница ссылается на все остальные. visited должен
        // отсечь повторы без гонок, иначе появятся дубли/лишние fetch.
        List<String> all = childUrls(12);
        Map<String, List<String>> g = new java.util.HashMap<>();
        g.put(BASE + "/", all);
        for (String u : all) {
            g.put(u, all); // каждый ребёнок ссылается на всех детей
        }
        SiteCrawler crawler = crawler(noRobots(), graph(g), DOMAIN);

        SiteCrawler.CrawlResult result = crawler.crawl(BASE, 100);

        assertThat(distinctUrls(result)).isEqualTo(result.pages().size());
        // 1 главная + 12 уникальных детей.
        assertThat(result.pages()).hasSize(13);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private interface Responder {
        PinnedHttpFetcher.Response respond(URI uri) throws Exception;
    }

    private static SiteCrawler crawler(java.util.function.Consumer<ComplianceWorkerProperties> tune,
                                       Responder responder, String resolvedHost) {
        try {
            ComplianceWorkerProperties props = new ComplianceWorkerProperties();
            tune.accept(props);
            UrlValidator validator = mock(UrlValidator.class);
            PinnedHttpFetcher fetcher = mock(PinnedHttpFetcher.class);
            InetAddress publicAddress = InetAddress.getByName("93.184.216.34");

            when(validator.isHostSafe(anyString())).thenReturn(true);
            when(validator.resolvePublicHost(anyString()))
                    .thenReturn(UrlValidator.ResolvedHost.ok(resolvedHost, List.of(publicAddress)));
            when(fetcher.fetch(any(URI.class), any(InetAddress.class), anyString(),
                    anyInt(), anyInt(), anyLong()))
                    .thenAnswer(inv -> {
                        // Небольшая задержка имитирует сетевой фетч — последовательный обход был бы
                        // заметно медленнее, проявляя реальную конкурентность.
                        Thread.sleep(20);
                        return responder.respond(inv.getArgument(0));
                    });
            return new SiteCrawler(props, validator, fetcher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static java.util.function.Consumer<ComplianceWorkerProperties> noRobots() {
        return noRobotsMax(100);
    }

    private static java.util.function.Consumer<ComplianceWorkerProperties> noRobotsMax(int maxPages) {
        return props -> {
            props.getCrawler().setRespectRobots(false);
            props.getCrawler().setRateLimitMs(0);          // в тесте паузы не нужны
            props.getCrawler().setMaxPages(maxPages);      // дефолт 20 иначе режет лимит ниже crawl(arg)
        };
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Граф url → внутренние ссылки. Любой неизвестный URL = 404. */
    private static Responder graph(Map<String, List<String>> pages) {
        return uri -> {
            String url = uri.toString();
            List<String> links = pages.get(url);
            if (links == null) {
                return notFound();
            }
            // title уникален per-URL → разный contentFingerprint, иначе acceptPage режет страницы
            // с идентичным контентом как duplicate-content (не баг краулера, артефакт мока).
            return htmlResponse("page-" + url, links);
        };
    }

    private static List<String> childUrls(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> BASE + "/p" + i)
                .collect(Collectors.toList());
    }

    private static int distinctUrls(SiteCrawler.CrawlResult result) {
        Set<String> seen = new HashSet<>();
        result.pages().forEach(p -> seen.add(p.url()));
        return seen.size();
    }

    private static PinnedHttpFetcher.Response htmlResponse(String title, List<String> links) {
        StringBuilder anchors = new StringBuilder();
        for (String l : links) {
            anchors.append("<a href=\"").append(l).append("\">link</a>\n");
        }
        // Текст уникален per-page (title в теле) и заведомо длиннее MIN_PAGE_TEXT_LENGTH (350),
        // чтобы ни soft-404, ни duplicate-content дедуп не отбраковывали валидные страницы графа.
        String body = ("Страница " + title + ". Создание сайтов, Telegram-боты, CRM и AI-интеграции "
                + "для бизнеса. Мы проектируем интерфейсы, настраиваем аналитику, подключаем формы "
                + "заявок, автоматизируем продажи и поддержку клиентов. Контакты, услуги, портфолио, "
                + "описание подхода, разработка, дизайн, интеграции, сопровождение, внедрение, "
                + "консультации, аудит, документация, обучение, миграция, оптимизация и развитие "
                + "уникального проекта " + title + ".").repeat(2);
        String html = """
                <!doctype html>
                <html>
                  <head><title>%s</title></head>
                  <body>
                    <main>
                      <h1>%s</h1>
                      <p>%s</p>
                      %s
                    </main>
                  </body>
                </html>
                """.formatted(title, title, body, anchors);
        return new PinnedHttpFetcher.Response(200, Map.of(), html);
    }

    private static PinnedHttpFetcher.Response notFound() {
        return new PinnedHttpFetcher.Response(404, Map.of(), "");
    }
}
