# Makefile - stock_broker local development commands
# All commands assume docker compose v2 is available
# Run 'make help' to see all targets

.PHONY: help bootstrap up up-core up-bus down ps logs reset-data proto lint test \
        proto-lint proto-format proto-format-check proto-build proto-breaking proto-compile-check \
        verify-infra ci-lint java-build java-test java-verify java-docker

# Default target
help:
	@echo "stock_broker - Local Development Commands"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@echo "Infrastructure targets:"
	@echo "  bootstrap       Run ./scripts/bootstrap.sh to generate .env and secrets"
	@echo "  up              Start core + analytics + obs stack (Postgres, Redis, ClickHouse, Prometheus, Grafana)"
	@echo "  up-core         Start core stack only (Postgres, Redis)"
	@echo "  up-bus          Start core + analytics + obs + bus stack (adds Redpanda)"
	@echo "  down            Stop and remove all containers"
	@echo "  ps              Show status of all services"
	@echo "  logs            Follow logs for all services (Ctrl-C to exit)"
	@echo "  reset-data      Destroy all data volumes (requires confirmation)"
	@echo "  verify-infra    Verify infrastructure health and configuration (run after 'make up')"
	@echo ""
	@echo "Protobuf targets (step 0.2):"
	@echo "  proto              Run lint, format-check, build, and compile-check"
	@echo "  proto-lint         Run buf lint on proto files"
	@echo "  proto-format       Format proto files in-place (buf format -w)"
	@echo "  proto-format-check Check proto formatting (buf format -d --exit-code)"
	@echo "  proto-build        Build proto files (buf build)"
	@echo "  proto-breaking     Check for breaking changes against main branch"
	@echo "  proto-compile-check Compile proto with grpcio-tools (Python) to verify"
	@echo ""
	@echo "Development targets:"
	@echo "  lint         Run linters (proto-lint + shellcheck)"
	@echo "  ci-lint      Check GitHub Action pins and run actionlint"
	@echo "  test         Run tests (no code exists yet)"
	@echo ""
	@echo "Java API targets:"
	@echo "  java-build       Build Java API with Maven (compile + test)"
	@echo "  java-test        Run Java unit + integration tests"
	@echo "  java-verify      Full Java verify (build, test, spotbugs, checkstyle, jacoco)"
	@echo "  java-docker      Build Java Docker image"
	@echo ""
	@echo "Examples:"
	@echo "  make bootstrap       # First-time setup"
	@echo "  make up              # Start full observability stack"
	@echo "  make up-core         # Minimal stack for Java development"
	@echo "  make verify-infra    # Verify infrastructure after 'make up'"
	@echo "  make ps              # Check service health"
	@echo "  make down            # Stop everything"

# Detect repository root for Docker volume mounts
REPO_ROOT := $(shell pwd)

# Bootstrap: generate .env and secrets
bootstrap:
	@./scripts/bootstrap.sh

# Start core + analytics + obs (default full stack)
up: bootstrap
	@docker compose --profile core --profile analytics --profile obs up -d
	@echo ""
	@echo "Stack started. Access points:"
	@echo "  Grafana:    http://127.0.0.1:3000  (admin / $$(grep SB_GRAFANA_ADMIN_PASSWORD .env | cut -d= -f2))"
	@echo "  Prometheus: http://127.0.0.1:9090"
	@echo "  Postgres:   127.0.0.1:5432  (user: sb_app)"
	@echo "  Redis:      127.0.0.1:6379"
	@echo "  ClickHouse: 127.0.0.1:8123 (HTTP) / 9000 (Native)"
	@echo ""
	@echo "Run 'make ps' to check health status"
	@echo "Run 'make verify-infra' to verify infrastructure"

# Start core only (Postgres + Redis)
up-core: bootstrap
	@docker compose --profile core up -d
	@echo ""
	@echo "Core stack started:"
	@echo "  Postgres: 127.0.0.1:5432"
	@echo "  Redis:    127.0.0.1:6379"

