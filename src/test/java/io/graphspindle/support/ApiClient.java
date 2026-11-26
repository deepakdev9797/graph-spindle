package io.graphspindle.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal JSON HTTP client for ITs: never throws on error statuses. */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;

    public ApiClient(String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).defaultStatusHandler(s -> true, (req, res) -> { })
                .build();
    }

    public Response get(String path) {
        return exchange(HttpMethod.GET, path, null, null);
    }

    public Response post(String path, String json) {
        return exchange(HttpMethod.POST, path, json, null);
    }

    public Response patch(String path, String json, String ifMatch) {
        return exchange(HttpMethod.PATCH, path, json, ifMatch);
    }

    public Response put(String path, String json, String ifMatch) {
        return exchange(HttpMethod.PUT, path, json, ifMatch);
    }

    public Response delete(String path) {
        return exchange(HttpMethod.DELETE, path, null, null);
    }

    public Response exchange(HttpMethod method, String path, String json, String ifMatch) {
        var spec = client.method(method).uri(path);
        if (ifMatch != null) {
            spec = spec.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        if (json != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        var entity = spec.retrieve().toEntity(String.class);
        String body = entity.getBody();
        JsonNode node = body == null || body.isBlank() ? null : JSON.readTree(body);
        return new Response(entity.getStatusCode().value(), entity.getHeaders(), node);
    }

    public record Response(int status, HttpHeaders headers, JsonNode body) {

        public String code() {
            return body == null || body.get("code") == null ? null : body.get("code").asString();
        }

        public String etag() {
            return headers.getETag();
        }
    }
}
