# Learning / dev: everything single-AZ and small. Roughly $150/month. Destroy when not in use.
environment         = "dev"
node_instance_types = ["t3.medium"]
node_group_size     = { min = 1, desired = 2, max = 3 }
db_instance_class   = "db.t4g.micro"
db_multi_az         = false
cache_node_type     = "cache.t4g.micro"
kafka_instance_type = "kafka.t3.small"
domain_name         = "dev.shop.example.com"
