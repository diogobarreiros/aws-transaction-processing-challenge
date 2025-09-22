# ===============================================
# Recursos AWS Core para a Aplicação
# ===============================================

# --- Networking (VPC, Subnets, NAT Gateway, Security Groups) ---
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr_block
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.project_name}-vpc"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-igw"
  }
}

resource "aws_subnet" "public" {
  count             = length(var.public_subnet_cidrs)
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.public_subnet_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.project_name}-public-subnet-${count.index}"
  }
}

resource "aws_subnet" "private" {
  count             = length(var.private_subnet_cidrs)
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.private_subnet_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.project_name}-private-subnet-${count.index}"
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_eip" "nat_gateway" {
  count = length(aws_subnet.public)
  vpc   = true
  tags = {
    Name = "${var.project_name}-nat-eip-${count.index}"
  }
}

resource "aws_nat_gateway" "main" {
  count         = length(aws_subnet.public)
  allocation_id = aws_eip.nat_gateway[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = {
    Name = "${var.project_name}-nat-gateway-${count.index}"
  }
  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.project_name}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  count  = length(aws_subnet.private)
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[count.index].id # Aponta para o NAT Gateway correspondente à AZ
  }

  tags = {
    Name = "${var.project_name}-private-rt-${count.index}"
  }
}

resource "aws_route_table_association" "private" {
  count          = length(aws_subnet.private)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}


resource "aws_security_group" "fargate_tasks" {
  name        = "${var.project_name}-fargate-sg"
  description = "Security group for Fargate tasks"
  vpc_id      = aws_vpc.main.id

  # Allow all outbound traffic (for S3, SQS, CloudWatch Logs, ECR, Secrets Manager, Parameter Store)
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # No ingress needed if not exposed via Load Balancer or direct access
  # If you add an ALB later, you'd add ingress rules here to allow traffic from the ALB.
  # For now, these are backend services that interact with S3/SQS.

  tags = {
    Name = "${var.project_name}-fargate-sg"
  }
}

# --- S3 Buckets (from diagram and poupanca - CSV Ingestion 2.pdf) ---
resource "aws_s3_bucket" "input" {
  bucket = var.s3_input_bucket_name
  tags = {
    Name = "${var.project_name}-input-bucket"
  }
}

resource "aws_s3_bucket" "unprocessed" {
  bucket = var.s3_unprocessed_bucket_name
  tags = {
    Name = "${var.project_name}-unprocessed-bucket"
  }
}

resource "aws_s3_bucket" "processed" {
  bucket = var.s3_processed_bucket_name
  tags = {
    Name = "${var.project_name}-processed-bucket"
  }
}

# Adicionar política para garantir que as funções IAM possam acessar (estas são políticas de bucket,
# as permissões FINAS são controladas pelas IAM roles das tasks)
resource "aws_s3_bucket_policy" "input_access" {
  bucket = aws_s3_bucket.input.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect    = "Allow",
        Principal = { AWS = "*" }, # Temporariamente amplo, será restringido pelas policies das roles depois
        Action    = ["s3:GetObject", "s3:ListBucket", "s3:PutObject"],
        Resource = [
          aws_s3_bucket.input.arn,
          "${aws_s3_bucket.input.arn}/*",
        ]
      },
    ],
  })
}

resource "aws_s3_bucket_policy" "unprocessed_access" {
  bucket = aws_s3_bucket.unprocessed.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect    = "Allow",
        Principal = { AWS = "*" }, # Temporariamente amplo, será restringido pelas policies das roles depois
        Action    = ["s3:GetObject", "s3:PutObject", "s3:ListBucket", "s3:DeleteObject"], # Consumer pode precisar deletar/mover
        Resource = [
          aws_s3_bucket.unprocessed.arn,
          "${aws_s3_bucket.unprocessed.arn}/*",
        ]
      },
    ],
  })
}

resource "aws_s3_bucket_policy" "processed_access" {
  bucket = aws_s3_bucket.processed.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect    = "Allow",
        Principal = { AWS = "*" }, # Temporariamente amplo, será restringido pelas policies das roles depois
        Action    = ["s3:PutObject", "s3:ListBucket"],
        Resource = [
          aws_s3_bucket.processed.arn,
          "${aws_s3_bucket.processed.arn}/*",
        ]
      },
    ],
  })
}


# --- SQS Queue (from diagram and poupanca - CSV Ingestion 2.pdf) ---
resource "aws_sqs_queue" "transaction_events" {
  name                       = var.sqs_queue_name
  delay_seconds              = 0
  max_message_size           = 262144 # 256 KB
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 0
  visibility_timeout_seconds = 300    # 5 minutes

  tags = {
    Name = "${var.project_name}-sqs-queue"
  }
}

# --- CloudWatch Log Groups (poupanca - CSV Ingestion 2.pdf, Requisito Não Funcional 4) ---
resource "aws_cloudwatch_log_group" "app_producer_logs" {
  name              = "/ecs/${var.project_name}-app-producer"
  retention_in_days = 30 # Ajuste conforme sua necessidade

  tags = {
    Name = "${var.project_name}-app-producer-logs"
  }
}

