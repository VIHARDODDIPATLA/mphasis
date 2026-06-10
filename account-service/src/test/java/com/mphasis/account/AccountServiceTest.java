package com.assessment.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountServiceTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private Map<String, Object> buildTransaction(String eventId, String type, double amount) {
        return Map.of(
                "eventId", eventId,
                "type", type,
                "amount", amount,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:00:00Z"
        );
    }

    // ─── Core Functionality ──────────────────────────────────────────────────

    @Test
    @Order(1)
    void applyTransaction_returnsCreated() throws Exception {
        mockMvc.perform(post("/accounts/acct-001/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildTransaction("tx-001", "CREDIT", 100.0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.eventId").value("tx-001"));
    }

    @Test
    @Order(2)
    void applyTransaction_idempotency_duplicateSkipped() throws Exception {
        mockMvc.perform(post("/accounts/acct-idem/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildTransaction("tx-idem-001", "CREDIT", 100.0))))
                .andExpect(status().isCreated());

        // Duplicate
        mockMvc.perform(post("/accounts/acct-idem/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildTransaction("tx-idem-001", "CREDIT", 100.0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    @Test
    @Order(3)
    void getBalance_creditMinus_debit() throws Exception {
        mockMvc.perform(post("/accounts/acct-bal/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildTransaction("tx-bal-001", "CREDIT", 500.0))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/accounts/acct-bal/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildTransaction("tx-bal-002", "DEBIT", 150.0))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-bal"))
                .andExpect(jsonPath("$.balance").value(350.0));
    }

    @Test
    @Order(4)
    void getBalance_emptyAccount_returnsZero() throws Exception {
        mockMvc.perform(get("/accounts/acct-empty/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    @Order(5)
    void getAccountDetails_includesTransactionsOrderedByTimestamp() throws Exception {
        // Submit out-of-order: later timestamp first
        Map<String, Object> laterTx = Map.of(
                "eventId", "tx-ord-002",
                "type", "DEBIT",
                "amount", 50.0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T16:00:00Z"
        );
        Map<String, Object> earlierTx = Map.of(
                "eventId", "tx-ord-001",
                "type", "CREDIT",
                "amount", 200.0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T12:00:00Z"
        );

        mockMvc.perform(post("/accounts/acct-ord/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(laterTx)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/accounts/acct-ord/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(earlierTx)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-ord"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-ord"))
                .andExpect(jsonPath("$.transactionCount").value(2))
                .andExpect(jsonPath("$.balance").value(150.0))
                .andExpect(jsonPath("$.transactions[0].eventId").value("tx-ord-001"))
                .andExpect(jsonPath("$.transactions[1].eventId").value("tx-ord-002"));
    }

    @Test
    @Order(6)
    void applyTransaction_validation_missingEventId_returns400() throws Exception {
        Map<String, Object> invalid = Map.of(
                "type", "CREDIT",
                "amount", 100.0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:00:00Z"
        );

        mockMvc.perform(post("/accounts/acct-val/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.eventId").exists());
    }

    @Test
    @Order(7)
    void applyTransaction_validation_invalidType_returns400() throws Exception {
        Map<String, Object> invalid = Map.of(
                "eventId", "tx-bad-type",
                "type", "TRANSFER",
                "amount", 100.0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:00:00Z"
        );

        mockMvc.perform(post("/accounts/acct-val/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.type").exists());
    }

    @Test
    @Order(8)
    void applyTransaction_validation_zeroAmount_returns400() throws Exception {
        Map<String, Object> invalid = Map.of(
                "eventId", "tx-zero",
                "type", "CREDIT",
                "amount", 0,
                "currency", "USD",
                "eventTimestamp", "2026-05-15T14:00:00Z"
        );

        mockMvc.perform(post("/accounts/acct-val/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.amount").exists());
    }

    // ─── Trace Propagation ────────────────────────────────────────────────────

    @Test
    @Order(9)
    void traceId_echoedInResponseHeader() throws Exception {
        String clientTraceId = "upstream-trace-abc123";

        mockMvc.perform(post("/accounts/acct-trace/transactions")
                        .header("X-Trace-ID", clientTraceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildTransaction("tx-trace-001", "CREDIT", 75.0))))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-ID", clientTraceId));
    }

    // ─── Health ────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void healthEndpoint_returnsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("account-service"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ─── Balance correctness with out-of-order events ─────────────────────────

    @Test
    @Order(11)
    void balance_correctRegardlessOfArrivalOrder() throws Exception {
        // Events arrive in reverse chronological order
        String[] eventIds = {"tx-oo-003", "tx-oo-002", "tx-oo-001"};
        String[] types    = {"DEBIT",     "CREDIT",    "CREDIT"};
        double[] amounts  = {100.0,        200.0,       300.0};
        String[] times    = {"2026-05-15T16:00:00Z", "2026-05-15T15:00:00Z", "2026-05-15T14:00:00Z"};

        for (int i = 0; i < eventIds.length; i++) {
            Map<String, Object> tx = Map.of(
                    "eventId", eventIds[i],
                    "type", types[i],
                    "amount", amounts[i],
                    "currency", "USD",
                    "eventTimestamp", times[i]
            );
            mockMvc.perform(post("/accounts/acct-oo/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(tx)))
                    .andExpect(status().isCreated());
        }

        // Balance = 300 + 200 - 100 = 400
        mockMvc.perform(get("/accounts/acct-oo/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(400.0));
    }
}
