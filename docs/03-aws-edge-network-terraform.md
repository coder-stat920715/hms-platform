# HMS Part 3: AWS Edge & Network (Terraform)

Request path: **Route 53 -> CloudFront (+WAF, Shield) -> External ALB (public subnets) -> Gateway (private) -> Internal ALB -> services**.
Terraform >= 1.6, AWS provider ~> 5.0. Untested reference: run `terraform plan` in a sandbox account first and replace placeholders (`example.com`, CIDRs, ARNs).

## Layout

```
infra/
  providers.tf  variables.tf  vpc.tf  security_groups.tf
  alb.tf  waf.tf  cloudfront.tf  route53.tf  outputs.tf
```

---

## providers.tf

```hcl
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
  backend "s3" {
    bucket         = "hms-tfstate"
    key            = "edge/terraform.tfstate"
    region         = "eu-west-1"
    dynamodb_table = "hms-tf-locks"
    encrypt        = true
  }
}

provider "aws" { region = var.region }

# CloudFront certificates and CLOUDFRONT-scope WAF must live in us-east-1
provider "aws" {
  alias  = "use1"
  region = "us-east-1"
}
```

## variables.tf

```hcl
variable "region"         { default = "eu-west-1" }
variable "domain"         { default = "hms.example.com" }
variable "api_domain"     { default = "api.hms.example.com" }
variable "origin_domain"  { default = "origin.api.hms.example.com" } # CloudFront origin (Route 53 routed)
variable "vpc_cidr"       { default = "10.20.0.0/16" }
variable "azs"            { default = ["eu-west-1a", "eu-west-1b", "eu-west-1c"] }
variable "blocked_countries" { default = ["KP", "IR", "SY"] }          # adjust to policy
variable "origin_secret"  { sensitive = true }                        # random 32+ chars, shared CF -> ALB
variable "enable_shield_advanced" { default = false }                 # needs an active subscription (~$3k/mo)
```

---

## vpc.tf

```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.8"

  name = "hms"
  cidr = var.vpc_cidr
  azs  = var.azs

  public_subnets   = ["10.20.0.0/24", "10.20.1.0/24", "10.20.2.0/24"]      # external ALB, NAT
  private_subnets  = ["10.20.10.0/24", "10.20.11.0/24", "10.20.12.0/24"]   # gateway, services, internal ALB
  database_subnets = ["10.20.20.0/24", "10.20.21.0/24", "10.20.22.0/24"]   # RDS, MSK, Redis (no internet route)

  enable_nat_gateway     = true
  one_nat_gateway_per_az = true       # HA: one NAT per AZ
  create_database_subnet_route_table = true

  enable_flow_log                      = true
  create_flow_log_cloudwatch_log_group = true
  create_flow_log_cloudwatch_iam_role  = true

  tags = { Project = "hms", DataClass = "PHI" }
}

# Keep AWS API traffic off the internet
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = concat(module.vpc.private_route_table_ids, module.vpc.database_route_table_ids)
}
```

---

## security_groups.tf

```hcl
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "external_alb" {
  name   = "hms-external-alb"
  vpc_id = module.vpc.vpc_id

  ingress {
    description     = "HTTPS from CloudFront only"
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }
  egress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.gateway.id]
  }
}

resource "aws_security_group" "gateway" {
  name   = "hms-gateway"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.external_alb.id]
  }
  egress {
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    security_groups = [aws_security_group.internal_alb.id]
  }
  egress {
    description = "Redis"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = module.vpc.database_subnets_cidr_blocks
  }
  egress {
    description = "JWKS / AWS APIs via NAT"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "internal_alb" {
  name   = "hms-internal-alb"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    security_groups = [aws_security_group.gateway.id]
  }
}

resource "aws_security_group" "services" {
  name   = "hms-services"
  vpc_id = module.vpc.vpc_id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.internal_alb.id]
  }
  # Service-to-service calls (mTLS) stay inside this SG
  ingress {
    from_port = 8080
    to_port   = 8080
    protocol  = "tcp"
    self      = true
  }
}

resource "aws_security_group" "data" {
  name   = "hms-data"
  vpc_id = module.vpc.vpc_id
  # No inline ingress: port-specific rules per data store are defined in Part 5 (data_sg.tf).
  # Debezium / Kafka Connect runs in the services SG, so it is covered there.
}
```

The catch-all rule has been removed; ingress to each data store is port-specific and lives in Part 5 (`data_sg.tf`).

---

## alb.tf

