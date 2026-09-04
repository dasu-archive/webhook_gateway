///usr/bin/env java --source 21 "$0" "$@"; exit $?

// 목 소비자. 설계 11.1.
//
// 실제 소비자로는 "죽였다 살리는" 상황을 만들 수 없다. 시나리오를 런타임에 바꿀 수 있는
// 가짜 소비자가 있어야 재시도·백오프·중복 차단을 실제로 관찰할 수 있다.
//
// 실행:  java tools/MockConsumer.java [port]
//
// 소비 엔드포인트
//   POST /consume
//
// 제어 평면
//   GET  /_control/state                     현재 모드와 통계
//   POST /_control/mode?mode=OK&delayMs=0&failFirst=0&status=500
//   POST /_control/down?seconds=30           N초간 500 (배포 중 다운 흉내)
//   GET  /_control/received                  받은 이벤트 ID 와 수신 횟수
//   POST /_control/reset

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MockConsumer {

    enum Mode { OK, FAIL_5XX, FAIL_4XX, FAIL_THEN_OK }

    static final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
    static final AtomicLong delayMs = new AtomicLong(0);
    static final AtomicInteger failFirst = new AtomicInteger(0);
    static final AtomicInteger failStatus = new AtomicInteger(500);
    static final AtomicReference<Instant> downUntil = new AtomicReference<>(Instant.EPOCH);

    /** 이벤트 ID -> 수신 횟수. 이게 없으면 "중복 0건"을 증명할 수 없다(설계 11.1). */
    static final Map<String, Integer> receiptsByEventId = new ConcurrentHashMap<>();
    static final AtomicInteger totalRequests = new AtomicInteger(0);
    static final AtomicInteger delivered = new AtomicInteger(0);
    static final List<String> recentLog = java.util.Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/consume", MockConsumer::consume);
        server.createContext("/_control/", MockConsumer::control);
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.start();
        System.out.println("목 소비자 실행: http://localhost:" + port + "/consume");
        System.out.println("제어:          http://localhost:" + port + "/_control/state");
    }

    static void consume(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        String eventId = header(exchange, "X-Gateway-Event-Id");
        String attempt = header(exchange, "X-Gateway-Attempt");
        int seen = receiptsByEventId.merge(eventId == null ? "(no-id)" : eventId, 1, Integer::sum);
        totalRequests.incrementAndGet();

        long delay = delayMs.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int status = decideStatus(seen);
        if (status >= 200 && status < 300) {
            delivered.incrementAndGet();
        }

        log("event=%s attempt=%s seen=%d size=%d -> %d".formatted(eventId, attempt, seen, body.length, status));
        respond(exchange, status, "{\"received\":\"" + eventId + "\",\"seen\":" + seen + "}");
    }

    static int decideStatus(int seenCount) {
        if (Instant.now().isBefore(downUntil.get())) {
            return 503;
        }
        return switch (mode.get()) {
            case OK -> 200;
            case FAIL_5XX -> failStatus.get();
            case FAIL_4XX -> 400;
            // 처음 M회는 실패하고 그 다음부터 성공. 재시도가 실제로 먹히는지 보는 시나리오.
            case FAIL_THEN_OK -> seenCount <= failFirst.get() ? failStatus.get() : 200;
        };
    }

    static void control(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> q = query(exchange.getRequestURI());

        switch (path) {
            case "/_control/state" -> respond(exchange, 200, """
                    {"mode":"%s","delayMs":%d,"failFirst":%d,"failStatus":%d,"downUntil":"%s",
                     "totalRequests":%d,"delivered":%d,"distinctEvents":%d,"duplicates":%d}"""
                    .formatted(mode.get(), delayMs.get(), failFirst.get(), failStatus.get(), downUntil.get(),
                            totalRequests.get(), delivered.get(), receiptsByEventId.size(), duplicateCount())
                    .replace("\n", ""));

            case "/_control/mode" -> {
                if (q.containsKey("mode")) {
                    mode.set(Mode.valueOf(q.get("mode").toUpperCase()));
                }
                if (q.containsKey("delayMs")) {
                    delayMs.set(Long.parseLong(q.get("delayMs")));
                }
                if (q.containsKey("failFirst")) {
                    failFirst.set(Integer.parseInt(q.get("failFirst")));
                }
                if (q.containsKey("status")) {
                    failStatus.set(Integer.parseInt(q.get("status")));
                }
                log("모드 변경 -> " + mode.get() + " delayMs=" + delayMs.get()
                        + " failFirst=" + failFirst.get() + " status=" + failStatus.get());
                respond(exchange, 200, "{\"mode\":\"" + mode.get() + "\"}");
            }

            case "/_control/down" -> {
                long seconds = Long.parseLong(q.getOrDefault("seconds", "30"));
                downUntil.set(Instant.now().plusSeconds(seconds));
                log(seconds + "초간 다운");
                respond(exchange, 200, "{\"downUntil\":\"" + downUntil.get() + "\"}");
            }

            case "/_control/received" -> {
                var sb = new StringBuilder("{");
                var sorted = new LinkedHashMap<>(receiptsByEventId);
                boolean first = true;
                for (var e : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                    first = false;
                }
                respond(exchange, 200, sb.append('}').toString());
            }

            case "/_control/reset" -> {
                receiptsByEventId.clear();
                totalRequests.set(0);
                delivered.set(0);
                recentLog.clear();
                mode.set(Mode.OK);
                delayMs.set(0);
                failFirst.set(0);
                failStatus.set(500);
                downUntil.set(Instant.EPOCH);
                respond(exchange, 200, "{\"status\":\"reset\"}");
            }

            default -> respond(exchange, 404, "{\"status\":\"not_found\"}");
        }
    }

    /** 같은 이벤트를 두 번 이상 받은 횟수의 합. 0 이 목표다. */
    static int duplicateCount() {
        return receiptsByEventId.values().stream().mapToInt(v -> v - 1).sum();
    }

    static void log(String message) {
        String line = Instant.now() + " " + message;
        System.out.println(line);
        recentLog.add(line);
        if (recentLog.size() > 500) {
            recentLog.remove(0);
        }
    }

    static String header(HttpExchange exchange, String name) {
        var values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    static Map<String, String> query(URI uri) {
        var out = new LinkedHashMap<String, String>();
        if (uri.getQuery() == null) {
            return out;
        }
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
