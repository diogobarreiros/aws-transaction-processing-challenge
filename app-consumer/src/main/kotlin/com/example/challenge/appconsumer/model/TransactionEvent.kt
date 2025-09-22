package com.example.challenge.appconsumer.model

import java.time.LocalDateTime

// Exemplo de estrutura de dados que viria do app-producer via SQS
data class TransactionEvent(
        val transactionId: String,
        val userId: String,
        val amount: Double,
        val currency: String,
        val transactionDate: LocalDateTime,
        val status: String,
        val originalSource: String? = null
)