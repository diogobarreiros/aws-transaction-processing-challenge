# variables.tf
variable "aws_region" {
  description = "A AWS region to deploy resources into."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "A name prefix for all resources."
  type        = string
  default     = "transaction-processor"
}

variable "vpc_cidr_block" {
  description = "The CIDR block for the VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "List of CIDR blocks for public subnets."
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "private_subnet_cidrs" {
  description = "List of CIDR blocks for private subnets."
  type        = list(string)
  default     = ["10.0.101.0/24", "10.0.102.0/24"]
}

# --- S3 Buckets ---
variable "s3_input_bucket_name" {
  description = "Name for the S3 input bucket."
  type        = string
  default     = "your-input-bucket-name-12345"
}

variable "s3_unprocessed_bucket_name" {
  description = "Name for the S3 unprocessed bucket."
  type        = string
  default     = "your-unprocessed-bucket-name-12345"
}

variable "s3_processed_bucket_name" {
  description = "Name for the S3 processed bucket."
  type        = string
  default     = "your-processed-bucket-name-12345"
}

# --- SQS Queue ---
variable "sqs_queue_name" {
  description = "Name for the SQS queue."
  type        = string
  default     = "transaction-events-queue"
}

# --- Docker Images (substitua pelas suas!) ---
variable "app_producer_docker_image" {
  description = "Docker image URI for the app-producer."
  type        = string
  default     = "nginx:latest"
}

variable "app_consumer_docker_image" {
  description = "Docker image URI for the app-consumer."
  type        = string
  default     = "busybox:latest"
}

variable "fargate_cpu" {
  description = "CPU units for Fargate tasks (e.g., 256 (.25 vCPU), 512 (.5 vCPU), 1024 (1 vCPU))."
  type        = number
  default     = 512
}

variable "fargate_memory" {
  description = "Memory (in MiB) for Fargate tasks (e.g., 512, 1024, 2048)."
  type        = number
  default     = 1024
}

variable "google_drive_secret_arn" {
  description = "The ARN of the Secrets Manager secret for Google Drive service account key."
  type        = string
  default = "arn:aws:secretsmanager:us-east-1:123456789012:secret:dummy-secret-arn-12345"
}