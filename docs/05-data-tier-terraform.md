# HMS Part 5: Data Tier (Terraform)

KMS, per-service PostgreSQL (RDS), Kafka (MSK), Redis (ElastiCache) and OpenSearch for the CQRS read models. Builds on the VPC and security groups in Part 3 (`module.vpc`, `aws_security_group.data`, `module.eks`). Untested reference: run `terraform plan` in a sandbox first.

```
infra/kms.tf  rds.tf  msk.tf  redis.tf  opensearch.tf  data_sg.tf
```

---

## 0. Corrections to earlier parts (already applied to those files; see `hms-changelog.md`)

- **Security group:** replace the catch-all `data` ingress rule from Part 3 with the port-specific rules in section 5.
- **Kafka auth and port:** Parts 1 to 4 used `:9094`. This part enables IAM authentication on MSK, whose TLS+IAM listener is **`:9098`**. Update the bootstrap addresses in the Helm values and the Strimzi `KafkaConnect`, and add the MSK IAM auth library and Spring properties from section 3.3.
- **Secret layout:** RDS-managed master secrets (below) are JSON with `username` and `password`. The ExternalSecret `remoteRef` values in Part 4 (`hms/appointment/db`) should point at those secret names, or use `remoteRef.key` with the ARN.

---

## 1. kms.tf

```hcl
resource "aws_kms_key" "data" {
  description             = "HMS data at rest (RDS, MSK, Redis, OpenSearch)"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}
resource "aws_kms_alias" "data" {
  name          = "alias/hms-data"
  target_key_id = aws_kms_key.data.key_id
}
```

Use separate keys per data class if auditors want independent revocation (for example one key for PHI databases and another for Kafka).

---

## 2. rds.tf: one PostgreSQL instance per service

Database-per-service keeps bounded contexts isolated. For cost, start with a shared cluster and separate logical databases and roles, then split when load demands.

```hcl
locals {
  databases = {
    patient     = { instance_class = "db.r6g.large",  storage = 200 }
    appointment = { instance_class = "db.r6g.large",  storage = 100 }
    billing     = { instance_class = "db.r6g.large",  storage = 100 }
    pharmacy    = { instance_class = "db.r6g.medium", storage = 100 }
    iam         = { instance_class = "db.t4g.medium", storage = 50 }
  }
}

resource "aws_db_subnet_group" "main" {
  name       = "hms"
  subnet_ids = module.vpc.database_subnets
}

resource "aws_db_parameter_group" "pg16" {
  name   = "hms-pg16"
  family = "postgres16"

  # Debezium / CDC
  parameter { name = "rds.logical_replication"  value = "1"   apply_method = "pending-reboot" }
  parameter { name = "max_replication_slots"    value = "10"  apply_method = "pending-reboot" }
  parameter { name = "max_wal_senders"          value = "10"  apply_method = "pending-reboot" }
  # Hardening and audit
  parameter { name = "rds.force_ssl"            value = "1"   apply_method = "immediate" }
  parameter { name = "log_connections"          value = "1"   apply_method = "immediate" }
  parameter { name = "log_statement"            value = "ddl" apply_method = "immediate" }
  # Don't let a stuck slot fill the disk (value in MB; -1 disables the cap)
  parameter { name = "max_slot_wal_keep_size"   value = "20480" apply_method = "pending-reboot" }
}

resource "aws_db_instance" "svc" {
  for_each = local.databases

  identifier     = "hms-${each.key}"
  engine         = "postgres"
  engine_version = "16.4"
  instance_class = each.value.instance_class

  db_name  = each.key
  username = "hms_admin"
  manage_master_user_password   = true                    # stored and rotated in Secrets Manager
  master_user_secret_kms_key_id = aws_kms_key.data.arn

  allocated_storage     = each.value.storage
  max_allocated_storage = each.value.storage * 4
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.data.arn

  multi_az               = true
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.data.id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.pg16.name

  backup_retention_period    = 14
  backup_window              = "02:00-03:00"
  maintenance_window         = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot      = true
  deletion_protection        = true
  skip_final_snapshot        = false
  final_snapshot_identifier  = "hms-${each.key}-final"
  auto_minor_version_upgrade = true

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.data.arn
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.rds_monitoring.arn
  iam_database_authentication_enabled   = true
}

resource "aws_iam_role" "rds_monitoring" {
  name = "hms-rds-monitoring"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "monitoring.rds.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}
resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}
```

