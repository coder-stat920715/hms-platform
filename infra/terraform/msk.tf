resource "aws_msk_configuration" "hms" {
  name           = "hms-config"
  kafka_versions = ["3.7.x"]
  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=6
    unclean.leader.election.enable=false
    log.retention.hours=168
  EOT
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/hms/msk"
  retention_in_days = 90
}

resource "aws_msk_cluster" "hms" {
  cluster_name           = "hms"
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3                    # one per AZ
  enhanced_monitoring    = "PER_TOPIC_PER_BROKER"

  broker_node_group_info {
    instance_type   = "kafka.m5.large"
    client_subnets  = module.vpc.database_subnets
    security_groups = [aws_security_group.msk.id]
    storage_info {
      ebs_storage_info {
        volume_size = 500
        provisioned_throughput { enabled = false }
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.hms.arn
    revision = aws_msk_configuration.hms.latest_revision
  }

  client_authentication {
    unauthenticated = false
    sasl { iam = true }                          # bootstrap on :9098
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.data.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }
}

output "msk_bootstrap_iam" { value = aws_msk_cluster.hms.bootstrap_brokers_sasl_iam }

# Example: appointment-service produces nothing directly (Debezium does) but consumes billing/pharmacy events
data "aws_iam_policy_document" "appointment_kafka" {
  statement {
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
    resources = [aws_msk_cluster.hms.arn, "${replace(aws_msk_cluster.hms.arn, ":cluster/", ":group/")}/*"]
  }
  statement {
    actions   = ["kafka-cluster:DescribeTopic", "kafka-cluster:ReadData"]
    resources = [
      "${replace(aws_msk_cluster.hms.arn, ":cluster/", ":topic/")}/hms.billing.events",
      "${replace(aws_msk_cluster.hms.arn, ":cluster/", ":topic/")}/hms.pharmacy.events",
    ]
  }
}