# Start core + analytics + obs + bus (adds Redpanda)
up-bus: bootstrap
	@docker compose --profile core --profile analytics --profile obs --profile bus up -d
	@echo ""
	@echo "Full stack with message bus started:"
	@echo "  Redpanda: 127.0.0.1:9092"

# Stop all services
down:
	@docker compose --profile core --profile analytics --profile obs --profile bus --profile app down

# Show service status
ps:
	@docker compose --profile core --profile analytics --profile obs --profile bus --profile app ps

# Follow logs
logs:
	@docker compose --profile core --profile analytics --profile obs --profile bus --profile app logs -f

# Reset data volumes (destructive - requires confirmation)
reset-data:
	@echo "WARNING: This will DESTROY ALL DATA in Postgres, Redis, ClickHouse, Prometheus, Grafana, and Redpanda volumes."
	@echo "This action cannot be undone."
	@read -p "Type 'yes' to confirm: " confirm; \
	if [ "$$confirm" = "yes" ]; then \
		docker compose --profile core --profile analytics --profile obs --profile bus --profile app down -v; \
		echo "Volumes destroyed. Run 'make up' to recreate."; \
	else \
		echo "Aborted."; \
	fi

# ============================================================
# Protobuf targets (step 0.2) - run via Docker
# ============================================================

# Run all proto checks (lint, format-check, build, compile-check)
proto: proto-lint proto-format-check proto-build proto-compile-check

# Run buf lint
proto-lint:
	docker run --rm -v "$(REPO_ROOT):/repo" bufbuild/buf:1.73.0 lint /repo/proto

# Format proto files in-place
proto-format:
	docker run --rm -v "$(REPO_ROOT):/repo" bufbuild/buf:1.73.0 format -w /repo/proto

# Check formatting (fails on diff)
proto-format-check:
	docker run --rm -v "$(REPO_ROOT):/repo" bufbuild/buf:1.73.0 format -d --exit-code /repo/proto

# Build proto files
proto-build:
	docker run --rm -v "$(REPO_ROOT):/repo" bufbuild/buf:1.73.0 build /repo/proto

# Check for breaking changes against main branch
proto-breaking:
	@if git rev-parse --verify main >/dev/null 2>&1; then \
		docker run --rm -v "$(REPO_ROOT):/repo" bufbuild/buf:1.73.0 breaking /repo/proto --against '.git#branch=main,subdir=proto'; \
	else \
		echo "No 'main' branch found or no commits yet; skipping breaking change check"; \
	fi

# Compile proto with grpcio-tools (Python) to verify it compiles
proto-compile-check:
	docker run --rm -v "$(REPO_ROOT):/repo" python:3.12-slim bash -c "pip install -q grpcio-tools && mkdir -p /tmp/proto_gen && python -m grpc_tools.protoc --proto_path=/repo/proto --python_out=/tmp/proto_gen --grpc_python_out=/tmp/proto_gen /repo/proto/stockbroker/market/v1/market.proto && echo 'Python proto compilation successful'"

# ============================================================
# Infrastructure verification
# ============================================================

verify-infra:
	@bash scripts/verify-infra.sh

# ============================================================
# Development targets
# ============================================================

lint: proto-lint
	docker run --rm -v "$(REPO_ROOT):/repo" koalaman/shellcheck:v0.10.0 /repo/scripts/bootstrap.sh

ci-lint:
	@bash scripts/check-action-pins.sh
	docker run --rm -v "$(REPO_ROOT):/repo" -w /repo rhysd/actionlint:1.7.12 -color

test:
	@echo "test target: no application code exists yet"

# ============================================================
# Java API targets
# ============================================================

java-build:
	@cd java && ./mvnw clean compile -B

java-test:
	@cd java && ./mvnw test -B

java-verify:
	@cd java && ./mvnw verify -B

java-docker:
	@docker build -t stockbroker/api:dev -f java/Dockerfile java/
