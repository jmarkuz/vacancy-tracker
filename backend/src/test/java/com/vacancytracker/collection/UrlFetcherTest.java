package com.vacancytracker.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlFetcherTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8080/x", "http://localhost/x", "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.9/", "http://100.64.0.1/", "http://[::1]/",
            "http://[fd00::1]/", "http://0.0.0.0/"})
    void internalAddressesAreRefusedBeforeAnyRequest(String url) {
        assertThatThrownBy(() -> new UrlFetcher(false).fetch(url))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private network");
    }

    @Test
    void aPublicAddressIsNotInternal() throws Exception {
        assertThat(UrlFetcher.isInternal(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(UrlFetcher.isInternal(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    @Test
    void anInvalidAddressDoesNotEchoTheInput() {
        assertThatThrownBy(() -> new UrlFetcher(false).fetch("http://exa mple.com/?k=secret"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The address is not valid");
    }
}
