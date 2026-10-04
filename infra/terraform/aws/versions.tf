terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state: shared between people and CI, versioned, and locked (S3 native locking, TF >= 1.11).
  # Create the bucket once by hand (or in a tiny bootstrap module); never commit terraform.tfstate.
  backend "s3" {
    bucket       = "shopflow-terraform-state-REPLACE-ME"
    key          = "prod/shopflow.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "shopflow"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
