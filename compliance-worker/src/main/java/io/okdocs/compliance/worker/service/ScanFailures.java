package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.contracts.enums.ScanFailureCode;
import io.okdocs.compliance.contracts.enums.ScanFailureStage;
import io.okdocs.compliance.contracts.enums.ScanFetchMode;
import io.okdocs.compliance.contracts.scan.ScanFailure;

import java.util.UUID;

/** Single source of failure policy and the temporary user-safe legacy message. */
public final class ScanFailures {

    private ScanFailures() {
    }

    public static ScanFailure failure(ScanFailureCode code, ScanFailureStage stage,
                                      boolean retryable, Integer httpStatus,
                                      ScanFetchMode fetchMode) {
        return new ScanFailure(code, stage, retryable, httpStatus, fetchMode, false, null);
    }

    public static ScanFailure browserUnavailable() {
        return new ScanFailure(
                ScanFailureCode.BROWSER_UNAVAILABLE,
                ScanFailureStage.BROWSER_FETCH,
                true, null, ScanFetchMode.BROWSER, false, null);
    }

    public static ScanFailure noPages() {
        return new ScanFailure(
                ScanFailureCode.HTTP_INVALID_RESPONSE,
                ScanFailureStage.HTTP_FETCH,
                true, null, ScanFetchMode.HTTP, false, null);
    }

    public static ScanFailure pipelineTimeout() {
        return new ScanFailure(
                ScanFailureCode.PIPELINE_TIMEOUT,
                ScanFailureStage.PIPELINE,
                true, null, null, false, null);
    }

    public static ScanFailure internal(UUID incidentId) {
        return new ScanFailure(
                ScanFailureCode.INTERNAL_ERROR,
                ScanFailureStage.PIPELINE,
                false, null, null, false, incidentId);
    }

    public static String legacyMessage(ScanFailure failure) {
        return switch (failure.code()) {
            case INVALID_URL -> "Адрес сайта указан неверно";
            case UNSAFE_TARGET -> "Адрес сайта недоступен для безопасной проверки";
            case DNS_NOT_FOUND -> "Домен сайта не найден";
            case DNS_TIMEOUT -> "Сервис доменных имён не ответил вовремя";
            case CONNECT_FAILED -> "Не удалось подключиться к сайту";
            case CONNECT_TIMEOUT -> "Сайт не ответил вовремя";
            case TLS_CERT_INVALID -> "Сертификат сайта недействителен";
            case TLS_HOSTNAME_MISMATCH -> "Сертификат не соответствует адресу сайта";
            case TLS_HANDSHAKE_FAILED -> "Не удалось установить защищённое соединение";
            case TLS_HANDSHAKE_TIMEOUT ->
                    "Сайт не завершил защищённое соединение вовремя";
            case ROBOTS_DENIED -> "Сайт запрещает автоматическую проверку";
            case HTTP_UNAUTHORIZED -> "Сайт требует авторизацию";
            case HTTP_FORBIDDEN -> "Сервер сайта отклонил запрос";
            case HTTP_NOT_FOUND -> "Страница сайта не найдена";
            case HTTP_RATE_LIMITED -> "Сайт временно ограничил количество запросов";
            case HTTP_CLIENT_ERROR -> "Сайт отклонил запрос";
            case HTTP_SERVER_ERROR -> "Сервер сайта временно недоступен";
            case RESPONSE_TIMEOUT -> "Сайт не передал ответ вовремя";
            case HTTP_INVALID_RESPONSE -> "Сайт вернул некорректный ответ";
            case RESPONSE_TOO_LARGE -> "Ответ сайта превышает безопасный размер";
            case REDIRECT_LOOP -> "Сайт перенаправляет запрос по кругу";
            case BROWSER_UNAVAILABLE -> "Динамический анализ временно недоступен";
            case BROWSER_NAVIGATION_TIMEOUT -> "Сайт не открылся в браузере вовремя";
            case PIPELINE_TIMEOUT -> "Проверка не завершилась за отведённое время";
            case PARSING_FAILED -> "Не удалось обработать содержимое сайта";
            case ANALYSIS_FAILED -> "Не удалось выполнить анализ сайта";
            case INTERNAL_ERROR, UNKNOWN -> "Не удалось завершить проверку из-за внутренней ошибки";
        };
    }
}
