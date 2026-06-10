package com.assessment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventGatewayIntegrationTest {

    // Must be started in a static block so it's running before @DynamicPropertySource fires
    static final WireMockServer wireMockServer;

    static {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // ─── WireMock helpers ─────────────────────────────────────────────────────

    private void stubAccountServiceOk(String accountId) {
        wireMockServer.stubFor(
                WireMock.post(WireMock.urlPathMatching("/accounts/" + accountId + "/transactions"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"status\":\"applied\"}")));
    }

    private String eventJson(String eventId, String accountId, String type, double amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "accountId", accountId,
                "type", type,
                "amount", amount,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:02:11Z"
        ));
    }

    // ─── Core Functionality ──────────────────────────────────────────────────

    @Test
    @Order(1)
    void submitEvent_returnsCreated() throws Exception {
        stubAccountServiceOk("acct-001");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-001", "acct-001", "CREDIT", 100.0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-001"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(100.0));
    }

    @Test
    @Order(2)
    void submitEvent_idempotency_returnsDuplicate() throws Exception {
        stubAccountServiceOk("acct-002");

        // First submission
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-idem-001", "acct-002", "CREDIT", 50.0)))
                .andExpect(status().isCreated());

        // Duplicate → same event body, HTTP 200
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-idem-001", "acct-002", "CREDIT", 50.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-idem-001"));

        // Account Service called exactly once
        wireMockServer.verify(1,
                WireMock.postRequestedFor(WireMock.urlPathMatching("/accounts/acct-002/transactions")));
    }

    @Test
    @Order(3)
    void getEvent_byId() throws Exception {
        stubAccountServiceOk("acct-003");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-get-001", "acct-003", "DEBIT", 25.0)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events/evt-get-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-get-001"))
                .andExpect(jsonPath("$.type").value("DEBIT"));
    }

    @Test
    @Order(4)
    void getEvent_notFound_returns404() throws Exception {
        mockMvc.perform(get("/events/nonexistent-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(5)
    void getEventsByAccount_orderedByEventTimestamp() throws Exception {
        stubAccountServiceOk("acct-order");

        String laterEvent = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt-order-002",
                "accountId", "acct-order",
                "type", "CREDIT",
                "amount", 200.0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T15:00:00Z"
        ));
        String earlierEvent = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt-order-001",
                "accountId", "acct-order",
                "type", "CREDIT",
                "amount", 100.0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T13:00:00Z"
        ));

        // Submit later first (out-of-order arrival)
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(laterEvent))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(earlierEvent))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/events").param("account", "acct-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body.indexOf("evt-order-001")).isLessThan(body.indexOf("evt-order-002"));
    }

    @Test
    @Order(6)
    void submitEvent_validation_missingEventId_returns400() throws Exception {
        String invalid = objectMapper.writeValueAsString(Map.of(
                "accountId", "acct-val", "type", "CREDIT",
                "amount", 100.0, "currency", "USD", "eventTimestamp", "2026-05-15T14:00:00Z"
        ));
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details.eventId").exists());
    }

    @Test
    @Order(7)
    void submitEvent_validation_negativeAmount_returns400() throws Exception {
        String invalid = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt-neg", "accountId", "acct-val",
                "type", "CREDIT", "amount", -50.0,
                "currency", "USD", "eventTimestamp", "2026-05-15T14:00:00Z"
        ));
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.amount").exists());
    }

    @Test
    @Order(8)
    void submitEvent_validation_invalidType_returns400() throws Exception {
        String invalid = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt-badtype", "accountId", "acct-val",
                "type", "TRANSFER", "amount", 100.0,
                "currency", "USD", "eventTimestamp", "2026-05-15T14:00:00Z"
        ));
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.type").exists());
    }

    // ─── Resiliency: Circuit Breaker ─────────────────────────────────────────

    @Test
    @Order(9)
    void submitEvent_accountServiceDown_returns503() throws Exception {
        wireMockServer.stubFor(
                WireMock.post(WireMock.urlPathMatching("/accounts/acct-down/transactions"))
                        .willReturn(WireMock.aResponse().withStatus(500)));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-down-001", "acct-down", "CREDIT", 100.0)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Service Unavailable"));
    }

    @Test
    @Order(10)
    void getEventById_worksWhenAccountServiceDown() throws Exception {
        stubAccountServiceOk("acct-degraded");
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-degraded-001", "acct-degraded", "CREDIT", 100.0)))
                .andExpect(status().isCreated());

        // Service goes down — GET reads from Gateway's local H2, no Account Service call needed
        wireMockServer.resetAll();
        mockMvc.perform(get("/events/evt-degraded-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-degraded-001"));
    }

    @Test
    @Order(11)
    void getEventsByAccount_worksWhenAccountServiceDown() throws Exception {
        // Events for acct-order stored in test 5; no Account Service call needed for GET
        mockMvc.perform(get("/events").param("account", "acct-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    // ─── Distributed Tracing ─────────────────────────────────────────────────

    @Test
    @Order(12)
    void traceId_propagatedToAccountService() throws Exception {
        stubAccountServiceOk("acct-trace");

        String clientTraceId = "test-trace-id-12345";
        mockMvc.perform(post("/events")
                        .header("X-Trace-ID", clientTraceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-trace-001", "acct-trace", "CREDIT", 100.0)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-ID", clientTraceId));

        // The same trace ID must appear in the call to Account Service
        wireMockServer.verify(
                WireMock.postRequestedFor(WireMock.urlPathMatching("/accounts/acct-trace/transactions"))
                        .withHeader("X-Trace-ID", WireMock.equalTo(clientTraceId)));
    }

    @Test
    @Order(13)
    void traceId_generatedWhenNotProvided() throws Exception {
        stubAccountServiceOk("acct-trace2");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-trace-002", "acct-trace2", "CREDIT", 50.0)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-ID"));
    }

    // ─── Health ────────────────────────────────────────────────────────────────

    @Test
    @Order(14)
    void healthEndpoint_returnsUp() throws Exception {
        wireMockServer.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/health"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"status\":\"UP\"}")));

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("event-gateway"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.accountService").value("UP"));
    }

    // ─── Full Integration Flow ────────────────────────────────────────────────

    @Test
    @Order(15)
    void fullFlow_creditAndDebit_eventsStoredAndForwarded() throws Exception {
        stubAccountServiceOk("acct-full");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-full-001", "acct-full", "CREDIT", 500.0)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-full-002", "acct-full", "DEBIT", 200.0)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events").param("account", "acct-full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        wireMockServer.verify(2,
                WireMock.postRequestedFor(WireMock.urlPathMatching("/accounts/acct-full/transactions")));
    }
}
