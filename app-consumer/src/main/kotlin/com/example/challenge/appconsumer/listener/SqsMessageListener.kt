package com.example.challenge.appconsumer.listener

import com.example.challenge.appconsumer.model.TransactionEvent
import com.example.challenge.appconsumer.service.DataProcessorService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SqsMessageListener(
        private val objectMapper: ObjectMapper,
        private val dataProcessorService: DataProcessorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${aws.sqs.queue-name}")
    fun receiveMessage(messageBody: String) {
        logger.info("Mensagem SQS recebida: {}", messageBody)
        try {
            val event = objectMapper.readValue<TransactionEvent>(messageBody)
            logger.debug("Evento desserializado: {}", event)

            // Processa o evento
            dataProcessorService.processEvent(event)

            logger.info("Mensagem SQS processada com sucesso para transactionId: {}", event.transactionId)
            // O Spring Cloud AWS SQS automaticamente deleta a mensagem da fila se não houver exceção
        } catch (e: Exception) {
            logger.error("Erro ao processar mensagem SQS: {}", messageBody, e)
            throw e
        }
    }
}