package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.persistence.scan.ComplianceFinding;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Суммирует диапазоны штрафов из {@code fineAmount} findings (§5.5). Формат строки свободный:
 * ищем все числа подряд, берём минимум/максимум. Перенос из MVP okdocks.
 */
@Component
public class FineCalculator {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d[\\d\\s]*)");

    public String totalRange(List<ComplianceFinding> findings) {
        long min = 0;
        long max = 0;
        for (ComplianceFinding f : findings) {
            long[] range = parse(f.getFineAmount());
            min += range[0];
            max += range[1];
        }
        if (max == 0) {
            return "0 ₽";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        DecimalFormat fmt = new DecimalFormat("#,##0", symbols);
        return "от " + fmt.format(min) + " до " + fmt.format(max) + " ₽";
    }

    long[] parse(String fineAmount) {
        if (fineAmount == null || fineAmount.isBlank()) {
            return new long[]{0, 0};
        }
        Matcher m = NUMBER_PATTERN.matcher(fineAmount);
        long min = Long.MAX_VALUE;
        long max = 0;
        while (m.find()) {
            String raw = m.group(1).replaceAll("\\s", "");
            if (raw.isEmpty()) {
                continue;
            }
            try {
                long v = Long.parseLong(raw);
                if (v < 1000) {
                    continue; // отбрасываем "152" из "152-ФЗ", части номеров статей и т.п.
                }
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        if (min == Long.MAX_VALUE) {
            return new long[]{0, 0};
        }
        return new long[]{min, max};
    }
}
