package com.example.challenge.processor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.challenge.model.Transaction;
import com.example.challenge.model.SqsTransactionEvent;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class TransactionFileProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransactionFileProcessor.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${app.sqs.queue-url}")
    private String sqsQueueUrl;

    public TransactionFileProcessor(SqsClient sqsClient, ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Processa um arquivo CSV de transações.
     * Lê o CSV, valida cada registro, transforma para o formato do evento SQS
     * e envia para a fila.
     *
     * @param sourceFileId O ID do arquivo do Google Drive que originou este processamento.
     * @param fileName O nome do arquivo CSV.
     * @param inputStream O InputStream contendo o conteúdo do arquivo CSV.
     * @throws IOException Se ocorrer um erro durante a leitura do CSV.
     */
    public void processCsvFile(String sourceFileId, String fileName, InputStream inputStream) throws IOException {
        log.info("Iniciando o processamento do arquivo CSV '{}' (ID: {})", fileName, sourceFileId);

        int processedCount = 0;
        int rejectedCount = 0;

        try (Reader reader = new InputStreamReader(inputStream)) {
            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .build());

            for (CSVRecord csvRecord : csvParser) {
                try {
                    Transaction transaction = parseCsvRecord(csvRecord);

                    if (isValidTransaction(transaction)) {
                        SqsTransactionEvent event = transformToSqsEvent(transaction, sourceFileId);

                        sendToSqs(event);
                        processedCount++;
                    } else {
                        log.warn("Transação inválida e rejeitada do arquivo {}. Registro: {}", fileName, csvRecord.toMap());
                        // TODO: Implementar o requisito 4: Descarte de dados rejeitados para bucket S3 apropriado.
                        // Isso envolveria serializar o registro rejeitado e enviá-lo para um S3 "dead-letter" bucket.
                        rejectedCount++;
                    }
                } catch (DateTimeParseException e) {
                    log.error("Erro de formato de data/hora para registro no arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap());
                    rejectedCount++;
                } catch (NumberFormatException e) {
                    log.error("Erro de formato numérico para registro no arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap());
                    rejectedCount++;
                } catch (IllegalArgumentException e) {
                    log.error("Erro nos cabeçalhos CSV ou campo ausente no arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap());
                    rejectedCount++;
                } catch (Exception e) {
                    log.error("Erro inesperado ao processar registro CSV do arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap(), e);
                    rejectedCount++;
                }
            }
            log.info("Processamento do arquivo '{}' (ID: {}) concluído. Processadas: {}, Rejeitadas: {}",
                    fileName, sourceFileId, processedCount, rejectedCount);

            // TODO: Implementar o requisito 4: Consolidação - Gerar relatório com totais por tipo de transação,
            // valor médio e quantidade de transações. Isso provavelmente seria feito após os eventos SQS
            // serem consumidos, ou acumulando métricas durante este processamento.

        } catch (IOException e) {
            log.error("Erro de IO ao ler o arquivo CSV '{}' (ID: {}): {}", fileName, sourceFileId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Faz o parsing de um CSVRecord para um objeto Transaction.
     * Mapeia os cabeçalhos do CSV (snake_case) para os campos do modelo Java.
     *
     * @param record O CSVRecord a ser parseado.
     * @return Um objeto Transaction preenchido.
     */
    private Transaction parseCsvRecord(CSVRecord record) {
        String metadataJson = record.get("metadata");
        Map<String, String> metadataMap = Collections.emptyMap();
        if (metadataJson != null && !metadataJson.trim().isEmpty()) {
            try {
                metadataMap = objectMapper.readValue(metadataJson, HashMap.class);
            } catch (JsonProcessingException e) {
                log.warn("Falha ao parsear metadata JSON para o registro: {}. Metadata raw: {}", record.toMap(), metadataJson, e);
                // Pode-se optar por lançar uma exceção ou retornar um mapa vazio, dependendo da criticidade.
            }
        }

        return Transaction.builder()
                .transactionId(record.get("transaction_id"))
                .transactionType(record.get("transaction_type"))
                .amount(new BigDecimal(record.get("amount")))
                .timestamp(Instant.parse(record.get("timestamp"))) // Assume formato ISO 8601 (ex: 2024-01-15T10:30:00Z)
                .customerId(record.get("customer_id"))
                .metadata(metadataMap)
                .build();
    }

    /**
     * Valida uma transação de acordo com os requisitos funcionais:
     * - Ignorar transações com valores negativos.
     * - Ignorar transações com campos obrigatórios faltando.
     *
     * @param transaction A transação a ser validada.
     * @return true se a transação for válida, false caso contrário.
     */
    private boolean isValidTransaction(Transaction transaction) {
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isBlank() ||
                transaction.getTransactionType() == null || transaction.getTransactionType().isBlank() ||
                transaction.getAmount() == null ||
                transaction.getTimestamp() == null ||
                transaction.getCustomerId() == null || transaction.getCustomerId().isBlank()) {
            log.warn("Validação falhou: Campos obrigatórios faltando para a transação: {}", transaction);
            return false;
        }

        if (transaction.getAmount().compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Validação falhou: Valor da transação negativo: {}", transaction.getAmount());
            return false;
        }
        return true;
    }

    /**
     * Transforma um objeto Transaction no formato SqsTransactionEvent (camelCase) e o enriquece.
     *
     * @param transaction A transação original.
     * @param sourceFileId O ID do arquivo de origem.
     * @return Um objeto SqsTransactionEvent enriquecido.
     */
    private SqsTransactionEvent transformToSqsEvent(Transaction transaction, String sourceFileId) {
        String transactionCategory;
        transactionCategory = (transaction.getAmount().compareTo(BigDecimal.ZERO) >= 0) ? "CREDIT" : "DEBIT";

        // Requisito: Adicionar timestamp de processamento
        return SqsTransactionEvent.builder()
                .transactionId(transaction.getTransactionId())
                .transactionType(transaction.getTransactionType())
                .transactionAmount(transaction.getAmount())
                .transactionTimestamp(transaction.getTimestamp())
                .customerIdentifier(transaction.getCustomerId())
                .transactionMetadata(transaction.getMetadata())
                .processingTimestamp(Instant.now())
                .transactionCategory(transactionCategory)
                .sourceFileId(sourceFileId)
                .build();
    }

    /**
     * Envia o evento de transação para a fila SQS.
     * O evento é serializado para JSON em camelCase.
     *
     * @param event O evento SqsTransactionEvent a ser enviado.
     */
    private void sendToSqs(SqsTransactionEvent event) {
        try {
            String messageBody = objectMapper.writeValueAsString(event); // Serializa para JSON

            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(sqsQueueUrl)
                    .messageBody(messageBody)
                    // TODO: Para garantir idempotência e "exactly-once delivery" em filas SQS FIFO,
                    // seria necessário usar messageDeduplicationId e messageGroupId.
                    // Para filas Standard, o SQS garante "at-least-once delivery".
                    // Para este desafio, SQS Standard é provavelmente o esperado.
                    .build();
            sqsClient.sendMessage(sendMessageRequest);
            log.debug("Evento SQS para a transação {} enviado com sucesso para a fila.", event.getTransactionId());
        } catch (JsonProcessingException e) {
            log.error("Erro ao serializar evento de transação para JSON: {}", event.getTransactionId(), e);
        } catch (SqsException e) {
            log.error("Erro ao enviar mensagem para a fila SQS para a transação {}: {}", event.getTransactionId(), e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            log.error("Erro inesperado ao enviar mensagem para a fila SQS para a transação {}: {}", event.getTransactionId(), e.getMessage(), e);
        }
    }
}