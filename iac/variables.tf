# Variáveis de Configuração Global
variable "aws_region" {
  description = "A região AWS onde os recursos serão criados."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Nome do ambiente (e.g., dev, staging, prod)."
  type        = string
  default     = "dev"
}

variable "project_name" {
  description = "Nome do projeto para prefixar recursos."
  type        = string
  default     = "transaction-processor"
}

# Variáveis para SQS
variable "sqs_queue_name" {
  description = "Nome da fila SQS para eventos de transação."
  type        = string
  default     = "transaction-events-queue"
}

# Variáveis para DynamoDB
variable "dynamodb_table_name" {
  description = "Nome da tabela DynamoDB para arquivos processados."
  type        = string
  default     = "transaction-processing-processed-files"
}

# Variáveis para S3
variable "s3_rejected_transactions_bucket_name" {
  description = "Nome do bucket S3 para transações rejeitadas."
  type        = string
  default     = "transaction-processing-rejected-data"
}

# Variáveis para Secrets Manager
variable "google_drive_secret_name" {
  description = "Nome do Secret no Secrets Manager que guarda as credenciais do Google Drive."
  type        = string
  default     = "google-drive-service-account-key"
}

# Variáveis para IAM Role (permissões da aplicação)
variable "app_iam_role_name" {
  description = "Nome do IAM Role para a aplicação no EKS."
  type        = string
  default     = "transaction-processor-app-role"
}

# Variável para o conteúdo da chave de serviço do Google Drive.
variable "google_drive_service_account_key_json" {
  description = "Conteúdo JSON da chave de serviço do Google Drive. Mantenha isso seguro!"
  type        = string
  sensitive   = true
}

# Definição da variável que controla o LocalStack
variable "localstack_enabled" {
  description = "Enable LocalStack for local development"
  type        = bool
  default     = false
}