package com.example.challenge.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Configuration
public class GoogleDriveConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveConfig.class);
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final GoogleDriveProperties googleDriveProperties;

    private final SecretsManagerClient secretsManagerClient;

    public GoogleDriveConfig(GoogleDriveProperties googleDriveProperties) {
        this.googleDriveProperties = googleDriveProperties;
        this.secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    @Bean
    public Drive googleDriveService() throws GeneralSecurityException, IOException {
        String secretJson = getGoogleDriveCredentialsFromSecretsManager();
        log.info("Credenciais do Google Drive obtidas do AWS Secrets Manager.");

        InputStream privateKeyStream = new ByteArrayInputStream(secretJson.getBytes());

        GoogleCredential credential = GoogleCredential.fromStream(privateKeyStream)
                .createScoped(Collections.singleton(DriveScopes.DRIVE));

        return new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName("AWS Transaction Processing Challenge")
                .build();
    }

    private String getGoogleDriveCredentialsFromSecretsManager() {
        String secretName = googleDriveProperties.getCredentialsSecretName();
        log.info("Tentando buscar o segredo '{}' do AWS Secrets Manager.", secretName);

        GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        GetSecretValueResponse getSecretValueResponse = secretsManagerClient.getSecretValue(getSecretValueRequest);

        if (getSecretValueResponse.secretString() != null) {
            return getSecretValueResponse.secretString();
        } else if (getSecretValueResponse.secretBinary() != null) {
            return getSecretValueResponse.secretBinary().asUtf8String();
        }
        throw new IllegalStateException("O segredo do Google Drive não foi encontrado ou está vazio no Secrets Manager.");
    }

    @PostConstruct
    public void validateProperties() {
        if (googleDriveProperties.getFolderId().equals("YOUR_GOOGLE_DRIVE_FOLDER_ID")) {
            log.warn("ATENÇÃO: A propriedade google.drive.folder-id ainda é o placeholder. Lembre-se de configurar o ID real da pasta do Google Drive.");
        }
        if (googleDriveProperties.getCredentialsSecretName().equals("google-drive-service-account-key")) {
            log.warn("ATENÇÃO: A propriedade google.drive.credentials-secret-name ainda é o placeholder. Lembre-se de configurar o nome real do segredo no AWS Secrets Manager.");
        }
    }
}