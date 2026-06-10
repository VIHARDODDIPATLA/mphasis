package com.assessment.gateway.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountServiceClient {

    private final RestTemplate restTemplate;

    @Value("${account-service.base-url}")
    private String accountServiceBaseUrl;

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    public void applyTransaction(String accountId, String eventId, String type,
                                  BigDecimal amount, String currency, Instant eventTimestamp,
                                  String traceId) {
        String url = accountServiceBaseUrl + "/accounts/" + accountId + "/transactions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-ID", traceId);

        Map<String, Object> body = new HashMap<>();
        body.put("eventId", eventId);
        body.put("type", type);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("eventTimestamp", eventTimestamp.toString());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        log.info("Calling Account Service applyTransaction accountId={} eventId={} traceId={}", accountId, eventId, traceId);
        restTemplate.postForEntity(url, request, Void.class);
    }

    public void applyTransactionFallback(String accountId, String eventId, String type,
                                          BigDecimal amount, String currency, Instant eventTimestamp,
                                          String traceId, Exception ex) {
        log.error("Circuit breaker triggered for applyTransaction accountId={} eventId={} traceId={} error={}",
                accountId, eventId, traceId, ex.getMessage());
        throw new AccountServiceUnavailableException("Account Service is unavailable: " + ex.getMessage(), ex);
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    public BigDecimal getBalance(String accountId, String traceId) {
        String url = accountServiceBaseUrl + "/accounts/" + accountId + "/balance";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-ID", traceId);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        log.info("Calling Account Service getBalance accountId={} traceId={}", accountId, traceId);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
        Object balance = response.getBody().get("balance");
        return new BigDecimal(balance.toString());
    }

    public BigDecimal getBalanceFallback(String accountId, String traceId, Exception ex) {
        log.error("Circuit breaker triggered for getBalance accountId={} traceId={} error={}",
                accountId, traceId, ex.getMessage());
        throw new AccountServiceUnavailableException("Account Service is unavailable: " + ex.getMessage(), ex);
    }

    public boolean isHealthy(String traceId) {
        try {
            String url = accountServiceBaseUrl + "/health";
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Trace-ID", traceId);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Account Service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
