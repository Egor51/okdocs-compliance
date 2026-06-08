package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Заглушка {@link DynamicCrawler}, активная когда {@code compliance.crawler.dynamic.enabled} не
 * {@code true} (нет {@link CdpDynamicCrawler}-бина). {@link #isAvailable()} == false — сервис
 * пропускает dynamic re-crawl без ошибки и отдаёт STATIC-результат.
 */
@Component
@ConditionalOnMissingBean(CdpDynamicCrawler.class)
public class NoopDynamicCrawler implements DynamicCrawler {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public PageAnalysisResult crawlPage(String url) {
        throw new UnsupportedOperationException("Dynamic crawl not enabled");
    }
}
