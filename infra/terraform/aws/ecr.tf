# One repository per service. Immutable tags: a pushed SHA can never be overwritten, so what
# the prod overlay names is exactly what runs. Scan on push for CVEs.
resource "aws_ecr_repository" "service" {
  for_each = toset(["order-service", "inventory-service", "notification-service", "payment-service"])

  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.environment != "prod"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# keep the last 30 images, so the registry does not grow forever but rollbacks stay possible
resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last 30 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 30
      }
      action = { type = "expire" }
    }]
  })
}
