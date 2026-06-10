package com.assessment.account.controller;

import com.assessment.account.filter.TraceFilter;
import com.assessment.account.model.TransactionRequest;
import com.assessment.account.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<Map<String, Object>> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request) {
        String traceId = MDC.get(TraceFilter.TRACE_ID_MDC_KEY);
        log.info("POST /accounts/{}/transactions eventId={} traceId={}", accountId, request.getEventId(), traceId);

        boolean applied = accountService.applyTransaction(accountId, request, traceId);

        if (applied) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("status", "applied", "eventId", request.getEventId()));
        } else {
            return ResponseEntity.ok(Map.of("status", "duplicate", "eventId", request.getEventId()));
        }
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        String traceId = MDC.get(TraceFilter.TRACE_ID_MDC_KEY);
        log.info("GET /accounts/{}/balance traceId={}", accountId, traceId);

        var balance = accountService.getBalance(accountId);
        return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "balance", balance
        ));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<Map<String, Object>> getAccountDetails(@PathVariable String accountId) {
        String traceId = MDC.get(TraceFilter.TRACE_ID_MDC_KEY);
        log.info("GET /accounts/{} traceId={}", accountId, traceId);

        Map<String, Object> details = accountService.getAccountDetails(accountId);
        return ResponseEntity.ok(details);
    }

}
