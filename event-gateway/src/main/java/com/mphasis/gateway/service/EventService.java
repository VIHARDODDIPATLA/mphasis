package com.assessment.gateway.service;

import com.assessment.gateway.client.AccountServiceClient;
import com.assessment.gateway.filter.TraceFilter;
import com.assessment.gateway.model.Event;
import com.assessment.gateway.model.EventRequest;
import com.assessment.gateway.model.EventResponse;
import com.assessment.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final Counter eventsSubmittedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsRejectedCounter;

    public EventService(EventRepository eventRepository,
                        AccountServiceClient accountServiceClient,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
        this.eventsSubmittedCounter = Counter.builder("gateway.events.submitted")
                .description("Total events submitted")
                .register(meterRegistry);
        this.eventsDuplicateCounter = Counter.builder("gateway.events.duplicate")
                .description("Duplicate event submissions")
                .register(meterRegistry);
        this.eventsRejectedCounter = Counter.builder("gateway.events.rejected")
                .description("Rejected events")
                .register(meterRegistry);
    }

    @Transactional
    public EventSubmitResult submitEvent(EventRequest request) {
        String traceId = MDC.get(TraceFilter.TRACE_ID_MDC_KEY);

        Optional<Event> existing = eventRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            log.info("Duplicate event detected eventId={} traceId={}", request.getEventId(), traceId);
            eventsDuplicateCounter.increment();
            return new EventSubmitResult(toResponse(existing.get()), true);
        }

        Event event = new Event();
        event.setEventId(request.getEventId());
        event.setAccountId(request.getAccountId());
        event.setType(request.getType());
        event.setAmount(request.getAmount());
        event.setCurrency(request.getCurrency());
        event.setEventTimestamp(request.getEventTimestamp());
        event.setReceivedAt(Instant.now());
        event.setTraceId(traceId);

        if (request.getMetadata() != null) {
            try {
                event.setMetadataJson(objectMapper.writeValueAsString(request.getMetadata()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize metadata for eventId={}", request.getEventId());
            }
        }

        eventRepository.save(event);
        log.info("Event saved eventId={} accountId={} type={} traceId={}",
                request.getEventId(), request.getAccountId(), request.getType(), traceId);

        accountServiceClient.applyTransaction(
                request.getAccountId(),
                request.getEventId(),
                request.getType(),
                request.getAmount(),
                request.getCurrency(),
                request.getEventTimestamp(),
                traceId
        );

        eventsSubmittedCounter.increment();
        log.info("Event processed successfully eventId={} traceId={}", request.getEventId(), traceId);
        return new EventSubmitResult(toResponse(event), false);
    }

    public Optional<EventResponse> getEvent(String eventId) {
        return eventRepository.findById(eventId).map(this::toResponse);
    }

    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private EventResponse toResponse(Event event) {
        EventResponse response = new EventResponse();
        response.setEventId(event.getEventId());
        response.setAccountId(event.getAccountId());
        response.setType(event.getType());
        response.setAmount(event.getAmount());
        response.setCurrency(event.getCurrency());
        response.setEventTimestamp(event.getEventTimestamp());
        response.setReceivedAt(event.getReceivedAt());

        if (event.getMetadataJson() != null) {
            try {
                response.setMetadata(objectMapper.readValue(event.getMetadataJson(), Map.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize metadata for eventId={}", event.getEventId());
            }
        }
        return response;
    }

    public record EventSubmitResult(EventResponse event, boolean isDuplicate) {}
}
