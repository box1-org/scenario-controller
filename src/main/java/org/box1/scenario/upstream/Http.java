package org.box1.scenario.upstream;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.ScenarioSettings;

/**
 * The only outbound HTTP path. Base URLs come from configuration; path segments must match a fixed ID pattern; every
 * call carries the run's correlation ID and W3C traceparent, and has a timeout.
 */
@ApplicationScoped
public class Http {
    /** Characters allowed in a path segment: contract IDs and SKUs without separators. */
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    static final ObjectMapper JSON = new ObjectMapper();
    /** Upstream bodies larger than this are refused, not buffered. */
    static final int MAX_BODY = 1 << 20;

    private final HttpClient client;
    private final Duration timeout;

    public Http(ScenarioSettings settings) {
        this.timeout = settings.upstreamTimeout();
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** A response: status and parsed JSON body (null when empty or not JSON). */
    public record Reply(int status, JsonNode body) {}

    static String segment(String value) {
        if (value == null || !SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Path segment is not valid");
        }
        return value;
    }

    Reply send(String method, String base, String path, Object body, Map<String, String> headers, String correlationId) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Accept", "application/json");
            if (correlationId != null) {
                request.header("X-Correlation-Id", correlationId);
            }
            String traceparent = traceparent();
            if (traceparent != null) {
                request.header("traceparent", traceparent);
            }
            headers.forEach(request::header);
            if (body instanceof String form) {
                request.method(method, HttpRequest.BodyPublishers.ofString(form));
            } else if (body != null) {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = client.send(request.build(), Http::bounded);
            return new Reply(response.statusCode(), parse(response.body()));
        } catch (IOException error) {
            throw new UpstreamException("Call to " + host(base) + " failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Call to " + host(base) + " was interrupted", error);
        }
    }

    /** Raw text, for the Prometheus metrics endpoint only. */
    String text(String base, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).GET().build();
            HttpResponse<String> response = client.send(request, Http::bounded);
            if (response.statusCode() != 200) {
                throw new UpstreamException(host(base) + " metrics answered " + response.statusCode(), response.statusCode());
            }
            return response.body();
        } catch (IOException error) {
            throw new UpstreamException("Call to " + host(base) + " failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Call to " + host(base) + " was interrupted", error);
        }
    }

    /** A body handler that refuses anything larger than MAX_BODY. */
    private static HttpResponse.BodySubscriber<String> bounded(HttpResponse.ResponseInfo info) {
        long length = info.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (length > MAX_BODY) {
            return HttpResponse.BodySubscribers.replacing("");
        }
        return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray(), bytes ->
                bytes.length > MAX_BODY ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (IOException error) {
            return null;
        }
    }

    static String host(String base) {
        return URI.create(base).getHost();
    }

    private static String traceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
    }
}
