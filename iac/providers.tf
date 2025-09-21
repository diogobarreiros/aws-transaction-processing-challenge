provider "aws" {
  region = var.aws_region

  dynamic "endpoint" {
    for_each = var.use_localstack ? ["true"] : []
    content {
      s3          = "http://localstack:4510"
      sqs         = "http://localstack:4510"
      glue        = "http://localstack:4510"
      cloudwatch  = "http://localstack:4510"
      iam         = "http://localstack:4510"
      sts         = "http://localstack:4510"
      ssm         = "http://localstack:4510"
      ecs         = "http://localstack:4510"
      ecr         = "http://localstack:4510"
      kms         = "http://localstack:4510"
      lambda      = "http://localstack:4510"
    }
  }
}