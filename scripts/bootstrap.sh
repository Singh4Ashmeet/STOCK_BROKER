#!/usr/bin/env bash
# scripts/bootstrap.sh - Generate local development secrets for stock_broker
# Usage: ./scripts/bootstrap.sh [--force]
# Idempotent: will not overwrite existing .env or keys unless --force is passed
# Works on Linux, macOS, WSL2, Git Bash

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_ROOT}/.env"
ENV_EXAMPLE="${REPO_ROOT}/.env.example"
SECRETS_DIR="${REPO_ROOT}/infra/secrets"
FORCE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --force)
            FORCE=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--force]"
            echo "  --force    Overwrite existing .env and regenerate all secrets"
            exit 0
            ;;
        *)
            echo -e "${RED}Error: Unknown option: $1${NC}"
            echo "Usage: $0 [--force]"
            exit 1
            ;;
    esac
done

# Helper functions
log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

check_command() {
    if ! command -v "$1" &> /dev/null; then
        log_error "Required command '$1' not found. Please install it and try again."
        return 1
    fi
}

generate_random_string() {
    local length="${1:-32}"
    openssl rand -base64 "$length" | tr -d "=+/" | cut -c1-"$length"
}

generate_sha256_hex() {
    # Generate SHA256 hash of input string and output as hex
    printf "%s" "$1" | openssl dgst -sha256 -binary | xxd -p -c 256
}

generate_ed25519_keypair() {
    local private_key_path="$1"
    local public_key_path="$2"

    # Generate Ed25519 private key
    openssl genpkey -algorithm Ed25519 -out "${private_key_path}" 2>/dev/null

    # Extract public key
    openssl pkey -in "${private_key_path}" -pubout -out "${public_key_path}" 2>/dev/null

    # Set restrictive permissions
    chmod 600 "${private_key_path}" 2>/dev/null || true
    chmod 644 "${public_key_path}" 2>/dev/null || true
}

# Safe sed replacement that works on NTFS (avoids sed -i rename issue)
safe_sed() {
    local pattern="$1"
    local file="$2"
    local tmp_file="${file}.tmp"
    sed "${pattern}" "${file}" > "${tmp_file}" && mv "${tmp_file}" "${file}"
}

check_prerequisites() {
    log_info "Checking prerequisites..."

    local missing=0

    # Check for openssl (required for secret generation)
    if ! check_command "openssl"; then
        missing=1
    fi

    # Check for docker
    if ! check_command "docker"; then
        missing=1
    fi

    # Check for docker compose v2
    if ! docker compose version &> /dev/null; then
        log_error "Docker Compose v2 not found. Please install Docker Compose v2 (included with Docker Desktop)."
        missing=1
    fi

    # Check for .env.example
    if [[ ! -f "${ENV_EXAMPLE}" ]]; then
        log_error ".env.example not found at ${ENV_EXAMPLE}"
        missing=1
    fi

    if [[ ${missing} -eq 1 ]]; then
        log_error "Prerequisites check failed. Please install missing tools and try again."
        exit 1
    fi

    log_success "All prerequisites satisfied"
}

create_env_file() {
    if [[ -f "${ENV_FILE}" && "${FORCE}" == "false" ]]; then
        log_warn ".env already exists. Use --force to regenerate."
        return 0
    fi

    log_info "Creating .env from .env.example..."

    # Copy the example file
    cp "${ENV_EXAMPLE}" "${ENV_FILE}"

    # Generate secrets and replace placeholders
    log_info "Generating secure random secrets..."

    # Postgres passwords
    safe_sed "s/CHANGE_ME_POSTGRES_MIGRATOR_PASSWORD/$(generate_random_string 32)/" "${ENV_FILE}"
    safe_sed "s/CHANGE_ME_POSTGRES_APP_PASSWORD/$(generate_random_string 32)/" "${ENV_FILE}"
    safe_sed "s/CHANGE_ME_POSTGRES_READONLY_PASSWORD/$(generate_random_string 32)/" "${ENV_FILE}"

    # Redis password
    safe_sed "s/CHANGE_ME_REDIS_PASSWORD/$(generate_random_string 32)/" "${ENV_FILE}"

    # ClickHouse passwords
    local ch_admin_pass
    ch_admin_pass="$(generate_random_string 32)"
    local ch_app_pass
    ch_app_pass="$(generate_random_string 32)"
    local ch_app_pass_sha256
    ch_app_pass_sha256="$(generate_sha256_hex "${ch_app_pass}")"
    # IMPORTANT: Replace SHA256 placeholder FIRST (it contains APP_PASSWORD as prefix)
    safe_sed "s/CHANGE_ME_CLICKHOUSE_APP_PASSWORD_SHA256/${ch_app_pass_sha256}/" "${ENV_FILE}"
    safe_sed "s/CHANGE_ME_CLICKHOUSE_PASSWORD/${ch_admin_pass}/" "${ENV_FILE}"
    safe_sed "s/CHANGE_ME_CLICKHOUSE_APP_PASSWORD/${ch_app_pass}/" "${ENV_FILE}"

    # Grafana admin password
    safe_sed "s/CHANGE_ME_GRAFANA_ADMIN_PASSWORD/$(generate_random_string 24)/" "${ENV_FILE}"

    # Service token
    safe_sed "s/CHANGE_ME_SERVICE_TOKEN/$(generate_random_string 48)/" "${ENV_FILE}"

    log_success ".env created with generated secrets"
}