resource "aws_cloudwatch_log_group" "app_consumer_logs" {
  name              = "/ecs/${var.project_name}-app-consumer"
  retention_in_days = 30 # Ajuste conforme sua necessidade

  tags = {
    Name = "${var.project_name}-app-consumer-logs"
  }
}


# --- ECS Cluster ---
resource "aws_ecs_cluster" "main" {
  name = "${var.project_name}-cluster"

  tags = {
    Name = "${var.project_name}-cluster"
  }
}

# --- IAM Roles for Fargate Tasks ---

# ECS Task Execution Role (for Fargate agent to pull images and push logs)
resource "aws_iam_role" "ecs_task_execution_role" {
  name = "${var.project_name}-ecs-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_role_policy" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}


# App Producer Task Role (for the application to interact with S3, SQS, Secrets Manager, Parameter Store)
resource "aws_iam_role" "app_producer_task_role" {
  name = "${var.project_name}-app-producer-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_policy" "app_producer_policy" {
  name        = "${var.project_name}-app-producer-policy"
  description = "Policy for app-producer to access S3, SQS, Secrets Manager, Parameter Store"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # CloudWatch Logs (já coberto pela execution role, mas bom explicitar)
      {
        Effect   = "Allow",
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"],
        Resource = "${aws_cloudwatch_log_group.app_producer_logs.arn}:*"
      },
      # S3 access (Read input, write unprocessed - poupanca - CSV Ingestion 2.pdf, Requisito Funcional 1)
      {
        Effect = "Allow",
        Action = [
          "s3:GetObject",
          "s3:ListBucket"
        ],
        Resource = [
          aws_s3_bucket.input.arn,
          "${aws_s3_bucket.input.arn}/*"
        ]
      },
      {
        Effect = "Allow",
        Action = [
          "s3:PutObject",
          "s3:DeleteObject" # Se o producer move o arquivo do input para unprocessed ou o apaga após processar
        ],
        Resource = [
          aws_s3_bucket.unprocessed.arn,
          "${aws_s3_bucket.unprocessed.arn}/*"
        ]
      },
      # SQS access (Send messages - poupanca - CSV Ingestion 2.pdf, Requisito Funcional 1)
      {
        Effect   = "Allow",
        Action   = ["sqs:SendMessage"],
        Resource = aws_sqs_queue.transaction_events.arn
      },
      # Secrets Manager (for Google Drive credentials)
      {
        Effect   = "Allow",
        Action   = ["secretsmanager:GetSecretValue"],
        Resource = var.google_drive_secret_arn
      },
      # SSM Parameter Store (for config - poupanca - CSV Ingestion 2.pdf, ponto 3)
      {
        Effect   = "Allow",
        Action   = ["ssm:GetParameter"],
        Resource = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/${var.project_name}/*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "app_producer_policy_attach" {
  role       = aws_iam_role.app_producer_task_role.name
  policy_arn = aws_iam_policy.app_producer_policy.arn
}


# App Consumer Task Role (for the application to interact with SQS, S3)
resource "aws_iam_role" "app_consumer_task_role" {
  name = "${var.project_name}-app-consumer-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_policy" "app_consumer_policy" {
  name        = "${var.project_name}-app-consumer-policy"
  description = "Policy for app-consumer to access SQS, S3, Parameter Store"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # CloudWatch Logs (já coberto pela execution role, mas bom explicitar)
      {
        Effect   = "Allow",
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"],
        Resource = "${aws_cloudwatch_log_group.app_consumer_logs.arn}:*"
      },
      # SQS access (Receive, Delete, Get attributes - poupanca - CSV Ingestion 2.pdf, ponto 7)
      {
        Effect = "Allow",
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ],
        Resource = aws_sqs_queue.transaction_events.arn
      },
      # S3 access (Write processed, read/write unprocessed if it handles retries/DLQ - poupanca - CSV Ingestion 2.pdf, ponto 8)
      {
        Effect = "Allow",
        Action = [
          "s3:PutObject",
          "s3:ListBucket"
        ],
        Resource = [
          aws_s3_bucket.processed.arn,
          "${aws_s3_bucket.processed.arn}/*"
        ]
      },
      # If consumer needs to interact with unprocessed bucket (e.g., move to DLQ or retry)
      {
        Effect = "Allow",
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject"
        ],
        Resource = [
          aws_s3_bucket.unprocessed.arn,
          "${aws_s3_bucket.unprocessed.arn}/*"
        ]
      },
      # SSM Parameter Store (if needed for other configs)
      {
        Effect   = "Allow",
        Action   = ["ssm:GetParameter"],
        Resource = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/${var.project_name}/*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "app_consumer_policy_attach" {
  role       = aws_iam_role.app_consumer_task_role.name
  policy_arn = aws_iam_policy.app_consumer_policy.arn
}

# --- AWS Caller Identity for ARNs ---
data "aws_caller_identity" "current" {}


# --- SSM Parameters (para configurar suas aplicações, conforme poupanca - CSV Ingestion 2.pdf, ponto 3) ---
resource "aws_ssm_parameter" "sqs_queue_url" {
  name        = "/${var.project_name}/sqs-queue-url"
  description = "URL of the SQS queue for transaction events"
  type        = "String"
  value       = aws_sqs_queue.transaction_events.id
}

resource "aws_ssm_parameter" "s3_input_bucket_name_param" {
  name        = "/${var.project_name}/s3-input-bucket-name"
  description = "Name of the S3 bucket for input files"
  type        = "String"
  value       = aws_s3_bucket.input.bucket
}

resource "aws_ssm_parameter" "s3_unprocessed_bucket_name_param" {
  name        = "/${var.project_name}/s3-unprocessed-bucket-name"
  description = "Name of the S3 bucket for unprocessed files"
  type        = "String"
  value       = aws_s3_bucket.unprocessed.bucket
}

resource "aws_ssm_parameter" "s3_processed_bucket_name_param" {
  name        = "/${var.project_name}/s3-processed-bucket-name"
  description = "Name of the S3 bucket for processed files"
  type        = "String"
  value       = aws_s3_bucket.processed.bucket
}

resource "aws_ssm_parameter" "google_drive_secret_arn_param" {
  name        = "/${var.project_name}/google-drive-secret-arn"
  description = "ARN of the Secrets Manager secret for Google Drive credentials"
  type        = "String"
  value       = var.google_drive_secret_arn
}


# --- ECS Task Definitions (Producer) ---
resource "aws_ecs_task_definition" "app_producer" {
  family                   = "${var.project_name}-app-producer-task"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.fargate_cpu
  memory                   = var.fargate_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = aws_iam_role.app_producer_task_role.arn

  container_definitions = jsonencode([
    {
      name      = "app-producer"
      image     = var.app_producer_docker_image
      cpu       = var.fargate_cpu
      memory    = var.fargate_memory
      essential = true
      environment = [
        {
          name  = "SQS_QUEUE_URL"
          value = aws_sqs_queue.transaction_events.id
        },
        {
          name  = "S3_INPUT_BUCKET_NAME"
          value = aws_s3_bucket.input.bucket
        },
        {
          name  = "S3_UNPROCESSED_BUCKET_NAME"
          value = aws_s3_bucket.unprocessed.bucket
        },
        {
          name  = "GOOGLE_DRIVE_SECRET_ARN"
          value = var.google_drive_secret_arn
        }
        # Adicione outras variáveis de ambiente conforme necessário para sua aplicação
      ],
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app_producer_logs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])

  tags = {
    Name = "${var.project_name}-app-producer-task"
  }
}

# --- ECS Service (Producer) ---
resource "aws_ecs_service" "app_producer" {
  name            = "${var.project_name}-app-producer-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app_producer.arn
  desired_count   = 1 # Inicie com 1 instância, ajuste conforme a carga
  launch_type     = "FARGATE"
  platform_version = "1.4.0" # ou a versão mais recente suportada

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.fargate_tasks.id]
    assign_public_ip = false
  }

  tags = {
    Name = "${var.project_name}-app-producer-service"
  }

  # Health checks do Fargate
  # deployment_controller {
  #   type = "ECS"
  # }
  # health_check_grace_period_seconds = 60 # Ajuste conforme o tempo de inicialização da sua app
}


# --- ECS Task Definitions (Consumer) ---
resource "aws_ecs_task_definition" "app_consumer" {
  family                   = "${var.project_name}-app-consumer-task"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.fargate_cpu
  memory                   = var.fargate_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = aws_iam_role.app_consumer_task_role.arn

  container_definitions = jsonencode([
    {
      name      = "app-consumer"
      image     = var.app_consumer_docker_image
      cpu       = var.fargate_cpu
      memory    = var.fargate_memory
      essential = true
      environment = [
        {
          name  = "SQS_QUEUE_URL"
          value = aws_sqs_queue.transaction_events.id
        },
        {
          name  = "S3_PROCESSED_BUCKET_NAME"
          value = aws_s3_bucket.processed.bucket
        },
        {
          name  = "S3_UNPROCESSED_BUCKET_NAME" # Caso o consumer precise para DLQ ou retries
          value = aws_s3_bucket.unprocessed.bucket
        }
        # Adicione outras variáveis de ambiente conforme necessário para sua aplicação
      ],
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app_consumer_logs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])

  tags = {
    Name = "${var.project_name}-app-consumer-task"
  }
}

# --- ECS Service (Consumer) ---
resource "aws_ecs_service" "app_consumer" {
  name            = "${var.project_name}-app-consumer-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app_consumer.arn
  desired_count   = 1 # Inicie com 1 instância, ajuste conforme a carga
  launch_type     = "FARGATE"
  platform_version = "1.4.0" # ou a versão mais recente suportada

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.fargate_tasks.id]
    assign_public_ip = false
  }

  # Health checks do Fargate
  # deployment_controller {
  #   type = "ECS"
  # }
  # health_check_grace_period_seconds = 60 # Ajuste conforme o tempo de inicialização da sua app

  tags = {
    Name = "${var.project_name}-app-consumer-service"
  }
}