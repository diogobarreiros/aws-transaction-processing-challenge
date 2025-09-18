package com.example.challenge.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Representa um evento de transação pronto para ser enviado para a fila SQS.
 */
@Data
@Builder
public class SqsTransactionEvent {
    private String transactionId;
    private String transactionType;
    private BigDecimal transactionAmount;
    private Instant transactionTimestamp;
    private String customerIdentifier;
    private Map<String, String> transactionMetadata;
    private Instant processingTimestamp;
    private String transactionCategory;
    private String sourceFileId; // ID do arquivo do Google Drive que originou a transação
}