package io.okdocs.compliance.contracts.enums;

/**
 * Жизненный цикл скана.
 * Цепочка: {@code QUEUED → CRAWLING → ANALYZING → COMPLETED | PARTIAL | FAILED}.
 *
 * <ul>
 *   <li>{@code COMPLETED} — все целевые страницы обработаны.</li>
 *   <li>{@code PARTIAL} — отчёт сформирован, но часть страниц не дошла (таймаут/ошибки fetch).</li>
 *   <li>{@code FAILED} — анализировать нечего (0 страниц) или скан признан зависшим (reaper).</li>
 * </ul>
 *
 * {@code CANCELLED} намеренно отсутствует в MVP: нет эндпоинта отмены и актора, который её
 * инициирует. Вернётся вместе с {@code POST /{id}/cancel}.
 */
public enum ScanStatus {
    QUEUED,
    CRAWLING,
    ANALYZING,
    COMPLETED,
    PARTIAL,
    FAILED;

    /** Терминальные статусы — из них нельзя уйти обратно. Логика статуса живёт на enum. */
    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED;
    }
}
