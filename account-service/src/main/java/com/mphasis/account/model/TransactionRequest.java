package com.assessment.account.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class TransactionRequest {

    @NotBlank(message = "eventId is required")
    private String eventId;

    @NotBlank(message = "type is required")
    @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
    private String type;

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    private String currency;

    @NotNull(message = "eventTimestamp is required")
    private Instant eventTimestamp;
}
