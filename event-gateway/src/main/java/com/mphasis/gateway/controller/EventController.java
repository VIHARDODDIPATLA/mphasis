package com.assessment.gateway.controller;

import com.assessment.gateway.client.AccountServiceClient;
import com.assessment.gateway.filter.TraceFilter;
import com.assessment.gateway.model.EventRequest;
import com.assessment.gateway.model.EventResponse;
import com.assessment.gateway.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;

    @PostMapping("/events")
    public ResponseEntity<?> submitEvent(@Valid @RequestBody EventRequest request) {
        log.info("POST /events eventId={} accountId={} type={} traceId={}",
                request.getEventId(), request.getAccountId(), request.getType(),
                MDC.get(TraceFilter.TRACE_ID_MDC_KEY));

        EventService.EventSubmitResult result = eventService.submitEvent(request);

        if (result.isDuplicate()) {
            return ResponseEntity.ok(result.event());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.event());
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String id) {
        log.info("GET /events/{} traceId={}", id, MDC.get(TraceFilter.TRACE_ID_MDC_KEY));
        return eventService.getEvent(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam(name = "account") String accountId) {
        log.info("GET /events?account={} traceId={}", accountId, MDC.get(TraceFilter.TRACE_ID_MDC_KEY));
        List<EventResponse> events = eventService.getEventsByAccount(accountId);
        return ResponseEntity.ok(events);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        String traceId = MDC.get(TraceFilter.TRACE_ID_MDC_KEY);
        boolean accountServiceUp = accountServiceClient.isHealthy(traceId);

        Map<String, Object> status = Map.of(
                "service", "event-gateway",
                "status", "UP",
                "accountService", accountServiceUp ? "UP" : "DOWN",
                "database", "UP"
        );
        return ResponseEntity.ok(status);
    }
}
