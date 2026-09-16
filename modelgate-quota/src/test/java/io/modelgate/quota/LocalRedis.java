package io.modelgate.quota;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Starts a throwaway redis-server for integration tests.
 *
 * <p>The binary is resolved in this order:
 * <ol>
 *   <li>{@code -Dmodelgate.test.redis.binary=/path/to/redis-server}</li>
 *   <li>{@code MODELGATE_REDIS_BINARY} environment variable</li>
 *   <li>common install locations (including {@code $HOME/.local/redis-src/bin/redis-server})</li>
 * </ol>
 * When none is found the test is skipped instead of failing, so plain {@code mvn test}
 * stays green on a machine without Redis.
 */
final class LocalRedis implements AutoCloseable {

    private final Process process;
    private final int port;

    private LocalRedis(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    int port() {
        return port;
    }

    static LocalRedis start() throws IOException {
        String binary = resolveBinary();
        assumeTrue(binary != null,
                "no redis-server binary found; run with -Dmodelgate.test.redis.binary=<path> "
                        + "to execute the Redis integration tests");

        int port = freePort();
        Process process = new ProcessBuilder(binary,
                "--port", String.valueOf(port),
                "--save", "",
                "--appendonly", "no",
                "--daemonize", "no")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        LocalRedis server = new LocalRedis(process, port);
        server.awaitReady();
        return server;
    }

    private void awaitReady() throws IOException {
        long deadline = System.currentTimeMillis() + 15_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("redis-server exited with code " + process.exitValue());
            }
            try {
                probe();
                return;
            } catch (Exception e) {
                last = e;
                sleep(100);
            }
        }
        throw new IOException("redis-server did not become ready on port " + port, last);
    }

    private void probe() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
        }
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + port);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            if (!"PONG".equals(connection.sync().ping())) {
                throw new IOException("unexpected PING reply");
            }
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String resolveBinary() {
        String property = System.getProperty("modelgate.test.redis.binary");
        if (isExecutable(property)) {
            return property;
        }
        String env = System.getenv("MODELGATE_REDIS_BINARY");
        if (isExecutable(env)) {
            return env;
        }
        String home = System.getProperty("user.home");
        for (String candidate : List.of(
                home + "/.local/redis-src/bin/redis-server",
                "/usr/local/bin/redis-server",
                "/usr/bin/redis-server",
                "/opt/homebrew/bin/redis-server")) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isExecutable(String path) {
        return path != null && new File(path).canExecute();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
