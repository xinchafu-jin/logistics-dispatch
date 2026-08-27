package com.example.backend.dispatch;

import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CpcFuelPriceClient {

    private final RestClient restClient;

    public CpcFuelPriceClient(@Value("${app.cpc.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public JsonNode getPrices() {
        return restClient.get()
                .uri("/mainprodlistprice")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
    }
}