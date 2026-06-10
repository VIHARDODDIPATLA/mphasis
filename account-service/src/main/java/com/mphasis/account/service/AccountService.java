package com.assessment.account.service;

import com.assessment.account.model.Transaction;
import com.assessment.account.model.TransactionRequest;
import com.assessment.account.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AccountService {

    private final TransactionRepository transactionRepository;
    private final Counter transactionsAppliedCounter;
    private final Counter transactionsDuplicateCounter;

    public AccountService(TransactionRepository transactionRepository, MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.transactionsAppliedCounter = Counter.builder("account.transactions.applied")
                .description("Total transactions applied")
                .register(meterRegistry);
        this.transactionsDuplicateCounter = Counter.builder("account.transactions.duplicate")
                .description("Duplicate transactions skipped")
                .register(meterRegistry);
    }

    @Transactional
    public boolean applyTransaction(String accountId, TransactionRequest request, String traceId) {
        if (transactionRepository.existsById(request.getEventId())) {
            log.info("Duplicate transaction skipped eventId={} accountId={} traceId={}",
                    request.getEventId(), accountId, traceId);
            transactionsDuplicateCounter.increment();
            return false;
        }

        Transaction transaction = new Transaction();
        transaction.setEventId(request.getEventId());
        transaction.setAccountId(accountId);
        transaction.setType(request.getType());
        transaction.setAmount(request.getAmount());
        transaction.setCurrency(request.getCurrency());
        transaction.setEventTimestamp(request.getEventTimestamp());
        transaction.setProcessedAt(Instant.now());
        transaction.setTraceId(traceId);

        transactionRepository.save(transaction);
        log.info("Transaction applied eventId={} accountId={} type={} amount={} traceId={}",
                request.getEventId(), accountId, request.getType(), request.getAmount(), traceId);

        transactionsAppliedCounter.increment();
        return true;
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountId) {
        BigDecimal balance = transactionRepository.calculateBalance(accountId);
        return balance != null ? balance : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAccountDetails(String accountId) {
        List<Transaction> transactions = transactionRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
        BigDecimal rawBalance = transactionRepository.calculateBalance(accountId);
        BigDecimal balance = rawBalance != null ? rawBalance : BigDecimal.ZERO;

        Map<String, Object> details = new HashMap<>();
        details.put("accountId", accountId);
        details.put("balance", balance);
        details.put("transactionCount", transactions.size());
        details.put("transactions", transactions.stream().map(t -> {
            Map<String, Object> tx = new HashMap<>();
            tx.put("eventId", t.getEventId());
            tx.put("type", t.getType());
            tx.put("amount", t.getAmount());
            tx.put("currency", t.getCurrency());
            tx.put("eventTimestamp", t.getEventTimestamp().toString());
            tx.put("processedAt", t.getProcessedAt().toString());
            return tx;
        }).toList());

        return details;
    }
}