create_secrets() {
    mkdir -p "${SECRETS_DIR}"

    local private_key="${SECRETS_DIR}/jwt_private.key"
    local public_key="${SECRETS_DIR}/jwt_public.key"

    if [[ -f "${private_key}" && "${FORCE}" == "false" ]]; then
        log_warn "JWT keypair already exists at ${SECRETS_DIR}/. Use --force to regenerate."
        return 0
    fi

    log_info "Generating Ed25519 JWT signing keypair..."
    generate_ed25519_keypair "${private_key}" "${public_key}"
    log_success "JWT keypair generated at ${SECRETS_DIR}/"
}

update_gitignore() {
    local gitignore="${REPO_ROOT}/.gitignore"
    local patterns=("infra/secrets/" ".env" ".env.*" "!/.env.example")

    for pattern in "${patterns[@]}"; do
        if ! grep -q "^${pattern}$" "${gitignore}" 2>/dev/null; then
            echo "${pattern}" >> "${gitignore}"
            log_info "Added '${pattern}' to .gitignore"
        fi
    done
}

create_gitattributes() {
    local gitattributes="${REPO_ROOT}/.gitattributes"
    local content="# Enforce LF line endings for shell scripts, YAML, SQL, and config files
*.sh    text eol=lf
*.yml   text eol=lf
*.yaml  text eol=lf
*.sql   text eol=lf
*.env*  text eol=lf
Makefile text eol=lf
"

    if [[ ! -f "${gitattributes}" ]] || ! grep -q "text eol=lf" "${gitattributes}"; then
        echo "${content}" > "${gitattributes}"
        log_info "Created .gitattributes with LF enforcement"
    fi
}

print_summary() {
    echo
    echo "========================================"
    echo "  Bootstrap Complete"
    echo "========================================"
    echo
    echo "Created/updated:"
    echo "  - .env              (gitignored, contains all secrets)"
    echo "  - infra/secrets/    (gitignored, JWT Ed25519 keypair, mode 600)"
    echo "  - .gitignore        (updated with secret patterns)"
    echo "  - .gitattributes    (LF line endings)"
    echo
    echo "Secrets generated (values NOT shown):"
    echo "  - Postgres: migrator, app, readonly passwords"
    echo "  - Redis: password"
    echo "  - ClickHouse: admin and app passwords"
    echo "  - Grafana: admin password"
    echo "  - Service-to-service token"
    echo "  - JWT Ed25519 keypair (kid: 2026-01)"
    echo
    echo "Next steps:"
    echo "  1. Run 'make up' to start the core+analytics+obs stack"
    echo "  2. Run 'make up-core' for just Postgres + Redis"
    echo "  3. Run 'make up-bus' to also start Redpanda"
    echo "  4. Access Grafana at http://127.0.0.1:3000 (user: admin)"
    echo "  5. Access Prometheus at http://127.0.0.1:9090"
    echo
    echo "To reset data volumes: 'make reset-data' (requires confirmation)"
    echo "To stop: 'make down'"
    echo
}

main() {
    echo "========================================"
    echo "  stock_broker Bootstrap"
    echo "========================================"
    echo

    cd "${REPO_ROOT}"

    check_prerequisites
    create_env_file
    create_secrets
    update_gitignore
    create_gitattributes
    print_summary
}

main "$@"