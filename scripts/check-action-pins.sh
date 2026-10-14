#!/usr/bin/env bash
# scripts/check-action-pins.sh - Verify all GitHub Actions are pinned by commit SHA
# Fails if any uses: in .github/workflows (excluding local ./ paths) is not @<40 hex>

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
log_fail() { echo -e "${RED}[FAIL]${NC} $*"; }
log_info() { echo -e "${YELLOW}[INFO]${NC} $*"; }

WORKFLOW_DIR=".github/workflows"
FAIL_COUNT=0

if [[ ! -d "$WORKFLOW_DIR" ]]; then
    log_fail "Workflow directory $WORKFLOW_DIR not found"
    exit 1
fi

log_info "Checking GitHub Action pins in $WORKFLOW_DIR..."

# Find all uses: lines in workflow files
# Exclude local actions (starting with ./)
# Check that each uses: has @ followed by 40 hex characters
while IFS= read -r line; do
    file=$(echo "$line" | cut -d: -f1)
    uses_line=$(echo "$line" | cut -d: -f3- | sed 's/^[[:space:]]*uses:[[:space:]]*//' | sed 's/^[[:space:]]*//')

    # Skip local actions
    if echo "$uses_line" | grep -q '^\./'; then
        continue
    fi

    # Extract the action and ref (everything after @)
    if echo "$uses_line" | grep -q '@'; then
        ref=$(echo "$uses_line" | sed 's/.*@//' | sed 's/[[:space:]]*#.*//' | sed 's/[[:space:]]*$//')
        action=$(echo "$uses_line" | sed 's/@.*//')

        # Check if ref is a 40-character hex SHA
        if echo "$ref" | grep -qE '^[a-f0-9]{40}$'; then
            log_pass "$file: $action@$ref (pinned by SHA)"
        else
            log_fail "$file: $action@$ref (NOT pinned by 40-char SHA)"
            ((FAIL_COUNT++))
        fi
    else
        log_fail "$file: $uses_line (no @ ref at all)"
        ((FAIL_COUNT++))
    fi
done < <(grep -rn '^[[:space:]]*uses:' "$WORKFLOW_DIR"/*.yml 2>/dev/null || true)

if [[ $FAIL_COUNT -eq 0 ]]; then
    log_pass "All GitHub Actions are pinned by commit SHA"
    exit 0
else
    log_fail "Found $FAIL_COUNT unpinned action(s)"
    exit 1
fi
