terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
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
