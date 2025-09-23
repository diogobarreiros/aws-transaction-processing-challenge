package com.example.challenge.appconsumer.listener

import com.example.challenge.appconsumer.model.TransactionEvent
import com.example.challenge.appconsumer.service.DataProcessorService
import com.fasterxml.jackson.core.JsonProcessingException
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
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class SqsMessageListenerTest {

    @Mock
    private lateinit var dataProcessorService: DataProcessorService

    private lateinit var objectMapper: ObjectMapper

    private lateinit var sqsMessageListener: SqsMessageListener

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }

        sqsMessageListener = SqsMessageListener(objectMapper, dataProcessorService)
    }

    @Test
    @DisplayName("Deve processar uma mensagem SQS válida com sucesso")
    fun shouldProcessMessageSuccessfully() {
        val transactionEvent = TransactionEvent(
            transactionId = UUID.randomUUID().toString(),
            userId = "user123",
            amount = 100.50,
            currency = "BRL",
            transactionDate = LocalDateTime.now(),
            status = "PENDING",
            originalSource = "GoogleDrive"
        )
        val messageBody = objectMapper.writeValueAsString(transactionEvent)

        doNothing().whenever(dataProcessorService).processEvent(any())

        sqsMessageListener.receiveMessage(messageBody)

        verify(dataProcessorService, times(1)).processEvent(eq(transactionEvent))
    }

    @Test
    @DisplayName("Deve lançar uma exceção quando a mensagem SQS for JSON inválido")
    fun shouldThrowExceptionWhenInvalidJsonMessage() {
        val invalidJsonMessage = "{ \"transactionId\": \"123\", \"userId\": \"user\", "

        val exception = assertThrows<Exception> { // O listener encapsula JsonProcessingException em Exception
            sqsMessageListener.receiveMessage(invalidJsonMessage)
        }

        assert(exception.cause is JsonProcessingException) { "Esperava-se que a causa fosse JsonProcessingException, mas foi ${exception.cause?.javaClass?.simpleName}" }
        assert(exception.message?.contains("Erro ao processar mensagem SQS") == true) { "Esperava-se que a mensagem contivesse 'Erro ao processar mensagem SQS'" }

        verify(dataProcessorService, never()).processEvent(any())
    }

    @Test
    @DisplayName("Deve re-lançar uma exceção quando o DataProcessorService falhar")
    fun shouldReThrowExceptionWhenDataProcessorServiceFails() {
        val transactionEvent = TransactionEvent(
            transactionId = UUID.randomUUID().toString(),
            userId = "user456",
            amount = 200.00,
            currency = "USD",
            transactionDate = LocalDateTime.now(),
            status = "PENDING",
            originalSource = "S3"
        )
        val messageBody = objectMapper.writeValueAsString(transactionEvent)
        val expectedErrorMessage = "Falha ao processar dados por algum motivo"

        doThrow(RuntimeException(expectedErrorMessage)).whenever(dataProcessorService).processEvent(any())

        val exception = assertThrows<RuntimeException> {
            sqsMessageListener.receiveMessage(messageBody)
        }

        assert(exception.message == expectedErrorMessage)

        verify(dataProcessorService, times(1)).processEvent(eq(transactionEvent))
    }
}