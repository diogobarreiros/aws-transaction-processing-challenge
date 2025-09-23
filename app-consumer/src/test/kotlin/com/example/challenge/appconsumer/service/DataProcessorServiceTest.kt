package com.example.challenge.appconsumer.service

import com.example.challenge.appconsumer.model.TransactionEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class DataProcessorServiceTest {

    @Mock
    private lateinit var s3Service: S3Service

    private lateinit var objectMapper: ObjectMapper

    private val s3OutputBucketName = "test-processed-data-bucket"

    private lateinit var dataProcessorService: DataProcessorService

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }

        dataProcessorService = DataProcessorService(s3Service, objectMapper, s3OutputBucketName)
    }

    @Test
    @DisplayName("Deve processar um evento e fazer upload para o S3 com sucesso")
    fun shouldProcessEventAndUploadToS3Successfully() {
        val transactionId = UUID.randomUUID().toString()
        val transactionDate = LocalDateTime.of(2023, 10, 27, 10, 30, 0)
        val inputEvent = TransactionEvent(
            transactionId = transactionId,
            userId = "user123",
            amount = 150.75,
            currency = "USD",
            transactionDate = transactionDate,
            status = "PENDING",
            originalSource = "SQS"
        )

        doNothing().whenever(s3Service).uploadProcessedData(any(), any(), any())

        dataProcessorService.processEvent(inputEvent)

        val expectedProcessedEvent = inputEvent.copy(
            status = "PROCESSED",
            originalSource = "SQS-Consumer"
        )
        val expectedProcessedJson = objectMapper.writeValueAsString(expectedProcessedEvent)

        // Gerando a chave S3 esperada
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        val expectedS3Key = "processed-transactions/${transactionDate.format(dateFormatter)}/${transactionId}.json"

        verify(s3Service, times(1)).uploadProcessedData(
            eq(s3OutputBucketName),
            eq(expectedS3Key),
            eq(expectedProcessedJson)
        )
    }

    @Test
    @DisplayName("Deve lançar exceção se a conversão JSON falhar")
    fun shouldThrowExceptionIfJsonConversionFails() {
        val failingObjectMapper = org.mockito.kotlin.mock<ObjectMapper>()
        val dataProcessorServiceWithFailingMapper = DataProcessorService(s3Service, failingObjectMapper, s3OutputBucketName)

        val inputEvent = TransactionEvent(
            transactionId = UUID.randomUUID().toString(),
            userId = "user123",
            amount = 150.75,
            currency = "USD",
            transactionDate = LocalDateTime.now(),
            status = "PENDING",
            originalSource = "SQS"
        )

        val expectedErrorMessage = "Erro de serialização JSON simulado"
        whenever(failingObjectMapper.writeValueAsString(any())).thenThrow(RuntimeException(expectedErrorMessage))

        val exception = assertThrows<RuntimeException> {
            dataProcessorServiceWithFailingMapper.processEvent(inputEvent)
        }

        assert(exception.message == expectedErrorMessage)

        verify(s3Service, times(0)).uploadProcessedData(any(), any(), any())
    }

    @Test
    @DisplayName("Deve lançar exceção se o S3Service falhar no upload")
    fun shouldThrowExceptionIfS3ServiceFailsToUpload() {
        val inputEvent = TransactionEvent(
            transactionId = UUID.randomUUID().toString(),
            userId = "user123",
            amount = 150.75,
            currency = "USD",
            transactionDate = LocalDateTime.now(),
            status = "PENDING",
            originalSource = "SQS"
        )
        val expectedErrorMessage = "Erro de upload para S3 simulado"

        doThrow(RuntimeException(expectedErrorMessage)).whenever(s3Service).uploadProcessedData(any(), any(), any())

        val exception = assertThrows<RuntimeException> {
            dataProcessorService.processEvent(inputEvent)
        }

        assert(exception.message == expectedErrorMessage)

        val expectedProcessedEvent = inputEvent.copy(
            status = "PROCESSED",
            originalSource = "SQS-Consumer"
        )
        val expectedProcessedJson = objectMapper.writeValueAsString(expectedProcessedEvent)
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        val expectedS3Key = "processed-transactions/${inputEvent.transactionDate.format(dateFormatter)}/${inputEvent.transactionId}.json"

        verify(s3Service, times(1)).uploadProcessedData(
            eq(s3OutputBucketName),
            eq(expectedS3Key),
            eq(expectedProcessedJson)
        )
    }
}