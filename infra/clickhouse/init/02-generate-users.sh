#!/usr/bin/env bash
# infra/clickhouse/init/02-generate-users.sh
# Generate ClickHouse users config from template using environment variables
# This runs as part of docker-entrypoint-initdb.d before the server starts

set -euo pipefail

TEMPLATE_FILE="/etc/clickhouse-server/users.d/app-user.xml.template"
OUTPUT_FILE="/etc/clickhouse-server/users.d/app-user.xml"

if [[ ! -f "${TEMPLATE_FILE}" ]]; then
    echo "Template file ${TEMPLATE_FILE} not found"
    exit 1
fi

# Use sed to substitute environment variables
# Only substitute the variables that exist in the template
app_hash="${SB_CLICKHOUSE_APP_PASSWORD_SHA256}"

# Create the output file by replacing placeholders
sed -e "s|\${SB_CLICKHOUSE_APP_PASSWORD_SHA256}|${app_hash}|g" \
    "${TEMPLATE_FILE}" > "${OUTPUT_FILE}"

echo "Generated ClickHouse users config at ${OUTPUT_FILE}"
cat "${OUTPUT_FILE}"