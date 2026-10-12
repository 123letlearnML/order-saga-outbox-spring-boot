package com.duong.saga.messaging;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Gửi 1 message sang service khác bằng HTTP POST /internal/messages.
 * Phân loại kết quả để bên gọi biết nên retry hay bỏ: 2xx = xong, 4xx = message hỏng (không retry),
 * còn lại (5xx, timeout, không kết nối được) = lỗi tạm thời (retry).
 */
@Component
public class MessageSender {

    public enum Outcome { DELIVERED, RETRYABLE, NON_RETRYABLE }

    public record Result(Outcome outcome, String error) {

        static Result delivered() {
            return new Result(Outcome.DELIVERED, null);
        }
    }

    private final SagaProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(500))
            .build();

    public MessageSender(SagaProperties properties) {
        this.properties = properties;
    }

    public Result send(String destination, String body) {
        Result result = sendOnce(destination, body);
        // Chaos: mô phỏng mạng/broker giao trùng (at-least-once). Bên nhận PHẢI chịu được.
        if (result.outcome() == Outcome.DELIVERED
                && ThreadLocalRandom.current().nextDouble() < properties.chaos().duplicateRate()) {
            sendOnce(destination, body);
        }
        return result;
    }

    private Result sendOnce(String destination, String body) {
        String baseUrl = properties.peers() == null ? null : properties.peers().get(destination);
        if (baseUrl == null) {
            return new Result(Outcome.NON_RETRYABLE, "Unknown destination '" + destination + "'");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/messages"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Result.delivered();
            }
            Outcome outcome = status >= 400 && status < 500 ? Outcome.NON_RETRYABLE : Outcome.RETRYABLE;
            return new Result(outcome, "HTTP " + status + " " + truncate(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRYABLE, "interrupted");
        } catch (Exception e) {
            return new Result(Outcome.RETRYABLE, e.getClass().getSimpleName() + ": " + truncate(e.getMessage()));
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