```hcl
# ----- Regional certificate for both ALBs (private CA or ACM) -----
resource "aws_acm_certificate" "regional" {
  domain_name               = var.origin_domain
  subject_alternative_names = ["internal.hms.example.com"]   # public ACM cannot issue for private TLDs like .local; point this name at the internal ALB via a private hosted zone
  validation_method         = "DNS"
  lifecycle { create_before_destroy = true }
}
# DNS validation records omitted; see route53.tf for the pattern.

# ----- External ALB (public subnets) -----
resource "aws_lb" "external" {
  name                       = "hms-external"
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = module.vpc.public_subnets
  security_groups            = [aws_security_group.external_alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = true

  access_logs {
    bucket  = aws_s3_bucket.alb_logs.id
    enabled = true
  }
}

resource "aws_lb_target_group" "gateway" {
  name        = "hms-gateway"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = module.vpc.vpc_id
  target_type = "ip"                    # EKS / ECS awsvpc

  health_check {
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
  }
  deregistration_delay = 30
}

resource "aws_lb_listener" "external_https" {
  load_balancer_arn = aws_lb.external.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.regional.arn

  # Default: reject anything that did not come through CloudFront
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "from_cloudfront" {
  listener_arn = aws_lb_listener.external_https.arn
  priority     = 10

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [var.origin_secret]
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}

# ----- Internal ALB (private subnets) -----
resource "aws_lb" "internal" {
  name                       = "hms-internal"
  load_balancer_type         = "application"
  internal                   = true
  subnets                    = module.vpc.private_subnets
  security_groups            = [aws_security_group.internal_alb.id]
  drop_invalid_header_fields = true
}

resource "aws_lb_listener" "internal_https" {
  load_balancer_arn = aws_lb.internal.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.regional.arn

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

# One target group + path rule per service
locals {
  services = {
    patient     = { path = "/api/patients/*",     priority = 10 }
    appointment = { path = "/api/appointments/*", priority = 20 }
    billing     = { path = "/api/invoices/*",     priority = 30 }
    pharmacy    = { path = "/api/pharmacy/*",     priority = 40 }
    iam         = { path = "/api/iam/*",          priority = 50 }
  }
}

resource "aws_lb_target_group" "svc" {
  for_each    = local.services
  name        = "hms-${each.key}"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = module.vpc.vpc_id
  target_type = "ip"
  health_check { path = "/actuator/health/readiness" }
}

resource "aws_lb_listener_rule" "svc" {
  for_each     = local.services
  listener_arn = aws_lb_listener.internal_https.arn
  priority     = each.value.priority

  condition {
    path_pattern { values = [each.value.path] }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.svc[each.key].arn
  }
}

resource "aws_s3_bucket" "alb_logs" {
  bucket = "hms-alb-logs-${data.aws_caller_identity.me.account_id}"
}
data "aws_caller_identity" "me" {}
```

If you use Kubernetes discovery (Part 2), the gateway can call services directly and the internal ALB path rules become optional. Keep the ALB if you want a single audited internal entry point.

---

## waf.tf

```hcl
resource "aws_wafv2_web_acl" "edge" {
  provider = aws.use1
  name     = "hms-edge"
  scope    = "CLOUDFRONT"

  default_action { allow {} }

  # 1. IP reputation
  rule {
    name     = "ip-reputation"
    priority = 10
    override_action { none {} }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  # 2. OWASP Top 10 style protections
  rule {
    name     = "common"
    priority = 20
    override_action { none {} }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "known-bad-inputs"
    priority = 30
    override_action { none {} }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "sqli"
    priority = 40
    override_action { none {} }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesSQLiRuleSet"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "sqli"
      sampled_requests_enabled   = true
    }
  }

  # 3. Edge rate limit: per-IP, 5-minute window
  rule {
    name     = "rate-limit"
    priority = 50
    action { block {} }
    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "hms-edge"
    sampled_requests_enabled   = true
  }
}

# Shield Advanced (requires a Shield Advanced subscription on the account)
resource "aws_shield_protection" "cloudfront" {
  count        = var.enable_shield_advanced ? 1 : 0
  name         = "hms-cloudfront"
  resource_arn = aws_cloudfront_distribution.api.arn
}

resource "aws_shield_protection" "external_alb" {
  count        = var.enable_shield_advanced ? 1 : 0
  name         = "hms-external-alb"
  resource_arn = aws_lb.external.arn
}
```

Start new managed rule groups in `count` (log-only) mode for a week and review sampled requests, because `CommonRuleSet` can flag legitimate large clinical payloads (body size rule). Add a scoped-down exclusion for upload paths instead of disabling the group.

---

## cloudfront.tf

