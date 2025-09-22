package com.example.challenge.appconsumer.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@Service
class S3Service(private val s3Client: S3Client) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun uploadProcessedData(bucketName: String, key: String, data: String) {
        logger.info("Tentando fazer upload para S3://{}/{}", bucketName, key)
        val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("application/json")
                .build()

        s3Client.putObject(putObjectRequest, RequestBody.fromString(data))
        logger.info("Upload bem-sucedido para S3://{}/{}", bucketName, key)
    }
}