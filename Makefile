# One entry point for every approach in this repo. `make help` lists them.
SHELL := /bin/bash
.DEFAULT_GOAL := help
SF := shopflow

help: ## show this list
	@grep -E '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

## ---- build & test ----
test: ## run every unit/integration test (H2, embedded Kafka, WireMock)
	cd $(SF) && ./mvnw -B verify
test-pg: ## boot every service against real Postgres 16 (CI's postgres-smoke, locally; needs Docker)
	docker run -d --name shopflow-pg-smoke --rm -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16-alpine
	until docker exec shopflow-pg-smoke pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
	for db in orders inventory payments notifications; do \
	  docker exec shopflow-pg-smoke psql -U postgres -c "create database $$db"; done
	cd $(SF) && PG_SMOKE=true ./mvnw -B test -Dtest=PostgresSchemaValidationTest \
	  -Dsurefire.failIfNoSpecifiedTests=false; status=$$?; \
	  docker stop shopflow-pg-smoke >/dev/null; exit $$status
build: ## build all images (alpine variant)
	for s in order-service inventory-service notification-service payment-service; do \
	  docker build --build-arg SERVICE=$$s -t shopflow/$$s:local $(SF); done
build-distroless: ## build the distroless variant of one service: make build-distroless SERVICE=order-service
	docker build -f $(SF)/Dockerfile.distroless --build-arg SERVICE=$(SERVICE) -t shopflow/$(SERVICE):distroless $(SF)

## ---- run locally ----
up: ## docker compose stack (postgres, kafka, redis, keycloak, 4 services, prometheus, alertmanager, tempo, loki, grafana)
	cd $(SF) && docker compose up --build -d
down: ## stop and delete the compose stack (volumes too)
	cd $(SF) && docker compose down -v
token: ## print a JWT for alice:  TOKEN=$$(make -s token)
	@curl -s -X POST http://localhost:8180/realms/shopflow/protocol/openid-connect/token \
	  -d grant_type=password -d client_id=shopflow-web -d username=alice -d password=alice | jq -r .access_token
loadtest: ## k6 flash sale against the compose stack (needs TOKEN)
	k6 run -e TOKEN=$(TOKEN) $(SF)/loadtest/flash-sale.js

## ---- kubernetes: the approaches side by side ----
k8s-dev: ## Kustomize dev overlay (minikube)
	kubectl apply -k $(SF)/k8s/overlays/dev
k8s-prod-render: ## render the AWS prod overlay without applying
	kubectl kustomize $(SF)/k8s/overlays/prod
helm-dev: ## same service via the Helm chart
	helm upgrade --install order-service $(SF)/helm/shopflow-service -n shopflow --create-namespace -f $(SF)/helm/values/order-service.yaml
rollouts-dev: ## progressive delivery: Argo Rollouts canary/blue-green instead of plain Deployments
	kubectl apply -k $(SF)/k8s/rollouts
policies: ## install Kyverno policies (cluster must run Kyverno)
	kubectl apply -f $(SF)/k8s/policies/kyverno/

## ---- validation (what CI runs) ----
lint: ## yamllint + hadolint + gitleaks + tflint + ansible-lint (same configs as CI — Walk 64)
	yamllint -c .yamllint.yaml .
	hadolint -c .hadolint.yaml $(SF)/Dockerfile $(SF)/Dockerfile.distroless
	gitleaks git --no-banner --redact -c .gitleaks.toml .
	cd infra/terraform/aws && tflint --init >/dev/null && tflint
	cd infra/ansible && ansible-lint
validate: ## manifests, policies, alert rules, alertmanager, helm, ansible
	for o in $(SF)/k8s/overlays/*/; do kustomize build $$o | kubeconform -strict -summary -ignore-missing-schemas -; done
	kustomize build $(SF)/k8s/rollouts | kubeconform -strict -summary -schema-location default \
	  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' -
	# verify-image-signature excluded like CI: the keyless check needs a registry+signature to exist
	kyverno apply $$(ls $(SF)/k8s/policies/kyverno/*.yaml | grep -v verify-image-signature) \
	  --resource <(kustomize build $(SF)/k8s/overlays/prod)
	promtool check rules $(SF)/monitoring/alert-rules.yml $(SF)/monitoring/recording-rules.yml $(SF)/monitoring/slo-rules.yml
	promtool test rules $(SF)/monitoring/tests/*.yml
	amtool check-config $(SF)/monitoring/alertmanager.yml
	helm lint $(SF)/helm/shopflow-service -f $(SF)/helm/values/order-service.yaml
	ansible-playbook --syntax-check -i infra/ansible/inventory.example.ini infra/ansible/site.yml
	cd infra/terraform/aws && terraform fmt -check -recursive && terraform init -backend=false >/dev/null && terraform validate

## ---- infra ----
tf-plan: ## terraform plan for an environment: make tf-plan ENV=dev
	cd infra/terraform/aws && terraform init && terraform plan -var-file=environments/$(ENV).tfvars
ansible-deploy: ## old-school: the compose stack on one VM via Ansible (needs infra/ansible/inventory.ini)
	ansible-playbook -i infra/ansible/inventory.ini infra/ansible/site.yml

.PHONY: help test test-pg build build-distroless up down token loadtest k8s-dev k8s-prod-render helm-dev rollouts-dev policies lint validate tf-plan ansible-deploy
