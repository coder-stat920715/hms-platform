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
