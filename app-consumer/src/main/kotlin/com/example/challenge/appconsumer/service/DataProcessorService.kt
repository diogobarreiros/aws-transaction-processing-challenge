package com.example.challenge.appconsumer.service

import com.example.challenge.appconsumer.model.TransactionEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter

@Service
class DataProcessorService(
        private val s3Service: S3Service,
        private val objectMapper: ObjectMapper,
        @Value("\${aws.s3.output-bucket-name}")
        private val s3OutputBucketName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun processEvent(event: TransactionEvent) {
        logger.info("Iniciando processamento do evento para transactionId: {}", event.transactionId)

        // Exemplo simples de enriquecimento
        val processedEvent = event.copy(
                status = "PROCESSED",
                originalSource = "SQS-Consumer",
        )

        // Converte o objeto processado de volta para JSON para armazenamento no S3
        val processedJson = objectMapper.writeValueAsString(processedEvent)

        // Define o path no S3. Uma boa prática é usar uma estrutura baseada em data.
        val datePath = processedEvent.transactionDate.format(dateFormatter)
        val s3Key = "processed-transactions/$datePath/${processedEvent.transactionId}.json"

        // Envia para o S3
        s3Service.uploadProcessedData(s3OutputBucketName, s3Key, processedJson)

        logger.info("Evento processado e salvo no S3 em s3://$s3OutputBucketName/$s3Key")
    }
}