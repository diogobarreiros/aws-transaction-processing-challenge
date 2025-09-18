package com.example.challenge.repository;

import com.example.challenge.model.ProcessedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Repository
public class ProcessedFileRepository {

    private static final Logger log = LoggerFactory.getLogger(ProcessedFileRepository.class);

    private final DynamoDbTable<ProcessedFile> processedFileTable;

    public ProcessedFileRepository(DynamoDbEnhancedClient dynamoDbEnhancedClient,
                                   @Value("${app.dynamodb.processed-files-table-name}") String tableName) {
        this.processedFileTable = dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ProcessedFile.class));
        log.info("ProcessedFileRepository inicializado para a tabela DynamoDB: {}", tableName);
    }

    /**
     * Salva um registro de arquivo processado no DynamoDB.
     *
     * @param processedFile O objeto ProcessedFile a ser salvo.
     */
    public void save(ProcessedFile processedFile) {
        log.info("Salvando registro de arquivo processado no DynamoDB: {}", processedFile.getFileId());
        processedFileTable.putItem(processedFile);
    }

    /**
     * Verifica se um arquivo com o dado fileId já foi processado.
     *
     * @param fileId O ID do arquivo do Google Drive.
     * @return true se o arquivo já foi processado, false caso contrário.
     */
    public boolean existsById(String fileId) {
        Key key = Key.builder().partitionValue(fileId).build();
        ProcessedFile item = processedFileTable.getItem(key);
        boolean exists = item != null;
        if (exists) {
            log.info("Arquivo com ID '{}' já encontrado como processado.", fileId);
        }
        return exists;
    }
}