```hcl
resource "aws_acm_certificate" "cf" {
  provider                  = aws.use1
  domain_name               = var.api_domain
  subject_alternative_names = [var.domain]
  validation_method         = "DNS"
  lifecycle { create_before_destroy = true }
}

resource "aws_acm_certificate_validation" "cf" {
  provider                = aws.use1
  certificate_arn         = aws_acm_certificate.cf.arn
  validation_record_fqdns = [for r in aws_route53_record.cf_validation : r.fqdn]
}

# APIs: never cache, forward everything needed for auth
resource "aws_cloudfront_cache_policy" "no_cache" {
  name        = "hms-no-cache"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 0
  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config  { cookie_behavior = "none" }
    headers_config  { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewerExceptHostHeader"
}

data "aws_cloudfront_response_headers_policy" "security" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_distribution" "api" {
  enabled         = true
  is_ipv6_enabled = true
  http_version    = "http2and3"
  aliases         = [var.api_domain, var.domain]
  web_acl_id      = aws_wafv2_web_acl.edge.arn
  price_class     = "PriceClass_100"

  origin {
    domain_name = var.origin_domain            # Route 53 latency/failover record (route53.tf)
    origin_id   = "regional-alb"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 30
    }
    custom_header {
      name  = "X-Origin-Verify"
      value = var.origin_secret
    }
  }

  # Static assets (SPA bundle) from S3 could be added as a second origin with an OAC.

  default_cache_behavior {
    target_origin_id         = "regional-alb"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = aws_cloudfront_cache_policy.no_cache.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "blacklist"
      locations        = var.blocked_countries
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cf.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  logging_config {
    bucket = aws_s3_bucket.cf_logs.bucket_domain_name
    prefix = "cf/"
  }
}

resource "aws_s3_bucket" "cf_logs" {
  bucket = "hms-cf-logs-${data.aws_caller_identity.me.account_id}"
}
```

Notes: API responses carry PHI, so caching stays off (`no_cache`). The `Authorization` header is forwarded via the AllViewer origin request policy. Because the origin only answers requests carrying `X-Origin-Verify`, hitting the ALB's DNS name directly gets a 403. Rotate the secret periodically (Secrets Manager rotation plus two-value support on the listener rule).

---

## route53.tf

```hcl
data "aws_route53_zone" "main" { name = "hms.example.com." }

# Public entry: alias to CloudFront
resource "aws_route53_record" "api" {
  for_each = toset(["A", "AAAA"])
  zone_id  = data.aws_route53_zone.main.zone_id
  name     = var.api_domain
  type     = each.value
  alias {
    name                   = aws_cloudfront_distribution.api.domain_name
    zone_id                = aws_cloudfront_distribution.api.hosted_zone_id
    evaluate_target_health = false
  }
}

# Health check per regional external ALB
resource "aws_route53_health_check" "primary" {
  fqdn              = aws_lb.external.dns_name
  port              = 443
  type              = "HTTPS"
  resource_path     = "/actuator/health/readiness"
  request_interval  = 10
  failure_threshold = 3
  measure_latency   = true
  # ALB requires the origin-verify header:
  # use a CALCULATED / CLOUDWATCH_METRIC check or add `child_health_threshold` + header via
  # `aws_route53_health_check` with `search_string` if you expose a public /health path.
}

# CloudFront's origin hostname resolves by latency, with health-based failover
resource "aws_route53_record" "origin_primary" {
  zone_id         = data.aws_route53_zone.main.zone_id
  name            = var.origin_domain
  type            = "A"
  set_identifier  = "eu-west-1"
  health_check_id = aws_route53_health_check.primary.id

  latency_routing_policy { region = "eu-west-1" }

  alias {
    name                   = aws_lb.external.dns_name
    zone_id                = aws_lb.external.zone_id
    evaluate_target_health = true
  }
}

# Second region: duplicate the stack (module "region") and add the matching record:
# resource "aws_route53_record" "origin_dr" { ... set_identifier = "us-east-1"
#   latency_routing_policy { region = "us-east-1" } ... }
# Unhealthy region drops out of DNS automatically.

# ACM DNS validation (CloudFront cert)
resource "aws_route53_record" "cf_validation" {
  for_each = {
    for o in aws_acm_certificate.cf.domain_validation_options : o.domain_name => o
  }
  zone_id         = data.aws_route53_zone.main.zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 60
  allow_overwrite = true
}
```

Route 53 health checkers can't send the `X-Origin-Verify` header, so the ALB would 403 them. Two clean fixes: (a) add a listener rule that allows `GET /health/edge` from the Route 53 health-checker IP ranges (published in `ip-ranges.json`), or (b) use a CloudWatch-alarm-based health check on `HealthyHostCount` for the gateway target group. Option (b) is simpler and avoids exposing any path.

---

## outputs.tf

```hcl
output "cloudfront_domain" { value = aws_cloudfront_distribution.api.domain_name }
output "external_alb_dns"  { value = aws_lb.external.dns_name }
output "internal_alb_dns"  { value = aws_lb.internal.dns_name }
output "vpc_id"            { value = module.vpc.vpc_id }
output "private_subnets"   { value = module.vpc.private_subnets }
```

---

## Apply order and checks

1. `terraform apply -target=module.vpc` then the rest. ACM validation takes a few minutes.
2. Verify: `curl -I https://api.hms.example.com/actuator/health` returns 401/200 through CloudFront, while `curl -k https://<alb-dns>` returns 403.
3. Fire a burst above the WAF rate limit and confirm blocks in WAF sampled requests.
4. Fail the primary gateway target group and confirm Route 53 shifts the origin record (multi-region only).

Not included: EKS/ECS cluster, RDS/MSK/ElastiCache modules, KMS keys, Secrets Manager and GuardDuty/CloudTrail baselines. Next options: the EKS cluster + Helm charts, the data tier (RDS, MSK, ElastiCache) in Terraform, or the ABAC policy test matrix.
