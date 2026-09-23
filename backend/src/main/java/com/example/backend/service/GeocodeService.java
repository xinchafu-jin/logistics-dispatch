package com.example.backend.service;

import com.example.backend.dto.respones.GeocodeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class GeocodeService {

    private final RestClient restClient;
    private final String userAgent;

    public GeocodeService(
            @Value("${app.geocode.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
            @Value("${app.geocode.user-agent:logistics-dispatch/1.0}") String userAgent
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.userAgent = userAgent;
    }

    public GeocodeResponse search(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("地址不能為空");
        }
        String normalized = query.trim();
        JsonNode root = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/search")
                        .queryParam("format", "jsonv2")
                        .queryParam("limit", 5)
                        .queryParam("countrycodes", "tw")
                        .queryParam("q", normalized)
                        .build())
                .header("User-Agent", userAgent)
                .retrieve()
                .body(JsonNode.class);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("地址轉座標服務回應格式錯誤");
        }
        List<GeocodeResponse.Result> results = new ArrayList<>();
        for (JsonNode item : root) {
            try {
                results.add(new GeocodeResponse.Result(
                        item.path("display_name").asString(""),
                        Double.valueOf(item.path("lat").asString()),
                        Double.valueOf(item.path("lon").asString()),
                        item.path("type").asString(null)));
            } catch (NumberFormatException ignored) {
                // 忽略外部服務中缺少有效座標的單筆資料，不以假座標補值。
            }
        }
        GeocodeResponse response = new GeocodeResponse();
        response.setQuery(normalized);
        response.setResults(results);
        return response;
    }
}
