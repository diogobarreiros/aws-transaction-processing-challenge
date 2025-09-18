package com.example.challenge.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Representa uma transação como lida diretamente do arquivo CSV.
 * Os nomes dos campos correspondem aos cabeçalhos do CSV (snake_case).
 */
@Data
@Builder
public class Transaction {
    private String transactionId;
    private String transactionType;
    private BigDecimal amount;
    private Instant timestamp;
    private String customerId;
    private Map<String, String> metadata;
}