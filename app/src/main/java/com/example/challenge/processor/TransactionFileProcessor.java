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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.SsmException;


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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TransactionFileProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransactionFileProcessor.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;
    private final SsmClient ssmClient;

    @Value("${app.sqs.queue-url}")
    private String sqsQueueUrl;

    @Value("${app.s3.rejected-transactions-bucket-name}")
    private String rejectedTransactionsBucketName;

    @Value("${app.ssm.processing-rules-parameter-name: /my-app/processing-rules}")
    private String processingRulesParameterName;

    private final AtomicBoolean enableBetaFeatures = new AtomicBoolean(false);

    public TransactionFileProcessor(SqsClient sqsClient, ObjectMapper objectMapper, S3Client s3Client, SsmClient ssmClient) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
        loadProcessingRules();
    }

    /**
     * Carrega as regras de processamento do AWS Parameter Store.
     * Pode ser chamado periodicamente ou na inicialização do serviço.
     * Neste exemplo, carregamos uma flag simples 'enableBetaFeatures'.
     */
    private void loadProcessingRules() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(processingRulesParameterName)
                    .withDecryption(true)
                    .build();

            String rulesJson = ssmClient.getParameter(parameterRequest).parameter().value();

            Map<String, Object> rulesMap = objectMapper.readValue(rulesJson, Map.class);
            if (rulesMap.containsKey("enableBetaFeatures")) {
                enableBetaFeatures.set((Boolean) rulesMap.get("enableBetaFeatures"));
                log.info("Regras de processamento carregadas do Parameter Store. enableBetaFeatures: {}", enableBetaFeatures.get());
            } else {
                log.warn("Parâmetro '{}' não contém 'enableBetaFeatures'. Usando valor padrão: {}", processingRulesParameterName, enableBetaFeatures.get());
            }
        } catch (SsmException e) {
            log.error("Erro ao carregar regras de processamento do Parameter Store '{}': {}", processingRulesParameterName, e.awsErrorDetails().errorMessage(), e);
            // Em um ambiente de produção, você pode querer implementar retry ou usar um valor de fallback.
        } catch (JsonProcessingException e) {
            log.error("Erro ao parsear JSON das regras de processamento do Parameter Store '{}': {}", processingRulesParameterName, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Erro inesperado ao carregar regras de processamento do Parameter Store: {}", e.getMessage(), e);
        }
    }

    /**
     * Processa um arquivo CSV de transações.
     * Lê o CSV, valida cada registro, transforma para o formato do evento SQS
     * e envia para a fila. Dados inválidos são descartados para S3.
     *
     * @param sourceFileId O ID do arquivo do Google Drive que originou este processamento.
     * @param fileName O nome do arquivo CSV.
     * @param inputStream O InputStream contendo o conteúdo do arquivo CSV.
     * @throws IOException Se ocorrer um erro durante a leitura do CSV.
     */
    public void processCsvFile(String sourceFileId, String fileName, InputStream inputStream) throws IOException {
        log.info("Iniciando o processamento do arquivo CSV '{}' (ID: {}) no app-producer.", fileName, sourceFileId);

        int processedCount = 0;
        int rejectedCount = 0;

        if (enableBetaFeatures.get()) {
            log.debug("Funcionalidades beta ativadas para este processamento.");
            // Adicione lógica específica de beta aqui, se aplicável
        }

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
                        discardRejectedTransaction(csvRecord, sourceFileId, fileName, "Validation Failed");
                        rejectedCount++;
                    }
                } catch (DateTimeParseException e) {
                    log.error("Erro de formato de data/hora para registro no arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap());
                    discardRejectedTransaction(csvRecord, sourceFileId, fileName, "Timestamp Format Error");
                    rejectedCount++;
                } catch (NumberFormatException e) {
                    log.error("Erro de formato numérico para registro no arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap());
                    discardRejectedTransaction(csvRecord, sourceFileId, fileName, "Amount Format Error");
                    rejectedCount++;
                } catch (IllegalArgumentException e) {
                    log.error("Erro nos cabeçalhos CSV ou campo ausente no arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap());
                    discardRejectedTransaction(csvRecord, sourceFileId, fileName, "Missing Header/Field");
                    rejectedCount++;
                } catch (Exception e) {
                    log.error("Erro inesperado ao processar registro CSV do arquivo '{}' (ID: {}): {}. Registro: {}",
                            fileName, sourceFileId, e.getMessage(), csvRecord.toMap(), e);
                    discardRejectedTransaction(csvRecord, sourceFileId, fileName, "Unexpected Error: " + e.getClass().getSimpleName());
                    rejectedCount++;
                }
            }

            log.info("Processamento do arquivo '{}' (ID: {}) concluído. Processadas: {}, Rejeitadas: {}",
                    fileName, sourceFileId, processedCount, rejectedCount);
        } catch (IOException e) {
            log.error("Erro de IO ao ler o arquivo CSV '{}' (ID: {}): {}", fileName, sourceFileId, e.getMessage(), e);
            throw e;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.warn("Erro ao fechar InputStream para o arquivo '{}': {}", fileName, e.getMessage());
                }
            }
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
            }
        }

        return Transaction.builder()
                .transactionId(record.get("transaction_id"))
                .transactionType(record.get("transaction_type"))
                .amount(new BigDecimal(record.get("amount"))).
                timestamp(Instant.parse(record.get("timestamp")))
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
            String messageBody = objectMapper.writeValueAsString(event); // Requisito: Eventos na fila SQS devem estar em formato JSON com camelCase
            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(sqsQueueUrl)
                    .messageBody(messageBody)
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

    /**
     * Descarrega uma transação rejeitada para um bucket S3 específico.
     *
     * @param rejectedRecord O registro CSV rejeitado.
     * @param sourceFileId O ID do arquivo original do Google Drive.
     * @param originalFileName O nome do arquivo original.
     * @param reason O motivo da rejeição.
     */
    private void discardRejectedTransaction(CSVRecord rejectedRecord, String sourceFileId, String originalFileName, String reason) {
        if (rejectedTransactionsBucketName == null || rejectedTransactionsBucketName.isBlank()) {
            log.error("Bucket para transações rejeitadas não configurado. Não é possível descartar o registro: {}", rejectedRecord.toMap());
            return;
        }

        try {
            String objectKey = String.format("rejected/%s/%s_%s.json",
                    sourceFileId,
                    originalFileName.replace(".csv", ""),
                    UUID.randomUUID());

            Map<String, String> rejectedData = new HashMap<>(rejectedRecord.toMap());
            rejectedData.put("_rejectionReason", reason);
            rejectedData.put("_sourceFileId", sourceFileId);
            rejectedData.put("_originalFileName", originalFileName);
            rejectedData.put("_rejectionTimestamp", Instant.now().toString());

            String content = objectMapper.writeValueAsString(rejectedData);

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(rejectedTransactionsBucketName)
                    .key(objectKey)
                    .contentType("application/json")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromString(content));

            log.info("Registro rejeitado salvo no S3: s3://{}/{}", rejectedTransactionsBucketName, objectKey);

        } catch (JsonProcessingException e) {
            log.error("Erro ao serializar registro rejeitado para JSON. Não foi possível salvar no S3. Registro: {}", rejectedRecord.toMap(), e);
        } catch (Exception e) {
            log.error("Erro ao salvar registro rejeitado no S3 para o bucket {}. Registro: {}", rejectedTransactionsBucketName, rejectedRecord.toMap(), e);
        }
    }
}