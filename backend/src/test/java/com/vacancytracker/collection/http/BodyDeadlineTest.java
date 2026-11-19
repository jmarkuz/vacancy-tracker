package com.vacancytracker.collection.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class BodyDeadlineTest {

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> responseWith(InputStream body) {
        HttpResponse<InputStream> r = mock(HttpResponse.class);
        when(r.body()).thenReturn(body);
        when(r.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return r;
    }

    @Test
    void aBodyThatStallsIsCutAtTheDeadline() {
        CountDownLatch closed = new CountDownLatch(1);
        InputStream stalled = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        assertThatThrownBy(() -> HttpFetcher.readBounded(responseWith(stalled), Duration.ofMillis(200)))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("not received within");
    }

    @Test
    void aBodyThatArrivesInTimeIsRead() {
        InputStream ok = new ByteArrayInputStream("hello".getBytes());
        assertThat(HttpFetcher.readBounded(responseWith(ok), Duration.ofSeconds(5))).isEqualTo("hello");
    }

    @Test
    void readHeadCutsALongBodyAtTheLimitWithoutFailing() throws IOException {
        InputStream long_ = new ByteArrayInputStream("0123456789".getBytes());
        assertThat(HttpFetcher.readHead(long_, 4, Duration.ofSeconds(5))).isEqualTo("0123".getBytes());
    }

    @Test
    void readHeadGivesUpOnABodyThatStalls() {
        CountDownLatch closed = new CountDownLatch(1);
        InputStream stalled = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        assertThatThrownBy(() -> HttpFetcher.readHead(stalled, 1024, Duration.ofMillis(200)))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("not received within");
    }

    @Test
    void aBodyOverTheLimitIsRefusedAndNamesTheLimitAndTheSetting() {
        InputStream big = new ByteArrayInputStream(new byte[11]);
        assertThatThrownBy(() -> HttpFetcher.readBounded(responseWith(big), Duration.ofSeconds(5), 10))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("larger than 10 bytes")
                .hasMessageContaining("APP_COLLECTION_HTTP_MAX_BODY_MB");
    }

    @Test
    void aBodyExactlyAtTheLimitIsRead() {
        InputStream exact = new ByteArrayInputStream(new byte[10]);
        assertThat(HttpFetcher.readBounded(responseWith(exact), Duration.ofSeconds(5), 10)).hasSize(10);
    }

    /** The size 5 MB used to be the limit and failed whole-list sources; the default must take several MB. */
    @Test
    void theDefaultLimitTakesAnAnswerOfSeveralMegabytes() {
        InputStream six = new ByteArrayInputStream(new byte[6 * 1024 * 1024]);
        assertThat(HttpFetcher.readBounded(responseWith(six), Duration.ofSeconds(30))).hasSize(6 * 1024 * 1024);
    }
}
