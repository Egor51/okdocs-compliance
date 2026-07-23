package io.okdocs.compliance.worker.crawler;

import io.okdocs.compliance.contracts.enums.ScanFailureCode;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinnedHttpFetcherTest {

    @Test
    void tlsHandshakeThatNeverAnswersIsBoundedAndClassified() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofPlatform().daemon().start(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    // Consume ClientHello but deliberately never produce a ServerHello.
                    while (socket.getInputStream().read() >= 0) {
                        // wait for the client-side timeout to close the connection
                    }
                } catch (Exception ignored) {
                    // The test closes the listener after the client completes.
                }
            });

            PinnedHttpFetcher fetcher = new PinnedHttpFetcher();
            URI uri = URI.create("https://localhost:" + server.getLocalPort() + "/");
            long started = System.nanoTime();

            assertThatThrownBy(() -> fetcher.fetch(
                    uri, InetAddress.getLoopbackAddress(), "test-agent",
                    500, 150, 1000, 1024))
                    .isInstanceOfSatisfying(FetchException.class, failure ->
                            assertThat(failure.failure().code())
                                    .isEqualTo(ScanFailureCode.TLS_HANDSHAKE_TIMEOUT));

            assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
            peer.join(1000);
            assertThat(peer.isAlive()).isFalse();
        }
    }
}
