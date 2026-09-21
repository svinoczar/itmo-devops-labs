package ru.itmo.devops.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class ApiApplication {
    private static final int BYTES_IN_MEGABYTE = 1024 * 1024;
    private static final int MEMORY_PAGE_SIZE = 4096;

    // Keeping strong references here prevents allocated blocks from being garbage-collected.
    private static final List<byte[]> HELD_MEMORY =
            Collections.synchronizedList(new ArrayList<>());

    // Publishing the calculation makes the burn loop observable to the JIT compiler.
    private static volatile long cpuSink;

    private ApiApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ApiApplication::health);
        server.createContext("/eat", ApiApplication::eat);
        server.createContext("/burn", ApiApplication::burn);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("api is listening on port %d%n", port);
    }

    private static void health(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            respond(exchange, 405, "method not allowed\n");
            return;
        }
        respond(exchange, 200, "OK\n");
    }

    private static void eat(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            respond(exchange, 405, "method not allowed\n");
            return;
        }

        String rawMegabytes = queryParameter(exchange.getRequestURI(), "mb");
        if (rawMegabytes == null) {
            respond(exchange, 400, "missing query parameter: mb\n");
            return;
        }

        final int megabytes;
        try {
            megabytes = Integer.parseInt(rawMegabytes);
            if (megabytes < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            respond(exchange, 400, "mb must be a non-negative integer\n");
            return;
        }

        for (int i = 0; i < megabytes; i++) {
            byte[] block = new byte[BYTES_IN_MEGABYTE];
            // Touch every page so the allocation becomes visible in resident memory (RSS).
            for (int offset = 0; offset < block.length; offset += MEMORY_PAGE_SIZE) {
                block[offset] = 1;
            }
            HELD_MEMORY.add(block);
        }

        respond(exchange, 200, "allocated and holding " + megabytes + " MB\n");
    }

    private static void burn(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            respond(exchange, 405, "method not allowed\n");
            return;
        }

        // This request intentionally never completes and occupies one executor thread/core.
        long value = cpuSink;
        while (true) {
            value = value * 1_664_525L + 1_013_904_223L;
            cpuSink = value;
        }
    }

    private static boolean isGet(HttpExchange exchange) {
        return "GET".equals(exchange.getRequestMethod());
    }

    private static String queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return parts[1];
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