### Post-provision SQL (run once per database, e.g. via Flyway `V0__roles.sql` or a bootstrap job)

```sql
-- Application role: DML only, no DDL in production
CREATE ROLE hms_app LOGIN;                       -- or: GRANT rds_iam TO hms_app; for IAM auth
GRANT CONNECT ON DATABASE appointment TO hms_app;
GRANT USAGE ON SCHEMA public TO hms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO hms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hms_app;

-- Debezium role: replication + read on outbox only
CREATE ROLE debezium LOGIN REPLICATION;
GRANT rds_replication TO debezium;
GRANT SELECT ON outbox TO debezium;
CREATE PUBLICATION dbz_outbox FOR TABLE outbox;  -- if not created by the migration
```

Flyway runs with a separate migration role that owns the schema. Store `hms_app` and `debezium` credentials as additional Secrets Manager entries that the ExternalSecrets in Part 4 read.

---

## 3. msk.tf

```hcl
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
```

### 3.1 IAM policy per service (least privilege)

```hcl
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
```

Attach it to the service's IRSA role (Part 4). Kafka Connect (Debezium) gets `WriteData` and `CreateTopic` on `hms.*.events`.

### 3.2 Topics (auto-create is off)

Create once from a bootstrap job or an admin host with IAM access:

```bash
for t in appointment patient billing pharmacy iam \
         billing-commands pharmacy-commands; do
  kafka-topics.sh --bootstrap-server $BOOTSTRAP --command-config client-iam.properties \
    --create --if-not-exists --topic "hms.${t}.events" \
    --partitions 6 --replication-factor 3 \
    --config min.insync.replicas=2 --config retention.ms=604800000
done
# one DLT per consumer group, e.g. hms.billing.events.appointment-saga.DLT
```

Partition count caps consumer parallelism, so size it for the largest consumer group you expect. Keys are aggregate IDs, so per-aggregate ordering is preserved.

### 3.3 Spring Kafka client settings for IAM auth

```xml
<dependency>
  <groupId>software.amazon.msk</groupId>
  <artifactId>aws-msk-iam-auth</artifactId>
  <version>2.2.0</version>
</dependency>
```
```yaml
spring.kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP}          # ...:9098
  properties:
    security.protocol: SASL_SSL
    sasl.mechanism: AWS_MSK_IAM
    sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
    sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
  consumer:
    enable-auto-commit: false
    auto-offset-reset: earliest
    isolation-level: read_committed
  producer:
    acks: all
    properties: { enable.idempotence: true }
```

For Strimzi Connect, use the same properties under `spec.config` (`security.protocol`, `sasl.*`, plus `producer.*` and `consumer.*` copies) and bake the IAM auth jar into the Connect image.

---

## 4. redis.tf and opensearch.tf

```hcl
# ---------- Redis (gateway rate limiting) ----------
resource "aws_elasticache_subnet_group" "redis" {
  name       = "hms-redis"
  subnet_ids = module.vpc.database_subnets
}

resource "random_password" "redis" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "redis" { name = "hms/redis" kms_key_id = aws_kms_key.data.arn }
resource "aws_secretsmanager_secret_version" "redis" {
  secret_id     = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({ password = random_password.redis.result })
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "hms-redis"
  description          = "Gateway rate limiting"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = "cache.r6g.large"
  num_cache_clusters   = 3
  port                 = 6379

  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.redis.name
  security_group_ids         = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result

  snapshot_retention_limit = 0        # rate-limit counters are disposable
}
```

Spring side: `spring.data.redis.ssl.enabled=true` and `spring.data.redis.password` from the secret. Spring Cloud Gateway's `RedisRateLimiter` fails open when Redis is unreachable (requests are allowed), and the WAF rate rule still protects the edge, so a Redis outage does not take the API down. Alarm on it anyway, because per-user limits are off while it lasts.

