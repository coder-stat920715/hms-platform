# Resources referenced by the data tier but not defined elsewhere.
data "aws_caller_identity" "current_for_opensearch" {}

resource "aws_iam_role" "opensearch_admin" {
  name = "hms-opensearch-admin"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current_for_opensearch.account_id}:root" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_cloudwatch_log_group" "os_audit" {
  name              = "/hms/opensearch/audit"
  retention_in_days = 365
}

resource "aws_cloudwatch_log_resource_policy" "opensearch" {
  policy_name = "hms-opensearch-logs"
  policy_document = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "es.amazonaws.com" }
      Action    = ["logs:PutLogEvents", "logs:CreateLogStream"]
      Resource  = "${aws_cloudwatch_log_group.os_audit.arn}:*"
    }]
  })
}
