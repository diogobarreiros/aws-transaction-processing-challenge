package com.example.challenge.google;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.example.challenge.config.GoogleDriveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoogleDriveClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveClient.class);

    private final Drive googleDriveService;
    private final String targetFolderId;

    public GoogleDriveClient(Drive googleDriveService, GoogleDriveProperties googleDriveProperties) {
        this.googleDriveService = googleDriveService;
        this.targetFolderId = googleDriveProperties.getFolderId();
        log.info("GoogleDriveClient inicializado para pasta ID: {}", targetFolderId);
    }

    public List<File> listCsvFilesInTargetFolder() throws IOException {
        String query = String.format("'%s' in parents and mimeType = 'text/csv' and trashed = false", targetFolderId);

        FileList result = googleDriveService.files().list()
                .setQ(query)
                .setFields("nextPageToken, files(id, name, mimeType, modifiedTime)")
                .execute();

        List<File> files = result.getFiles();

        if (files == null || files.isEmpty()) {
            log.info("Nenhum arquivo CSV encontrado na pasta {}", targetFolderId);
            return Collections.emptyList();
        }

        log.info("Encontrados {} arquivos CSV na pasta {}: {}", files.size(), targetFolderId,
                files.stream().map(File::getName).collect(Collectors.joining(", ")));

        return files;
    }

    public InputStream downloadFileContent(String fileId) throws IOException {
        log.info("Baixando conteúdo do arquivo com ID: {}", fileId);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        googleDriveService.files().get(fileId).executeMediaAndDownloadTo(outputStream);
        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    /**
     * Move um arquivo para a lixeira do Google Drive após o processamento.
     * Esta é uma estratégia simples para "marcar como processado".
     * Em um ambiente de produção, considere mover para uma pasta "processed" ou "archive".
     *
     * @param fileId O ID do arquivo a ser movido para a lixeira.
     * @throws IOException Se ocorrer um erro ao mover o arquivo.
     */
    public void trashFile(String fileId) throws IOException {
        log.info("Movendo arquivo com ID {} para a lixeira.", fileId);

        File fileMetadata = new File();
        fileMetadata.setTrashed(true);

        googleDriveService.files().update(fileId, fileMetadata).execute();
        log.info("Arquivo com ID {} movido para a lixeira com sucesso.", fileId);
    }

    /**
     * Este método requer permissões WRITE, se você usá-lo, o escopo deve ser ajustado para DriveScopes.DRIVE
     * Move um arquivo para uma pasta específica no Google Drive.
     *
     * @param fileId O ID do arquivo a ser movido.
     * @param newParentFolderId O ID da nova pasta pai.
     * @throws IOException Se ocorrer um erro ao mover o arquivo.
     */
    public void moveFile(String fileId, String newParentFolderId) throws IOException {
        log.info("Movendo arquivo com ID {} para a pasta {}.", fileId, newParentFolderId);
        File file = googleDriveService.files().get(fileId)
                .setFields("parents")
                .execute();
        String previousParents = String.join(",", file.getParents());

        googleDriveService.files().update(fileId, null)
                .setAddParents(newParentFolderId)
                .setRemoveParents(previousParents)
                .setFields("id, parents")
                .execute();
        log.info("Arquivo com ID {} movido para a pasta {} com sucesso.", fileId, newParentFolderId);
    }
}