```hcl
# ---------- OpenSearch (CQRS read models) ----------
resource "aws_opensearch_domain" "read" {
  domain_name    = "hms-read"
  engine_version = "OpenSearch_2.15"

  cluster_config {
    instance_type            = "r6g.large.search"
    instance_count           = 3
    zone_awareness_enabled   = true
    zone_awareness_config { availability_zone_count = 3 }
    dedicated_master_enabled = true
    dedicated_master_type    = "m6g.large.search"
    dedicated_master_count   = 3
  }

  ebs_options {
    ebs_enabled = true
    volume_type = "gp3"
    volume_size = 200
  }

  vpc_options {
    subnet_ids         = module.vpc.database_subnets
    security_group_ids = [aws_security_group.opensearch.id]
  }

  encrypt_at_rest { enabled = true, kms_key_id = aws_kms_key.data.arn }
  node_to_node_encryption { enabled = true }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  advanced_security_options {
    enabled                        = true
    internal_user_database_enabled = false
    master_user_options { master_user_arn = aws_iam_role.opensearch_admin.arn }
  }

  log_publishing_options {
    log_type                 = "AUDIT_LOGS"
    cloudwatch_log_group_arn = aws_cloudwatch_log_group.os_audit.arn
  }
}
```

Part 1's projector used Spring Data Elasticsearch. OpenSearch 2.x is **not** protocol-compatible with the Elasticsearch 8 client, so use the `opensearch-java` client (or `spring-data-opensearch`), or choose Elasticsearch on Elastic Cloud or EC2 if you must keep the ES client. Sign requests with SigV4 via IRSA, and give each projector a role scoped to its index prefix (`appointment-*`).

---

## 5. data_sg.tf: port-specific rules (replaces the Part 3 `data` rule)

```hcl
resource "aws_security_group" "msk"        { name = "hms-msk"        vpc_id = module.vpc.vpc_id }
resource "aws_security_group" "redis"      { name = "hms-redis"      vpc_id = module.vpc.vpc_id }
resource "aws_security_group" "opensearch" { name = "hms-opensearch" vpc_id = module.vpc.vpc_id }

locals {
  clients = [aws_security_group.services.id, aws_security_group.gateway.id, module.eks.node_security_group_id]
}

resource "aws_vpc_security_group_ingress_rule" "pg" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.data.id
  referenced_security_group_id = each.value
  from_port = 5432
  to_port   = 5432
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "kafka" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.msk.id
  referenced_security_group_id = each.value
  from_port = 9098
  to_port   = 9098
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "redis" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = each.value
  from_port = 6379
  to_port   = 6379
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "opensearch" {
  for_each                     = toset(local.clients)
  security_group_id            = aws_security_group.opensearch.id
  referenced_security_group_id = each.value
  from_port = 443
  to_port   = 443
  ip_protocol = "tcp"
}
```

Remove the old catch-all `ingress` block from `aws_security_group.data` in Part 3 when you apply this, otherwise the broad rule keeps working. Pods get node-level SG access here; for per-pod isolation, enable EKS security groups for pods or rely on the Kubernetes NetworkPolicies from Part 4.

---

## 6. Operational notes

- **Replication slots:** a stopped Debezium connector keeps its slot and WAL accumulates. `max_slot_wal_keep_size` above bounds this but invalidates the slot if exceeded (the connector then needs a re-snapshot). Alarm on `OldestReplicationSlotLag` and `TransactionLogsDiskUsage` in CloudWatch.
- **Backups and DR:** RDS automated backups (14 days) plus cross-region snapshot copy; MSK has no built-in backup, so use MSK Replicator to a DR region for topics that matter. Outbox rows are the source of truth, so a replay from the database is possible if Kafka is lost.
- **Failover test:** `aws rds reboot-db-instance --force-failover` on a non-production copy while the saga load test runs. Connectors reconnect and resume from the slot LSN; consumers dedupe replays.
- **Cost:** five Multi-AZ RDS instances, a 3-broker MSK cluster and a 6-node OpenSearch domain are the bulk of the bill. Consolidate RDS into fewer instances in non-production.
- **Compliance:** enable AWS Config rules (encrypted volumes, public access, Multi-AZ), CloudTrail data events on the secrets, and database activity streams if your auditors require them.

Not included: DR region stack (MSK Replicator, RDS cross-region replicas), Karpenter, service mesh. Next option: the ABAC policy test matrix (the policy engine has no tests yet), which is the main remaining gap.
