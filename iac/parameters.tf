# ==================================================
# AWS Parameter Store - Parâmetros de Configuração
# ==================================================

# Parâmetro para as regras de processamento, lido pelo app-producer
resource "aws_ssm_parameter" "processing_rules" {
  # O nome deve ser o mesmo usado em @Value("${app.ssm.processing-rules-parameter-name}")
  # Exemplo: /transaction-challenge/config/processing-rules ou /my-app/processing-rules
  name        = "/${var.project_name}/config/processing-rules"
  type        = "String"
  value       = jsonencode({
    enableBetaFeatures = false,
    minAmount          = 1.00,
  })
  description = "Regras de processamento para o serviço app-producer."
  tier        = "Standard"
}

# Parâmetro para a URL da fila SQS de eventos
resource "aws_ssm_parameter" "sqs_queue_url_param" {
  name        = "/${var.project_name}/sqs/transaction-events-queue-url"
  type        = "String"
  # Referencie o recurso SQS criado no seu main.tf
  value       = aws_sqs_queue.transaction_events_queue.url # Substitua pelo nome real do seu recurso SQS
  description = "URL da fila SQS para eventos de transação do app-producer."
}

# Parâmetro para o nome do bucket S3 de transações rejeitadas
resource "aws_ssm_parameter" "rejected_transactions_bucket_name_param" {
  name        = "/${var.project_name}/s3/rejected-transactions-bucket-name"
  type        = "String"
  # Referencie o recurso S3 criado no seu main.tf
  value       = aws_s3_bucket.rejected_transactions_bucket.id # Substitua pelo nome real do seu recurso S3
  description = "Nome do bucket S3 para transações rejeitadas pelo app-producer."
}