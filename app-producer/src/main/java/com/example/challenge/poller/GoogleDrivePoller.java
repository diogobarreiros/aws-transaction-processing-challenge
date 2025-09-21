package com.example.challenge.poller;

import com.example.challenge.google.GoogleDriveClient;
import com.example.challenge.model.ProcessedFile;
import com.example.challenge.processor.TransactionFileProcessor;
import com.example.challenge.repository.ProcessedFileRepository;
import com.google.api.services.drive.model.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

@Component
public class GoogleDrivePoller {

    private static final Logger log = LoggerFactory.getLogger(GoogleDrivePoller.class);

    private final GoogleDriveClient googleDriveClient;
    private final TransactionFileProcessor transactionFileProcessor;
    private final ProcessedFileRepository processedFileRepository;

    public GoogleDrivePoller(GoogleDriveClient googleDriveClient,
                             TransactionFileProcessor transactionFileProcessor,
                             ProcessedFileRepository processedFileRepository) {
        this.googleDriveClient = googleDriveClient;
        this.transactionFileProcessor = transactionFileProcessor;
        this.processedFileRepository = processedFileRepository;
    }

    @Scheduled(cron = "${app.poller.google-drive.cron:0 */5 * * * *}")
    public void pollGoogleDriveForCsvFiles() {
        log.info("Iniciando verificação agendada da pasta do Google Drive por arquivos CSV...");
        try {
            List<File> csvFiles = googleDriveClient.listCsvFilesInTargetFolder();

            if (csvFiles.isEmpty()) {
                log.info("Nenhum novo arquivo CSV encontrado para processar.");
                return;
            }

            for (File file : csvFiles) {
                if (processedFileRepository.existsById(file.getId())) {
                    log.info("Arquivo {} (ID: {}) já foi processado. Pulando.", file.getName(), file.getId());
                    continue;
                }

                try {
                    log.info("Processando arquivo CSV: {} (ID: {})", file.getName(), file.getId());
                    InputStream fileContent = googleDriveClient.downloadFileContent(file.getId());

                    transactionFileProcessor.processCsvFile(file.getId(), file.getName(), fileContent);

                    ProcessedFile processedFile = ProcessedFile.builder()
                            .fileId(file.getId())
                            .fileName(file.getName())
                            .processedTimestamp(Instant.now())
                            .status("SUCCESS")
                            .build();
                    processedFileRepository.save(processedFile);
                    log.info("Arquivo {} (ID: {}) registrado como processado com sucesso.", file.getName(), file.getId());

                    googleDriveClient.trashFile(file.getId());
                    log.info("Arquivo {} (ID: {}) processado e movido para a lixeira.", file.getName(), file.getId());

                } catch (IOException e) {
                    log.error("Erro de IO ao processar o arquivo {} (ID: {}): {}. Não será marcado como processado.", file.getName(), file.getId(), e.getMessage());
                } catch (Exception e) {
                    log.error("Erro inesperado ao processar o arquivo {} (ID: {}): {}. Não será marcado como processado.", file.getName(), file.getId(), e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.error("Erro de IO ao listar arquivos do Google Drive: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Erro inesperado durante o polling do Google Drive: {}", e.getMessage(), e);
        }
        log.info("Verificação agendada do Google Drive concluída.");
    }
}