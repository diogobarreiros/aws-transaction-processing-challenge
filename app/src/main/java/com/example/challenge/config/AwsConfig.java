package com.example.challenge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class AwsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsConfig.class);

    private static final Region AWS_REGION = Region.US_EAST_1;

    @Value("${app.aws.localstack.enabled:false}")
    private boolean localstackEnabled;

    @Value("${app.aws.localstack.endpoint:http://localhost:4566}")
    private String localstackEndpoint;

    @Value("${app.dynamodb.processed-files-table-name}")
    private String processedFilesTableName;

    @Value("${google.drive.secret-name}")
    private String googleDriveSecretName;


    /**
     * Método genérico para configurar o builder do cliente AWS com endpoint do LocalStack
     */
    private <T extends AwsClientBuilder<?, ?>> T configureClientBuilder(T builder) {
        if (localstackEnabled) {
            log.info("Configurando cliente AWS para LocalStack em: {}", localstackEndpoint);
            return (T) builder.endpointOverride(URI.create(localstackEndpoint))
                    .region(AWS_REGION);
        } else {
            log.info("Configurando cliente AWS para AWS Cloud na região: {}", AWS_REGION);
            return (T) builder.region(AWS_REGION);
        }
    }

    @Bean
    public SqsClient sqsClient() {
        return configureClientBuilder(SqsClient.builder()).build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClient client = configureClientBuilder(DynamoDbClient.builder()).build();
        if (localstackEnabled) {
            ensureDynamoDbTableExists(client, processedFilesTableName);
        }
        return client;
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    public S3Client s3Client() {
        return configureClientBuilder(S3Client.builder()).build();
    }

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        SecretsManagerClient client = configureClientBuilder(SecretsManagerClient.builder()).build();
        if (localstackEnabled) {
            ensureGoogleDriveSecretExists(client, googleDriveSecretName);
        }
        return client;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private void ensureDynamoDbTableExists(DynamoDbClient dynamoDbClient, String tableName) {
        try {
            log.info("Verificando se a tabela DynamoDB '{}' existe no LocalStack.", tableName);
            ListTablesResponse response = dynamoDbClient.listTables(ListTablesRequest.builder().build());
            if (!response.tableNames().contains(tableName)) {
                log.warn("Tabela DynamoDB '{}' não encontrada no LocalStack. Criando...", tableName);
                CreateTableRequest request = CreateTableRequest.builder()
                        .tableName(tableName)
                        .keySchema(KeySchemaElement.builder().attributeName("fileId").keyType(KeyType.HASH).build())
                        .attributeDefinitions(AttributeDefinition.builder().attributeName("fileId").attributeType(ScalarAttributeType.S).build())
                        .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(1L).writeCapacityUnits(1L).build())
                        .build();
                dynamoDbClient.createTable(request);
                log.info("Tabela DynamoDB '{}' criada com sucesso no LocalStack.", tableName);
            } else {
                log.info("Tabela DynamoDB '{}' já existe no LocalStack.", tableName);
            }
        } catch (ResourceInUseException e) {
            log.info("Tabela DynamoDB '{}' já está sendo criada ou já existe no LocalStack. Ignorando.", tableName);
        } catch (DynamoDbException e) {
            log.error("Erro ao verificar/criar tabela DynamoDB '{}' no LocalStack: {}", tableName, e.getMessage(), e);
        }
    }

    /**
     * Método auxiliar para criar o Secret do Google Drive no LocalStack, se não existir
     */
    private void ensureGoogleDriveSecretExists(SecretsManagerClient secretsManagerClient, String secretName) {
        String effectiveSecretName = "transaction-processor/" + secretName;
        try {
            log.info("Verificando se o Secret '{}' existe no LocalStack.", effectiveSecretName);

            DescribeSecretResponse response = secretsManagerClient.describeSecret(DescribeSecretRequest.builder().secretId(effectiveSecretName).build());
            log.info("Secret '{}' já existe no LocalStack.", effectiveSecretName);

        } catch (SecretsManagerException e) {
            if (e.statusCode() == 400 && e.awsErrorDetails().errorCode().equals("ResourceNotFoundException")) {
                log.warn("Secret '{}' não encontrado no LocalStack. Tentando criar um placeholder.", effectiveSecretName);
                String placeholderSecret = "{\n" +
                        "  \"type\": \"service_account\",\n" +
                        "  \"project_id\": \"project-aws-itau\",\n" +
                        "  \"private_key_id\": \"f4fbcf151ef35016dd315e31a480d2875f27893e\",\n" +
                        "  \"private_key\": \"-----BEGIN PRIVATE KEY-----\nMIIEuwIBADANBgkqhkiG9w0BAQEFAASCBKUwggShAgEAAoIBAQCTaQHdjuJ3e3Ip\np9c5RmHpyN/KJLVnJiIH0PFwpUf0qGVD09DcKJxmR+6HAwws2LKcTespF6F+q4hH\n6ZndwF/wF19DBA/t+OI7IjlmdLOji7K6o7H0JhR0TiGqebYdMK/dHJZj9SK256Ji\nxg5aiGqhluCyM/xlwBAYU3EdiG4qnf+HsVmgJJDTebGvPWRc6AIOz8NLW1BDnFnQ\nQ7VLJJ06exJ+M0te7hIBC2eq7/T2QWDpbjXYS8F3Xk+kcPNoKzoqIzk2kznzPjxL\np6z/vQoamAaI53In+LeHCsYOdsnncVN+hCRRo/ThV841ze6Z1hygLYh7iWJmybP6\nSFQBjH3bAgMBAAECgf9oONYYz+1OjPkqeoCw/hI8OMoBIfMf+tYUsKYD/tfhIy51\nxkzlW9E4f6Ml0gHJs2yVFoIti4ejB6RTM9aV8m3iUqQHvdeFaXKKXKg4yEo6V4sG\narZm6IWNtbAX1/XqrYGp04yBH9BrgVpOe7mqSMkbdpFnbVoGlsTKbjSLrOo3Nms9\n5gSNKJLd1axkSP7JmE4vO1LafH3DYAjKn2JyXd9WBCEwlE8ZKdRa2ViPswGMQD/q\nTbmSJTo7fYDCWXieIgGsQUK0bEUtv7bYDD+FIqhsdmnMCi2BI2GAeGbHPwdrjEYE\nYqE7EOGTiB/RtH5HRUc3iutgwANjNXulw2SFdSkCgYEAzo3kc8RayTMRRIzgMRg1\nIbUHfazEpTRPepbuUKRes8xyZIa5LtP45GP8GkJaS2p5JYUR4ifNa2QxhEG4gkfP\n4ul3oMpqbFz/dGEEkVMeDk1L1GXWoVl7zGGKDmYtgXpzQ1ywEaAjnIq1JMdLpWZ9\nB5JwJA032kTUlJNfs0k3c60CgYEAtrKiGY2/s8pC457jBH3aWOI3Sb35L6g9ma82\nBzSPtu7mE4J/EET8WiPhDS6IIWrjZCxq+sMQTQ3qsZMEfebtjUX1PIu5GoapAns9\nJMWJhDRgOkGKfJbhgCn01QHjuKHsHA/sndgemH2nVBLRDGgDbuIl3MO11yR413jy\neLchKKcCgYB9lvjZCwLIYyyUqbOHJljn7dJoL6xBZtUMjMjKb6bFXAIR7XibaHdl\nJwYaUU3lnQ+cKJT/FYRpT7pr2Cn/zPox1hLOyUR8Lu+EK5lDY25a89SviFxALtB/\nLK9soeP5XiLHSowjq2L7w7rwad0s7GwJNpjI0uCq7j7zN8hwkowM4QKBgQCEzPhT\nhtvwPne8qUPvgePzdAwoSDUX1T1htyCYwDYvDRyk1diy5NJiW249fLNrRx0fNcJV\noPD6ccFFbs53DiNi65VFe3MDuxqjOR3K2uQI+2FvNzEJO0uTM+xJ4WO8U8ci5thi\nLDShN8Unsb3PPNQyB6TqAjVMW7CTP9FQuh0aRQKBgEHBq/m9VJBGaZln+N9/Dq6u\nQKKmLlawX6EqAxJzO4kAJH+VHZQ07m92vk5B/9djcYHmtyADMc0d0svnH9209j2m\nNwQNBE0Bj3Qn6FE/8juRZkAwd1eXhqvVKXx1thp9tSkVdd9BDUR3FqaVPJTMEBPC\nvrPhBTKRycMZttvFMX1M\n-----END PRIVATE KEY-----\",\n" +
                        "  \"client_email\": \"aws-itau@project-aws-itau.iam.gserviceaccount.com\",\n" +
                        "  \"client_id\": \"114675321231418910266\",\n" +
                        "  \"auth_uri\": \"https://accounts.google.com/o/oauth2/auth\",\n" +
                        "  \"token_uri\": \"https://oauth2.googleapis.com/token\",\n" +
                        "  \"auth_provider_x509_cert_url\": \"https://www.googleapis.com/oauth2/v1/certs\",\n" +
                        "  \"client_x509_cert_url\": \"https://www.googleapis.com/robot/v1/metadata/x509/aws-itau%40project-aws-itau.iam.gserviceaccount.com\",\n" +
                        "  \"universe_domain\": \"googleapis.com\"\n" +
                        "}";


                try {
                    secretsManagerClient.createSecret(CreateSecretRequest.builder()
                            .name(effectiveSecretName)
                            .secretString(placeholderSecret)
                            .description("Placeholder for Google Drive Service Account Key (LocalStack)")
                            .build());
                    log.info("Secret '{}' criado como placeholder no LocalStack.", effectiveSecretName);
                } catch (SecretsManagerException createE) {
                    log.error("Erro ao criar secret placeholder '{}' no LocalStack: {}", effectiveSecretName, createE.getMessage(), createE);
                }
            } else {
                log.error("Erro ao verificar/criar Secret '{}' no LocalStack: {}", effectiveSecretName, e.getMessage(), e);
            }
        }
    }
}