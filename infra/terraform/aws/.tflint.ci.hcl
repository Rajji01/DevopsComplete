# CI config: core rules + the AWS provider ruleset (instance types, deprecated arguments, invalid ARNs...).
# Plugin download needs GitHub API access (GITHUB_TOKEN in CI).
plugin "aws" {
  enabled = true
  version = "0.49.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
rule "terraform_required_version" { enabled = true }
rule "terraform_naming_convention" { enabled = true }
rule "terraform_unused_declarations" { enabled = true }
rule "terraform_deprecated_interpolation" { enabled = true }
