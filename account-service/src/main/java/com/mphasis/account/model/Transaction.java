package com.assessment.account.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_account_id", columnList = "account_id"),
        @Index(name = "idx_event_timestamp", columnList = "event_timestamp")
})
@Data
@NoArgsConstructor
public class Transaction {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "type", nullable = false, length = 10)
    private String type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "trace_id", length = 64)
    private String traceId;
}
