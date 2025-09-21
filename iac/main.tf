# ===============================================
# Recursos AWS Core para a Aplicação
# ===============================================

# --- SQS Queue para Eventos de Transação ---
resource "aws_sqs_queue" "transaction_events_queue" {
  name                       = "${var.project_name}-${var.sqs_queue_name}-${var.environment}"
  delay_seconds              = 0
  max_message_size           = 262144 # 256 KB
  message_retention_seconds  = 345600 # 4 dias
  receive_wait_time_seconds  = 0
  visibility_timeout_seconds = 30

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

# --- DynamoDB Table para Arquivos Processados (Idempotência) ---
resource "aws_dynamodb_table" "processed_files_table" {
  name         = "${var.project_name}-${var.dynamodb_table_name}-${var.environment}"
  hash_key     = "fileId"
  billing_mode = "PAY_PER_REQUEST"

  attribute {
    name = "fileId"
    type = "S"
  }

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

# --- S3 Bucket para Transações Rejeitadas ---
resource "aws_s3_bucket" "rejected_transactions_bucket" {
  bucket = "${var.project_name}-${replace(var.s3_rejected_transactions_bucket_name, "_", "-")}-${var.environment}"

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_s3_bucket_versioning" "rejected_transactions_bucket_versioning" {
  bucket = aws_s3_bucket.rejected_transactions_bucket.id
  versioning_configuration {
    status = "Enabled"
  }
}

# --- Secrets Manager para Credenciais do Google Drive ---
resource "aws_secretsmanager_secret" "google_drive_credentials_secret" {
  name        = "${var.project_name}/${var.google_drive_secret_name}"
  description = "Credenciais da conta de serviço do Google Drive para o ${var.project_name} no ambiente ${var.environment}"

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_secretsmanager_secret_version" "google_drive_credentials_secret_version" {
  secret_id     = aws_secretsmanager_secret.google_drive_credentials_secret.id
  secret_string = var.google_drive_service_account_key_json # Conteúdo JSON da chave de serviço
}

# ===============================================
# IAM Role e Políticas para a Aplicação no EKS
# (Permissões para SQS, DynamoDB, S3, Secrets Manager)
# ===============================================

resource "aws_iam_role" "app_execution_role" {
  name = "${var.project_name}-${var.app_iam_role_name}-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "eks.amazonaws.com" # EKS assume este role, ou o pod EKS com IRSA
        }
      },
    ]
  })

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_iam_role_policy" "app_access_policy" {
  name = "${var.project_name}-${var.app_iam_role_name}-policy-${var.environment}"
  role = aws_iam_role.app_execution_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:GetQueueUrl",
          "sqs:GetQueueAttributes",
        ]
        Resource = aws_sqs_queue.transaction_events_queue.arn
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:GetItem",
        ]
        Resource = aws_dynamodb_table.processed_files_table.arn
      },
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:ListBucket",
        ]
        Resource = [
          aws_s3_bucket.rejected_transactions_bucket.arn,
          "${aws_s3_bucket.rejected_transactions_bucket.arn}/*",
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
        ]
        Resource = aws_secretsmanager_secret.google_drive_credentials_secret.arn
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:*:*:*"
      },
    ]
  })
}