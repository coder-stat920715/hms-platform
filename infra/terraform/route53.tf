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
