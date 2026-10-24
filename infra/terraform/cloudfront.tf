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
