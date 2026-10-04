# Managed Kafka for the orders.events topic. 3 brokers across 3 AZs = replication factor 3 is
# possible, so a broker (or AZ) loss does not lose events. IAM auth: no passwords, pods get a role.
resource "aws_security_group" "msk" {
  name_prefix = "${var.name}-msk-"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description     = "Kafka (TLS + IAM) from EKS nodes only"
    from_port       = 9092
    to_port         = 9098
    protocol        = "tcp"
    security_groups = local.cluster_sg_ids
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_msk_configuration" "shopflow" {
  name           = "${var.name}-kafka"
  kafka_versions = ["3.6.0"]

  # safe defaults: a write is acknowledged only when 2 of 3 replicas have it
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=6
    log.retention.hours=168
  PROPERTIES
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = 14
}

resource "aws_msk_cluster" "shopflow" {
  cluster_name           = var.name
  kafka_version          = "3.6.0"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.kafka_instance_type
    client_subnets  = module.vpc.private_subnets
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = 100
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.shopflow.arn
    revision = aws_msk_configuration.shopflow.latest_revision
  }

  client_authentication {
    sasl {
      iam = true # pods authenticate with their IRSA role (aws-msk-iam-auth library in the services)
    }
  }

  encryption_info {
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

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true # broker metrics scraped by the in-cluster Prometheus
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }
}
