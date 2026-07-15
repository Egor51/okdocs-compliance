package io.okdocs.compliance.api.service;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.contracts.enums.FindingSeverity;
import io.okdocs.compliance.contracts.enums.ScanTier;
import io.okdocs.compliance.contracts.exception.ComplianceValidationException;
import io.okdocs.compliance.contracts.scan.AffectedPageDto;
import io.okdocs.compliance.contracts.scan.FindingDto;
import io.okdocs.compliance.contracts.scan.PositiveCheckDto;
import io.okdocs.compliance.contracts.scan.ReportQualityDto;
import io.okdocs.compliance.contracts.scan.ScanReportResponse;
import io.okdocs.compliance.contracts.scan.ScanSummaryDto;
import io.okdocs.compliance.persistence.scan.ComplianceScan;
import io.okdocs.compliance.persistence.scan.ComplianceScanRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportPdfService {

    private static final String FONT_RESOURCE =
            "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("dd.MM.yyyy · HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final ScanCommandService scanCommandService;
    private final ComplianceScanRepository scanRepository;

    public PdfDocument generate(UUID scanId, CompliancePrincipal principal) {
        ScanReportResponse report = scanCommandService.getReport(scanId, principal);
        if (report.tier() != ScanTier.PREMIUM) {
            throw new ComplianceValidationException("PDF доступен только для полного отчёта");
        }
        ComplianceScan scan = scanRepository.findById(scanId).orElseThrow();
        Labels labels = Labels.forLocale(scan.getLocale());
        try {
            return new PdfDocument(render(report, labels), filename(report));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сформировать PDF отчёта " + scanId, e);
        }
    }

    private byte[] render(ScanReportResponse report, Labels labels) throws IOException {
        try (PDDocument document = new PDDocument();
             InputStream fontStream = ReportPdfService.class.getResourceAsStream(FONT_RESOURCE)) {
            if (fontStream == null) {
                throw new IllegalStateException("PDF font resource is unavailable");
            }
            PDFont font = PDType0Font.load(document, fontStream, true);
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle(labels.reportTitle + " — " + report.siteDomain());
            info.setAuthor("OKDOCS");
            info.setCreator("OKDOCS Compliance Intelligence");
            info.setSubject(labels.reportSubject);

            try (ProductPdf pdf = new ProductPdf(document, font, report, labels)) {
                pdf.cover();
                pdf.findings();
                pdf.positiveChecks();
            }
            addFooters(document, font, report, labels);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void addFooters(PDDocument document, PDFont font,
                                   ScanReportResponse report, Labels labels) throws IOException {
        int total = document.getNumberOfPages();
        for (int index = 0; index < total; index++) {
            PDPage page = document.getPage(index);
            try (PDPageContentStream footer = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                footer.setStrokingColor(Palette.BORDER);
                footer.setLineWidth(.6f);
                footer.moveTo(ProductPdf.MARGIN, 38);
                footer.lineTo(PDRectangle.A4.getWidth() - ProductPdf.MARGIN, 38);
                footer.stroke();
                drawText(footer, font, "OKDOCS  ·  " + report.siteDomain(),
                        ProductPdf.MARGIN, 23, 8, Palette.MUTED);
                String pages = labels.page + " " + (index + 1) + " / " + total;
                float width = textWidth(font, pages, 8);
                drawText(footer, font, pages,
                        PDRectangle.A4.getWidth() - ProductPdf.MARGIN - width,
                        23, 8, Palette.MUTED);
            }
        }
    }

    private static void drawText(PDPageContentStream stream, PDFont font, String text,
                                 float x, float y, float size, Color color) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static float textWidth(PDFont font, String value, float size) throws IOException {
        return font.getStringWidth(value) / 1000f * size;
    }

    private static String filename(ScanReportResponse report) {
        String domain = report.siteDomain() == null ? "site" : report.siteDomain()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("-+", "-");
        if (domain.isBlank()) domain = "site";
        return "okdocs-" + domain + "-" + report.id() + ".pdf";
    }

    public record PdfDocument(byte[] content, String filename) {
    }

    private static final class ProductPdf implements AutoCloseable {
        private static final float WIDTH = PDRectangle.A4.getWidth();
        private static final float HEIGHT = PDRectangle.A4.getHeight();
        private static final float MARGIN = 44;
        private static final float CONTENT_WIDTH = WIDTH - MARGIN * 2;
        private static final float CONTENT_BOTTOM = 54;

        private final PDDocument document;
        private final PDFont font;
        private final ScanReportResponse report;
        private final Labels labels;
        private PDPageContentStream content;
        private float y;

        private ProductPdf(PDDocument document, PDFont font,
                           ScanReportResponse report, Labels labels) {
            this.document = document;
            this.font = font;
            this.report = report;
            this.labels = labels;
        }

        private void cover() throws IOException {
            newPage(true);

            fillRect(0, HEIGHT - 238, WIDTH, 238, Palette.NAVY);
            fillRect(MARGIN, HEIGHT - 62, 18, 18, Palette.BLUE);
            textAt("O", MARGIN + 5.2f, HEIGHT - 57.5f, 8, Color.WHITE);
            textAt("OKDOCS", MARGIN + 27, HEIGHT - 57, 13, Color.WHITE);
            textAt(labels.productLine.toUpperCase(Locale.ROOT),
                    MARGIN + 27, HEIGHT - 72, 7.5f, Palette.BLUE_LIGHT);

            List<String> title = wrap(labels.reportTitle, 27, 325);
            float titleY = HEIGHT - 119;
            for (String line : title) {
                textAt(line, MARGIN, titleY, 27, Color.WHITE);
                titleY -= 32;
            }
            textAt(ellipsize(report.siteUrl(), 315, 12), MARGIN, HEIGHT - 192,
                    12, Palette.BLUE_LIGHT);

            int score = report.score() == null ? 0 : Math.max(0, Math.min(100, report.score()));
            Risk risk = Risk.forScore(score, labels);
            float scoreX = WIDTH - MARGIN - 128;
            float scoreY = HEIGHT - 205;
            fillRect(scoreX, scoreY, 128, 126, Palette.WHITE);
            fillRect(scoreX, scoreY + 119, 128, 7, risk.color);
            textAt(labels.riskIndex.toUpperCase(Locale.ROOT), scoreX + 15, scoreY + 99,
                    7.5f, Palette.MUTED);
            textAt(String.valueOf(score), scoreX + 15, scoreY + 52, 35, risk.color);
            textAt("/ 100", scoreX + 71, scoreY + 59, 10, Palette.MUTED);
            textAt(risk.label, scoreX + 15, scoreY + 27, 10, risk.color);

            metadataCard(MARGIN, HEIGHT - 297, 154, labels.created,
                    DATE.format(report.createdAt()));
            metadataCard(MARGIN + 164, HEIGHT - 297, 154, labels.jurisdiction,
                    String.valueOf(report.jurisdiction()));
            metadataCard(MARGIN + 328, HEIGHT - 297, CONTENT_WIDTH - 328, labels.duration,
                    formatDuration(report.durationMs()));

            textAt(labels.executiveSummary, MARGIN, HEIGHT - 333, 16, Palette.NAVY);
            textAt(labels.executiveSubtitle, MARGIN, HEIGHT - 350, 8.5f, Palette.MUTED);

            ScanSummaryDto summary = report.summary();
            int critical = summary == null ? 0 : summary.critical();
            int high = summary == null ? 0 : summary.high();
            int medium = summary == null ? 0 : summary.medium();
            int low = summary == null ? 0 : summary.low();
            float statY = HEIGHT - 432;
            float statWidth = (CONTENT_WIDTH - 27) / 4;
            severityCard(MARGIN, statY, statWidth, critical, labels.critical, Palette.RED);
            severityCard(MARGIN + statWidth + 9, statY, statWidth, high, labels.high, Palette.ORANGE);
            severityCard(MARGIN + (statWidth + 9) * 2, statY, statWidth, medium,
                    labels.medium, Palette.AMBER);
            severityCard(MARGIN + (statWidth + 9) * 3, statY, statWidth, low, labels.low, Palette.BLUE);

            float fineY = HEIGHT - 505;
            fillRect(MARGIN, fineY, CONTENT_WIDTH, 54, Palette.RED_TINT);
            fillRect(MARGIN, fineY, 4, 54, Palette.RED);
            textAt(labels.potentialFine.toUpperCase(Locale.ROOT), MARGIN + 16, fineY + 34,
                    7.5f, Palette.RED);
            String fine = summary == null || !hasText(summary.totalPotentialFine())
                    ? labels.notSpecified : summary.totalPotentialFine();
            textAt(ellipsize(fine, CONTENT_WIDTH - 32, 13), MARGIN + 16, fineY + 14,
                    13, Palette.NAVY);

            coverageCard(HEIGHT - 601);

            fillRect(MARGIN, 104, CONTENT_WIDTH, 79, Palette.WHITE);
            textAt(labels.aboutReport.toUpperCase(Locale.ROOT), MARGIN + 16, 162,
                    7.5f, Palette.BLUE);
            List<String> note = wrap(labels.aboutReportText, 9, CONTENT_WIDTH - 32);
            float noteY = 145;
            for (int i = 0; i < Math.min(note.size(), 4); i++) {
                textAt(note.get(i), MARGIN + 16, noteY, 9, Palette.BODY);
                noteY -= 12;
            }
            textAt(labels.reportId + ": " + report.id(), MARGIN, 78, 7.5f, Palette.MUTED);
        }

        private void findings() throws IOException {
            List<FindingDto> findings = report.findings() == null ? List.of() : report.findings();
            newSectionPage(labels.findings, labels.findingsSubtitle(findings.size()), Palette.RED);
            if (findings.isEmpty()) {
                emptyState(labels.noFindings, labels.noFindingsText, Palette.GREEN, Palette.GREEN_TINT);
                return;
            }
            int index = 1;
            for (FindingDto finding : findings) {
                finding(index++, finding);
            }
        }

        private void finding(int index, FindingDto finding) throws IOException {
            Severity severity = Severity.of(finding.severity(), labels);
            List<String> titleLines = wrap(
                    hasText(finding.title()) ? finding.title() : finding.code(), 13, CONTENT_WIDTH - 42);
            float headerHeight = 54 + Math.max(0, titleLines.size() - 1) * 17;
            ensure(headerHeight + 16);
            fillRect(MARGIN, y - headerHeight, CONTENT_WIDTH, headerHeight, Palette.WHITE);
            fillRect(MARGIN, y - headerHeight, 5, headerHeight, severity.color);
            fillRect(MARGIN + 16, y - 25, severity.badgeWidth, 17, severity.tint);
            textAt(severity.label, MARGIN + 22, y - 20, 7.5f, severity.color);
            textAt("#" + index, MARGIN + CONTENT_WIDTH - 30, y - 20, 8, Palette.MUTED);
            float titleY = y - 42;
            for (String line : titleLines) {
                textAt(line, MARGIN + 16, titleY, 13, Palette.NAVY);
                titleY -= 17;
            }
            y -= headerHeight + 8;

            twoColumnDetails(labels.legalBasis, finding.legalBasis(),
                    labels.fine, finding.fineAmount());
            infoPanel(labels.explanation, finding.explanation(), Palette.SURFACE,
                    Palette.SLATE, Palette.BODY);
            infoPanel(labels.recommendation, finding.recommendation(), Palette.BLUE_TINT,
                    Palette.BLUE, Palette.NAVY);
            infoPanel(labels.evidence, finding.evidence(), Palette.SURFACE,
                    Palette.SLATE, Palette.BODY);
            affectedPages(finding.affectedPages(), finding.sourceUrl());
            y -= 12;
        }

        private void positiveChecks() throws IOException {
            ReportQualityDto quality = report.quality();
            List<PositiveCheckDto> checks = quality == null
                    ? List.of() : quality.positiveChecks();
            if (checks.isEmpty()) return;
            newSectionPage(labels.positiveChecks, labels.positiveSubtitle, Palette.GREEN);
            for (PositiveCheckDto check : checks) {
                List<String> title = wrap(check.title(), 11, CONTENT_WIDTH - 54);
                List<String> message = hasText(check.message())
                        ? wrap(check.message(), 9, CONTENT_WIDTH - 54) : List.of();
                float height = 31 + title.size() * 15 + message.size() * 12;
                ensure(height + 8);
                fillRect(MARGIN, y - height, CONTENT_WIDTH, height, Palette.WHITE);
                fillCircle(MARGIN + 22, y - 23, 10, Palette.GREEN_TINT);
                textAt("+", MARGIN + 18.5f, y - 27, 11, Palette.GREEN);
                float lineY = y - 22;
                for (String line : title) {
                    textAt(line, MARGIN + 44, lineY, 11, Palette.NAVY);
                    lineY -= 15;
                }
                for (String line : message) {
                    textAt(line, MARGIN + 44, lineY - 2, 9, Palette.MUTED);
                    lineY -= 12;
                }
                y -= height + 8;
            }
        }

        private void newSectionPage(String title, String subtitle, Color accent) throws IOException {
            newPage(false);
            fillRect(MARGIN, HEIGHT - 139, 5, 56, accent);
            textAt(title, MARGIN + 17, HEIGHT - 101, 21, Palette.NAVY);
            textAt(ellipsize(subtitle, CONTENT_WIDTH - 20, 9),
                    MARGIN + 17, HEIGHT - 122, 9, Palette.MUTED);
            y = HEIGHT - 158;
        }

        private void newPage(boolean cover) throws IOException {
            if (content != null) content.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            fillRect(0, 0, WIDTH, HEIGHT, Palette.PAGE);
            if (!cover) {
                fillRect(0, HEIGHT - 62, WIDTH, 62, Palette.WHITE);
                fillRect(MARGIN, HEIGHT - 40, 13, 13, Palette.BLUE);
                textAt("O", MARGIN + 3.8f, HEIGHT - 36.5f, 6, Color.WHITE);
                textAt("OKDOCS", MARGIN + 20, HEIGHT - 37, 10, Palette.NAVY);
                String domain = ellipsize(report.siteDomain(), 220, 8);
                float domainWidth = textWidth(font, safeText(domain), 8);
                textAt(domain, WIDTH - MARGIN - domainWidth, HEIGHT - 37, 8, Palette.MUTED);
            }
            y = HEIGHT - 88;
        }

        private void metadataCard(float x, float bottom, float width, String label, String value)
                throws IOException {
            fillRect(x, bottom, width, 48, Palette.WHITE);
            textAt(label.toUpperCase(Locale.ROOT), x + 12, bottom + 30, 7, Palette.MUTED);
            textAt(ellipsize(value, width - 24, 9), x + 12, bottom + 13, 9, Palette.NAVY);
        }

        private void severityCard(float x, float bottom, float width, int value,
                                  String label, Color color) throws IOException {
            fillRect(x, bottom, width, 64, Palette.WHITE);
            fillCircle(x + 19, bottom + 43, 6, tint(color));
            fillCircle(x + 19, bottom + 43, 2.5f, color);
            textAt(String.valueOf(value), x + 34, bottom + 34, 19, Palette.NAVY);
            textAt(label.toUpperCase(Locale.ROOT), x + 12, bottom + 13, 7, Palette.MUTED);
        }

        private void coverageCard(float bottom) throws IOException {
            ReportQualityDto quality = report.quality();
            int passed = quality == null ? 0 : quality.passed();
            int failed = quality == null ? 0 : quality.failed();
            int skipped = quality == null ? 0 : quality.notEvaluated();
            int coverage = quality == null || quality.coveragePercent() == null
                    ? 0 : Math.max(0, Math.min(100, quality.coveragePercent()));
            fillRect(MARGIN, bottom, CONTENT_WIDTH, 77, Palette.WHITE);
            textAt(labels.coverage, MARGIN + 16, bottom + 55, 11, Palette.NAVY);
            String value = coverage + "%";
            float valueWidth = textWidth(font, safeText(value), 11);
            textAt(value, MARGIN + CONTENT_WIDTH - 16 - valueWidth, bottom + 55,
                    11, Palette.BLUE);
            fillRect(MARGIN + 16, bottom + 34, CONTENT_WIDTH - 32, 7, Palette.BORDER);
            fillRect(MARGIN + 16, bottom + 34, (CONTENT_WIDTH - 32) * coverage / 100f,
                    7, Palette.BLUE);
            String details = labels.passed + "  " + passed + "     "
                    + labels.failed + "  " + failed + "     "
                    + labels.notEvaluated + "  " + skipped;
            textAt(details, MARGIN + 16, bottom + 14, 8, Palette.MUTED);
        }

        private void twoColumnDetails(String leftLabel, String leftValue,
                                      String rightLabel, String rightValue) throws IOException {
            if (!hasText(leftValue) && !hasText(rightValue)) return;
            float gap = 8;
            float width = (CONTENT_WIDTH - gap) / 2;
            List<String> left = wrap(defaultValue(leftValue), 8.5f, width - 24);
            List<String> right = wrap(defaultValue(rightValue), 8.5f, width - 24);
            int maxLines = Math.max(left.size(), right.size());
            float height = 30 + maxLines * 11;
            ensure(height + 8);
            detailCell(MARGIN, y - height, width, height, leftLabel, left);
            detailCell(MARGIN + width + gap, y - height, width, height, rightLabel, right);
            y -= height + 8;
        }

        private void detailCell(float x, float bottom, float width, float height,
                                String label, List<String> lines) throws IOException {
            fillRect(x, bottom, width, height, Palette.WHITE);
            textAt(label.toUpperCase(Locale.ROOT), x + 12, bottom + height - 17,
                    7, Palette.MUTED);
            float lineY = bottom + height - 34;
            for (String line : lines) {
                textAt(line, x + 12, lineY, 8.5f, Palette.NAVY);
                lineY -= 11;
            }
        }

        private void infoPanel(String label, String value, Color background,
                               Color accent, Color textColor) throws IOException {
            if (!hasText(value)) return;
            List<String> allLines = wrap(value, 9, CONTENT_WIDTH - 36);
            int offset = 0;
            boolean continued = false;
            while (offset < allLines.size()) {
                ensure(52);
                int capacity = Math.max(1, (int) ((y - CONTENT_BOTTOM - 38) / 12));
                int count = Math.min(capacity, allLines.size() - offset);
                float height = 31 + count * 12;
                fillRect(MARGIN, y - height, CONTENT_WIDTH, height, background);
                fillRect(MARGIN, y - height, 4, height, accent);
                String heading = continued ? label + " · " + labels.continued : label;
                textAt(heading.toUpperCase(Locale.ROOT), MARGIN + 16, y - 18,
                        7, accent);
                float lineY = y - 36;
                for (int i = 0; i < count; i++) {
                    textAt(allLines.get(offset + i), MARGIN + 16, lineY, 9, textColor);
                    lineY -= 12;
                }
                y -= height + 8;
                offset += count;
                continued = true;
                if (offset < allLines.size()) newPage(false);
            }
        }

        private void affectedPages(List<AffectedPageDto> pages, String sourceUrl) throws IOException {
            List<String> rows = new ArrayList<>();
            if (pages != null) {
                for (AffectedPageDto page : pages) {
                    if (page == null || !hasText(page.url())) continue;
                    String row = page.url();
                    if (hasText(page.evidence())) row += " — " + page.evidence();
                    rows.addAll(wrap(row, 8.5f, CONTENT_WIDTH - 48));
                }
            }
            if (rows.isEmpty() && hasText(sourceUrl)) {
                rows.addAll(wrap(sourceUrl, 8.5f, CONTENT_WIDTH - 48));
            }
            if (rows.isEmpty()) return;
            int offset = 0;
            boolean continued = false;
            while (offset < rows.size()) {
                ensure(48);
                int capacity = Math.max(1, (int) ((y - CONTENT_BOTTOM - 31) / 14));
                int count = Math.min(capacity, rows.size() - offset);
                float height = 29 + count * 14;
                fillRect(MARGIN, y - height, CONTENT_WIDTH, height, Palette.WHITE);
                String heading = continued
                        ? labels.affectedPages + " · " + labels.continued
                        : labels.affectedPages;
                textAt(heading.toUpperCase(Locale.ROOT), MARGIN + 16, y - 18,
                        7, Palette.MUTED);
                float lineY = y - 38;
                for (int i = 0; i < count; i++) {
                    fillCircle(MARGIN + 19, lineY + 3, 2, Palette.BLUE);
                    textAt(rows.get(offset + i), MARGIN + 28, lineY, 8.5f, Palette.BODY);
                    lineY -= 14;
                }
                y -= height + 8;
                offset += count;
                continued = true;
                if (offset < rows.size()) newPage(false);
            }
        }

        private void emptyState(String title, String message, Color accent, Color background)
                throws IOException {
            fillRect(MARGIN, y - 108, CONTENT_WIDTH, 108, background);
            fillCircle(MARGIN + 31, y - 35, 14, Color.WHITE);
            textAt("+", MARGIN + 26, y - 40, 15, accent);
            textAt(title, MARGIN + 58, y - 31, 14, Palette.NAVY);
            List<String> lines = wrap(message, 9, CONTENT_WIDTH - 76);
            float lineY = y - 51;
            for (String line : lines) {
                textAt(line, MARGIN + 58, lineY, 9, Palette.MUTED);
                lineY -= 12;
            }
            y -= 120;
        }

        private void ensure(float required) throws IOException {
            if (y - required < CONTENT_BOTTOM) newPage(false);
        }

        private void fillRect(float x, float bottom, float width, float height, Color color)
                throws IOException {
            content.setNonStrokingColor(color);
            content.addRect(x, bottom, Math.max(0, width), Math.max(0, height));
            content.fill();
        }

        private void fillCircle(float cx, float cy, float radius, Color color) throws IOException {
            float k = .55228475f;
            content.setNonStrokingColor(color);
            content.moveTo(cx + radius, cy);
            content.curveTo(cx + radius, cy + k * radius, cx + k * radius, cy + radius, cx, cy + radius);
            content.curveTo(cx - k * radius, cy + radius, cx - radius, cy + k * radius, cx - radius, cy);
            content.curveTo(cx - radius, cy - k * radius, cx - k * radius, cy - radius, cx, cy - radius);
            content.curveTo(cx + k * radius, cy - radius, cx + radius, cy - k * radius, cx + radius, cy);
            content.fill();
        }

        private void textAt(String value, float x, float baseline, float size, Color color)
                throws IOException {
            drawText(content, font, safeText(value), x, baseline, size, color);
        }

        private List<String> wrap(String source, float size, float width) throws IOException {
            String normalized = safeText(defaultValue(source))
                    .replaceAll("[\\p{Cc}&&[^\\t]]", " ")
                    .replaceAll("\\s+", " ").trim();
            if (normalized.isEmpty()) return List.of();
            List<String> result = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : normalized.split(" ")) {
                if (line.isEmpty()) {
                    appendBrokenWord(result, line, word, size, width);
                    continue;
                }
                String candidate = line + " " + word;
                if (textWidth(font, candidate, size) <= width) {
                    line.append(' ').append(word);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    appendBrokenWord(result, line, word, size, width);
                }
            }
            if (!line.isEmpty()) result.add(line.toString());
            return result;
        }

        private void appendBrokenWord(List<String> result, StringBuilder line, String word,
                                      float size, float width) throws IOException {
            if (textWidth(font, word, size) <= width) {
                line.append(word);
                return;
            }
            StringBuilder part = new StringBuilder();
            for (int offset = 0; offset < word.length();) {
                int codePoint = word.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                if (!part.isEmpty() && textWidth(font, part + character, size) > width) {
                    result.add(part.toString());
                    part.setLength(0);
                }
                part.append(character);
                offset += Character.charCount(codePoint);
            }
            line.append(part);
        }

        private String ellipsize(String value, float maxWidth, float size) throws IOException {
            String safe = safeText(defaultValue(value));
            if (textWidth(font, safe, size) <= maxWidth) return safe;
            String suffix = "…";
            while (!safe.isEmpty() && textWidth(font, safe + suffix, size) > maxWidth) {
                safe = safe.substring(0, safe.offsetByCodePoints(safe.length(), -1));
            }
            return safe + suffix;
        }

        /** Любой знак из результатов сканирования не должен ломать весь PDF. */
        private String safeText(String source) throws IOException {
            String expanded = defaultValue(source)
                    .replace("₽", " руб.")
                    .replace("€", " EUR ")
                    .replace("£", " GBP ")
                    .replace("¥", " JPY ");
            StringBuilder safe = new StringBuilder(expanded.length());
            for (int offset = 0; offset < expanded.length();) {
                int codePoint = expanded.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                try {
                    font.encode(character);
                    safe.append(character);
                } catch (IllegalArgumentException ignored) {
                    safe.append('?');
                }
                offset += Character.charCount(codePoint);
            }
            return safe.toString();
        }

        @Override
        public void close() throws IOException {
            if (content != null) content.close();
        }

        private static Color tint(Color color) {
            return new Color(
                    Math.min(255, (int) (color.getRed() * .12 + 224)),
                    Math.min(255, (int) (color.getGreen() * .12 + 224)),
                    Math.min(255, (int) (color.getBlue() * .12 + 224)));
        }

        private static String defaultValue(String value) {
            return value == null ? "" : value;
        }

        private static String formatDuration(Long durationMs) {
            if (durationMs == null) return "—";
            if (durationMs < 1_000) return durationMs + " ms";
            return String.format(Locale.ROOT, "%.1f s", durationMs / 1_000d);
        }
    }

    private record Severity(String label, Color color, Color tint, float badgeWidth) {
        private static Severity of(FindingSeverity severity, Labels labels) {
            if (severity == FindingSeverity.CRITICAL) {
                return new Severity(labels.critical.toUpperCase(Locale.ROOT),
                        Palette.RED, Palette.RED_TINT, 66);
            }
            if (severity == FindingSeverity.HIGH) {
                return new Severity(labels.high.toUpperCase(Locale.ROOT),
                        Palette.ORANGE, Palette.ORANGE_TINT, 56);
            }
            if (severity == FindingSeverity.MEDIUM) {
                return new Severity(labels.medium.toUpperCase(Locale.ROOT),
                        Palette.AMBER, Palette.AMBER_TINT, 60);
            }
            return new Severity(labels.low.toUpperCase(Locale.ROOT),
                    Palette.BLUE, Palette.BLUE_TINT, 52);
        }
    }

    private record Risk(String label, Color color) {
        private static Risk forScore(int score, Labels labels) {
            if (score >= 75) return new Risk(labels.riskHigh, Palette.RED);
            if (score >= 50) return new Risk(labels.riskElevated, Palette.ORANGE);
            if (score >= 25) return new Risk(labels.riskModerate, Palette.AMBER);
            return new Risk(labels.riskLow, Palette.GREEN);
        }
    }

    private static final class Palette {
        private static final Color NAVY = new Color(15, 23, 42);
        private static final Color BODY = new Color(51, 65, 85);
        private static final Color MUTED = new Color(100, 116, 139);
        private static final Color SLATE = new Color(71, 85, 105);
        private static final Color BLUE = new Color(37, 99, 235);
        private static final Color BLUE_LIGHT = new Color(191, 219, 254);
        private static final Color BLUE_TINT = new Color(239, 246, 255);
        private static final Color GREEN = new Color(22, 163, 74);
        private static final Color GREEN_TINT = new Color(240, 253, 244);
        private static final Color RED = new Color(220, 38, 38);
        private static final Color RED_TINT = new Color(254, 242, 242);
        private static final Color ORANGE = new Color(234, 88, 12);
        private static final Color ORANGE_TINT = new Color(255, 247, 237);
        private static final Color AMBER = new Color(202, 138, 4);
        private static final Color AMBER_TINT = new Color(254, 252, 232);
        private static final Color PAGE = new Color(246, 248, 252);
        private static final Color SURFACE = new Color(248, 250, 252);
        private static final Color WHITE = Color.WHITE;
        private static final Color BORDER = new Color(226, 232, 240);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Labels(
            String reportTitle, String reportSubject, String productLine, String created,
            String reportId, String jurisdiction, String duration, String riskIndex,
            String executiveSummary, String executiveSubtitle, String critical, String high,
            String medium, String low, String potentialFine, String notSpecified,
            String coverage, String passed, String failed, String notEvaluated,
            String aboutReport, String aboutReportText, String findings, String noFindings,
            String noFindingsText, String legalBasis, String fine, String explanation,
            String recommendation, String evidence, String affectedPages, String positiveChecks,
            String positiveSubtitle, String continued, String andMore, String page,
            String riskHigh, String riskElevated, String riskModerate, String riskLow,
            String findingsSubtitlePattern
    ) {
        private String findingsSubtitle(int count) {
            return String.format(Locale.ROOT, findingsSubtitlePattern, count);
        }

        private static Labels forLocale(String locale) {
            if ("en".equalsIgnoreCase(locale)) {
                return new Labels(
                        "Compliance scan report", "Automated website compliance scan",
                        "Compliance intelligence", "Created", "Report ID", "Jurisdiction",
                        "Scan time", "External risk index", "Executive summary",
                        "A clear overview of detected risks and automated coverage",
                        "Critical", "High", "Medium", "Low", "Potential fine", "Not specified",
                        "Automated check coverage", "Passed", "Failed", "Not evaluated",
                        "About this report",
                        "The report highlights observable compliance risks and prioritizes next actions. It is based on an automated scan and should be complemented by expert review.",
                        "Detected findings", "No findings detected",
                        "No observable risks were detected by the automated checks included in this scan.",
                        "Legal basis", "Potential fine", "Why it matters", "How to fix",
                        "Evidence", "Affected pages", "What is already done well",
                        "Positive controls detected during the automated scan", "continued",
                        "and more", "Page", "High risk", "Elevated risk", "Moderate risk",
                        "Low risk", "%d findings prioritized by severity and impact");
            }
            return new Labels(
                    "Отчёт о проверке", "Автоматическая проверка сайта",
                    "Compliance intelligence", "Создан", "ID отчёта", "Юрисдикция",
                    "Время проверки", "Индекс внешнего риска", "Главное о результате",
                    "Краткая сводка рисков и покрытия автоматической проверки",
                    "Критичные", "Высокие", "Средние", "Низкие", "Потенциальный штраф",
                    "Не указан", "Покрытие автоматической проверки", "Пройдено",
                    "Не пройдено", "Не проверено", "Об этом отчёте",
                    "Отчёт показывает наблюдаемые риски соответствия и помогает определить приоритетные действия. Автоматическую проверку рекомендуется дополнить экспертной оценкой.",
                    "Обнаруженные нарушения", "Нарушений не обнаружено",
                    "В рамках доступных автоматических проверок наблюдаемые риски не обнаружены.",
                    "Правовое основание", "Потенциальный штраф", "Почему это важно",
                    "Как исправить", "Доказательство", "Затронутые страницы",
                    "Что уже сделано хорошо", "Положительные результаты автоматической проверки",
                    "продолжение", "и ещё", "Страница", "Высокий риск", "Повышенный риск",
                    "Умеренный риск", "Низкий риск",
                    "Найдено нарушений: %d · отсортировано по серьёзности и влиянию");
        }
    }
}
