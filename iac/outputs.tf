output "ecs_cluster_name" {
  description = "The name of the ECS cluster."
  value       = aws_ecs_cluster.main.name
}

output "app_producer_service_name" {
  description = "The name of the app-producer ECS service."
  value       = aws_ecs_service.app_producer.name
}

output "app_consumer_service_name" {
  description = "The name of the app-consumer ECS service."
  value       = aws_ecs_service.app_consumer.name
}

output "sqs_queue_url" {
  description = "The URL of the SQS queue."
  value       = aws_sqs_queue.transaction_events.id
}

output "s3_input_bucket_name" {
  description = "Name of the S3 input bucket."
  value       = aws_s3_bucket.input.bucket
}

output "s3_processed_bucket_name" {
  description = "Name of the S3 processed bucket."
  value       = aws_s3_bucket.processed.bucket
}