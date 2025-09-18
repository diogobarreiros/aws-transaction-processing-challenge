package com.example.challenge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "google.drive")
public class GoogleDriveProperties {

    @NotBlank(message = "O nome do segredo das credenciais do Google Drive não pode estar em branco.")
    private String credentialsSecretName;

    @NotBlank(message = "O ID da pasta do Google Drive não pode estar em branco.")
    private String folderId;
}