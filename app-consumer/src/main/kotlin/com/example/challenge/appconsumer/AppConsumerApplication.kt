package com.example.challenge.appconsumer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import io.awspring.cloud.sqs.annotation.EnableSqs

@SpringBootApplication
@EnableSqs
class AppConsumerApplication

fun main(args: Array<String>) {
	runApplication<AppConsumerApplication>(*args)
}