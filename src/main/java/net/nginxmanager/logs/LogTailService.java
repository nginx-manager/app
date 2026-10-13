package net.nginxmanager.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 로그 실시간 보기(SSE). 1초마다 열린 스트림의 파일 끝을 확인해 새 줄을 보낸다. */
@Service
public class LogTailService {

    private static final Logger log = LoggerFactory.getLogger(LogTailService.class);
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    static final int MAX_STREAMS = 20;
    static final int INITIAL_LINES = 50;

    private record Tail(SseEmitter emitter, LogCursor cursor) {
    }

    private final Set<Tail> tails = ConcurrentHashMap.newKeySet();

    public SseEmitter open(Path file) throws IOException {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        if (tails.size() >= MAX_STREAMS) {
            emitter.send(SseEmitter.event().name("busy").data("동시에 볼 수 있는 실시간 로그 수를 넘었습니다."));
            emitter.complete();
            return emitter;
        }
        Tail tail = new Tail(emitter, new LogCursor(file));
        // 최근 줄을 먼저 보여 준다(오래된 줄부터)
        List<String> recent = LogSearch.search(file, null, INITIAL_LINES, LogSearch.CHUNK * 4L).lines();
        for (int i = recent.size() - 1; i >= 0; i--) {
            emitter.send(SseEmitter.event().name("line").data(recent.get(i)));
        }
        emitter.onCompletion(() -> tails.remove(tail));
        emitter.onTimeout(() -> tails.remove(tail));
        emitter.onError(e -> tails.remove(tail));
        tails.add(tail);
        return emitter;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        for (Tail tail : tails) {
            try {
                for (String line : tail.cursor().readNewLines()) {
                    tail.emitter().send(SseEmitter.event().name("line").data(line));
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("closing log stream: {}", e.getMessage());
                tails.remove(tail);
                tail.emitter().completeWithError(e);
            }
        }
    }

    int openStreams() {
        return tails.size();
    }
}
