package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.enums.ScanFailureCode;
import io.okdocs.compliance.contracts.enums.ScanFailureStage;
import io.okdocs.compliance.contracts.enums.ScanFetchMode;
import io.okdocs.compliance.contracts.scan.ScanFailure;
import io.okdocs.compliance.worker.service.ScanFailures;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.ConnectException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.cert.CertificateException;
import java.util.zip.GZIPInputStream;

/**
 * Minimal HTTP/1.1 fetcher that connects to a pre-resolved IP while preserving the original Host
 * header. For HTTPS it still uses the original hostname for SNI and endpoint identification, so
 * certificate verification is not weakened by IP pinning.
 */
@Component
public class PinnedHttpFetcher {

    private static final int MAX_HEADER_BYTES = 64 * 1024;

    public Response fetch(URI uri, InetAddress address, String userAgent,
                          int connectTimeoutMs, int tlsHandshakeTimeoutMs,
                          int readTimeoutMs, long maxBodyBytes)
            throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = effectivePort(uri, scheme);
        long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readTimeoutMs);
        try (Socket socket = openSocket(
                uri, address, scheme, port, connectTimeoutMs, tlsHandshakeTimeoutMs, deadlineNanos)) {
            socket.setSoTimeout(boundedTimeout(readTimeoutMs, deadlineNanos));
            writeRequest(socket.getOutputStream(), uri, userAgent);
            try {
                return readResponse(socket.getInputStream(), maxBodyBytes);
            } catch (FetchException e) {
                throw e;
            } catch (SocketTimeoutException e) {
                throw failure(ScanFailureCode.RESPONSE_TIMEOUT, ScanFailureStage.HTTP_FETCH,
                        true, null, "Timed out before HTTP response", e);
            } catch (BodyTooLargeException e) {
                throw failure(ScanFailureCode.RESPONSE_TOO_LARGE, ScanFailureStage.HTTP_FETCH,
                        false, null, "HTTP response body exceeds the configured limit", e);
            } catch (IOException e) {
                throw failure(ScanFailureCode.HTTP_INVALID_RESPONSE, ScanFailureStage.HTTP_FETCH,
                        true, null, "Invalid HTTP response", e);
            }
        }
    }

    private Socket openSocket(URI uri, InetAddress address, String scheme, int port,
                              int connectTimeoutMs, int tlsHandshakeTimeoutMs, long deadlineNanos)
            throws IOException {
        Socket plain = new Socket();
        try {
            try {
                plain.connect(new InetSocketAddress(address, port),
                        boundedTimeout(connectTimeoutMs, deadlineNanos));
            } catch (SocketTimeoutException e) {
                throw failure(ScanFailureCode.CONNECT_TIMEOUT, ScanFailureStage.CONNECT,
                        true, null, "TCP connect timed out", e);
            } catch (ConnectException | NoRouteToHostException e) {
                throw failure(ScanFailureCode.CONNECT_FAILED, ScanFailureStage.CONNECT,
                        true, null, "TCP connect failed", e);
            }
            if (!"https".equals(scheme)) {
                return plain;
            }
            String host = uri.getHost();
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            if (isDnsName(host)) {
                params.setServerNames(List.of(new SNIHostName(host)));
            }
            ssl.setSSLParameters(params);
            ssl.setSoTimeout(boundedTimeout(tlsHandshakeTimeoutMs, deadlineNanos));
            try {
                ssl.startHandshake();
            } catch (SocketTimeoutException e) {
                throw failure(ScanFailureCode.TLS_HANDSHAKE_TIMEOUT, ScanFailureStage.TLS,
                        true, null, "TLS handshake timed out", e);
            } catch (SSLHandshakeException e) {
                throw tlsHandshakeFailure(e);
            } catch (SSLException e) {
                throw failure(ScanFailureCode.TLS_HANDSHAKE_FAILED, ScanFailureStage.TLS,
                        false, null, "TLS handshake failed", e);
            }
            return ssl;
        } catch (IOException | RuntimeException e) {
            try {
                plain.close();
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            throw e;
        }
    }

    private void writeRequest(OutputStream out, URI uri, String userAgent) throws IOException {
        String request = "GET " + requestTarget(uri) + " HTTP/1.1\r\n"
                + "Host: " + hostHeader(uri) + "\r\n"
                + "User-Agent: " + userAgent + "\r\n"
                + "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private Response readResponse(InputStream in, long maxBodyBytes) throws IOException {
        String headersRaw = readHeaders(in);
        String[] lines = headersRaw.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IOException("Invalid HTTP response");
        }
        int statusCode = parseStatus(lines[0]);
        Map<String, List<String>> headers = parseHeaders(lines);
        byte[] body;
        try {
            body = readBody(in, headers, maxBodyBytes);
            if (header(headers, "content-encoding")
                    .map(v -> v.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false)) {
                body = gunzip(body, maxBodyBytes);
            }
        } catch (SocketTimeoutException e) {
            throw failure(ScanFailureCode.RESPONSE_TIMEOUT, ScanFailureStage.HTTP_FETCH,
                    true, statusCode, "Timed out while reading HTTP response body", e);
        } catch (BodyTooLargeException e) {
            throw failure(ScanFailureCode.RESPONSE_TOO_LARGE, ScanFailureStage.HTTP_FETCH,
                    false, statusCode, "HTTP response body exceeds the configured limit", e);
        } catch (IOException e) {
            throw failure(ScanFailureCode.HTTP_INVALID_RESPONSE, ScanFailureStage.HTTP_FETCH,
                    true, statusCode, "Invalid HTTP response body", e);
        }
        Charset charset = charset(headers);
        return new Response(statusCode, headers, new String(body, charset));
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int matched = 0;
        int[] marker = {'\r', '\n', '\r', '\n'};
        while (out.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            out.write(b);
            if (b == marker[matched]) {
                matched++;
                if (matched == marker.length) {
                    return out.toString(StandardCharsets.ISO_8859_1);
                }
            } else {
                matched = b == marker[0] ? 1 : 0;
            }
        }
        throw new IOException("HTTP headers too large or incomplete");
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Invalid HTTP status line");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid HTTP status code", e);
        }
    }

    private static Map<String, List<String>> parseHeaders(String[] lines) {
        Map<String, List<String>> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    private static byte[] readBody(InputStream in, Map<String, List<String>> headers, long maxBodyBytes)
            throws IOException {
        String transferEncoding = header(headers, "transfer-encoding").orElse("");
        if (transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in, maxBodyBytes);
        }
        long contentLength = header(headers, "content-length").map(PinnedHttpFetcher::parseLong).orElse(-1L);
        return readFixedOrToEof(in, contentLength, maxBodyBytes);
    }

    private static byte[] readChunked(InputStream in, long maxBodyBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readAsciiLine(in);
            int semi = line.indexOf(';');
            String sizeText = semi >= 0 ? line.substring(0, semi) : line;
            int size;
            try {
                size = Integer.parseInt(sizeText.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size", e);
            }
            if (size == 0) {
                return out.toByteArray();
            }
            copyLimited(in, out, size, maxBodyBytes);
            expectCrlf(in);
        }
    }

    private static byte[] readFixedOrToEof(InputStream in, long contentLength, long maxBodyBytes)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long remaining = contentLength;
        while (remaining != 0) {
            int limit = buffer.length;
            if (remaining > 0) {
                limit = (int) Math.min(limit, remaining);
            }
            int read = in.read(buffer, 0, limit);
            if (read < 0) {
                break;
            }
            appendLimited(out, buffer, read, maxBodyBytes);
            if (remaining > 0) {
                remaining -= read;
            }
        }
        return out.toByteArray();
    }

    private static void copyLimited(InputStream in, ByteArrayOutputStream out, int bytes, long maxBodyBytes)
            throws IOException {
        byte[] buffer = new byte[8192];
        int remaining = bytes;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Unexpected EOF in chunk");
            }
            appendLimited(out, buffer, read, maxBodyBytes);
            remaining -= read;
        }
    }

    private static void appendLimited(ByteArrayOutputStream out, byte[] buffer, int read, long maxBodyBytes)
            throws IOException {
        if ((long) out.size() + read > maxBodyBytes) {
            int allowed = (int) Math.max(0, maxBodyBytes - out.size());
            if (allowed > 0) {
                out.write(buffer, 0, allowed);
            }
            throw new BodyTooLargeException();
        }
        out.write(buffer, 0, read);
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Unexpected EOF");
            }
            if (b == '\n') {
                byte[] bytes = out.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            out.write(b);
        }
    }

    private static void expectCrlf(InputStream in) throws IOException {
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("Invalid chunk delimiter");
        }
    }

    private static byte[] gunzip(byte[] body, long maxBodyBytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
            return readFixedOrToEof(gzip, -1, maxBodyBytes);
        }
    }

    private static Charset charset(Map<String, List<String>> headers) {
        String contentType = header(headers, "content-type").orElse("");
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(trimmed.substring("charset=".length()).trim());
                } catch (Exception ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static java.util.Optional<String> header(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(values.get(0));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static int effectivePort(URI uri, String scheme) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equals(scheme) ? 443 : 80;
    }

    private static String requestTarget(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String hostHeader(URI uri) {
        String host = uri.getHost();
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port > 0 && port != defaultPort(scheme)) {
            return host + ":" + port;
        }
        return host;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    private static int boundedTimeout(int configuredMs, long deadlineNanos) throws FetchException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw failure(ScanFailureCode.RESPONSE_TIMEOUT, ScanFailureStage.HTTP_FETCH,
                    true, null, "Per-page fetch deadline exhausted", null);
        }
        long remainingMs = Math.max(1L,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return (int) Math.min(configuredMs, Math.min(Integer.MAX_VALUE, remainingMs));
    }

    private static boolean isDnsName(String host) {
        return host != null && !host.contains(":") && !host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private static FetchException tlsHandshakeFailure(SSLHandshakeException cause) {
        String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        boolean hostnameMismatch = message.contains("subject alternative")
                || message.contains("no name matching")
                || message.contains("hostname");
        if (!hostnameMismatch) {
            for (Throwable current = cause; current != null; current = current.getCause()) {
                String currentMessage = current.getMessage() == null
                        ? "" : current.getMessage().toLowerCase(Locale.ROOT);
                if (currentMessage.contains("subject alternative")
                        || currentMessage.contains("no name matching")
                        || currentMessage.contains("hostname")) {
                    hostnameMismatch = true;
                    break;
                }
            }
        }
        if (hostnameMismatch) {
            return failure(ScanFailureCode.TLS_HOSTNAME_MISMATCH, ScanFailureStage.TLS,
                    false, null, "TLS certificate hostname mismatch", cause);
        }
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof CertificateException) {
                return failure(ScanFailureCode.TLS_CERT_INVALID, ScanFailureStage.TLS,
                        false, null, "TLS certificate validation failed", cause);
            }
        }
        return failure(ScanFailureCode.TLS_HANDSHAKE_FAILED, ScanFailureStage.TLS,
                false, null, "TLS handshake failed", cause);
    }

    private static FetchException failure(ScanFailureCode code, ScanFailureStage stage,
                                          boolean retryable, Integer httpStatus,
                                          String diagnosticMessage, Throwable cause) {
        ScanFailure failure = ScanFailures.failure(
                code, stage, retryable, httpStatus, ScanFetchMode.HTTP);
        return new FetchException(failure, diagnosticMessage, cause);
    }

    private static final class BodyTooLargeException extends IOException {
    }

    public record Response(int statusCode, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }
}
