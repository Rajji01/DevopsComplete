# Core rules only, so `tflint` works offline. CI adds the AWS ruleset with --config .tflint.ci.hcl
rule "terraform_required_version" { enabled = true }
rule "terraform_naming_convention" { enabled = true }
rule "terraform_unused_declarations" { enabled = true }
rule "terraform_deprecated_interpolation" { enabled = true }
