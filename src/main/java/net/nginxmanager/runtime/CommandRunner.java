package net.nginxmanager.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 외부 명령 실행. 셸을 거치지 않고 인자 목록 그대로 실행한다. */
public class CommandRunner {

    public record Result(int exitCode, String output) {
    }

    public Result run(List<String> command, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            try (InputStream in = process.getInputStream()) {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new NginxCommandException(command.getFirst() + " timed out after " + timeout);
                }
                String output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new Result(process.exitValue(), output);
            }
        } catch (IOException e) {
            throw new NginxCommandException("failed to run " + command.getFirst(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NginxCommandException("interrupted while running " + command.getFirst(), e);
        }
    }
}
