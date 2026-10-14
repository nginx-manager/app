package net.nginxmanager.stream;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.QueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StreamListQueryCountTest extends ConfigRootTest {

    @Autowired
    StreamService streamService;
    @Autowired
    StreamController streamController;
    @Autowired
    QueryCounter queryCounter;

    @Test
    void streamListQueryCountDoesNotGrowWithStreams() {
        AtomicInteger port = new AtomicInteger(10000);
        queryCounter.assertConstantQueries(
                n -> {
                    for (int i = 0; i < n; i++) {
                        streamService.create(new StreamForm(StreamProtocol.TCP, port.incrementAndGet(), "10.0.0.1:22",
                                null, null, null, null, null), "editor");
                    }
                },
                () -> assertThat(streamController.rows()).isNotEmpty(),
                1, 50);
    }
}
