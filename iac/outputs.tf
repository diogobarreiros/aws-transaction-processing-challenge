# Saída para a URL da fila SQS
output "sqs_queue_url" {
  description = "A URL da fila SQS criada."
  value       = aws_sqs_queue.transaction_events_queue.url
}

# Saída para o nome da tabela DynamoDB
output "dynamodb_table_name" {
  description = "O nome da tabela DynamoDB para arquivos processados."
  value       = aws_dynamodb_table.processed_files_table.name
}

# Saída para o nome do bucket S3 de transações rejeitadas
output "s3_rejected_transactions_bucket_name" {
  description = "O nome do bucket S3 para transações rejeitadas."
  value       = aws_s3_bucket.rejected_transactions_bucket.bucket
}

# Saída para o ARN do Secret do Google Drive
output "google_drive_secret_arn" {
  description = "O ARN do Secret no Secrets Manager para as credenciais do Google Drive."
  value       = aws_secretsmanager_secret.google_drive_credentials_secret.arn
}

# Saída para o nome do Secret do Google Drive (o nome amigável)
output "google_drive_secret_name" {
  description = "O nome do Secret no Secrets Manager para as credenciais do Google Drive."
  value       = aws_secretsmanager_secret.google_drive_credentials_secret.name
}

# Saída para o ARN da IAM Role da aplicação
output "app_iam_role_arn" {
  description = "O ARN do IAM Role para a aplicação no EKS."
  value       = aws_iam_role.app_execution_role.arn
}