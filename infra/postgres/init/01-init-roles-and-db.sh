#!/usr/bin/env bash
# infra/postgres/init/01-init-roles-and-db.sh
# Postgres initialization: create roles, database, extensions, and defaults
# This runs as the POSTGRES_USER (sb_migrator) inside the container on first startup

set -euo pipefail

# The following variables are provided by the postgres container environment:
# POSTGRES_DB=stockbroker
# POSTGRES_USER=sb_migrator
# POSTGRES_PASSWORD=<from env>

# Additional passwords from .env via environment variables
# SB_POSTGRES_APP_PASSWORD
# SB_POSTGRES_READONLY_PASSWORD

# We run this as the superuser (sb_migrator) which has CREATEDB and CREATEROLE
# since it's the POSTGRES_USER

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<-EOSQL
    -- Create roles with passwords from environment
    -- sb_migrator already exists as the bootstrap superuser (POSTGRES_USER)
    -- We just ensure it has the right attributes

    -- Create sb_app role (DML only, no DDL, no UPDATE/DELETE on ledger)
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'sb_app') THEN
            CREATE ROLE sb_app WITH LOGIN PASSWORD '${SB_POSTGRES_APP_PASSWORD}';
        ELSE
            ALTER ROLE sb_app WITH PASSWORD '${SB_POSTGRES_APP_PASSWORD}';
        END IF;
    END
    \$\$;

    -- Create sb_readonly role (SELECT only)
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'sb_readonly') THEN
            CREATE ROLE sb_readonly WITH LOGIN PASSWORD '${SB_POSTGRES_READONLY_PASSWORD}';
        ELSE
            ALTER ROLE sb_readonly WITH PASSWORD '${SB_POSTGRES_READONLY_PASSWORD}';
        END IF;
    END
    \$\$;

    -- Grant CONNECT on database to app and readonly roles
    GRANT CONNECT ON DATABASE stockbroker TO sb_app, sb_readonly;

    -- Enable required extensions
    CREATE EXTENSION IF NOT EXISTS citext;
    CREATE EXTENSION IF NOT EXISTS pgcrypto;

    -- Set database-level defaults
    ALTER DATABASE stockbroker SET timezone = 'UTC';
    ALTER DATABASE stockbroker SET statement_timeout = '30s';
    ALTER DATABASE stockbroker SET idle_in_transaction_session_timeout = '60s';

    -- Set role-specific defaults for sb_app
    ALTER ROLE sb_app SET statement_timeout = '10s';
    ALTER ROLE sb_app SET lock_timeout = '5s';
    ALTER ROLE sb_app SET idle_in_transaction_session_timeout = '30s';
    ALTER ROLE sb_app SET timezone = 'UTC';

    -- Set role-specific defaults for sb_readonly
    ALTER ROLE sb_readonly SET statement_timeout = '30s';
    ALTER ROLE sb_readonly SET lock_timeout = '10s';
    ALTER ROLE sb_readonly SET timezone = 'UTC';

    -- Grant schema usage (tables will be created by Flyway migrations later)
    GRANT USAGE ON SCHEMA public TO sb_app, sb_readonly;

    -- Default privileges for future tables created by sb_migrator (Flyway)
    ALTER DEFAULT PRIVILEGES FOR ROLE sb_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sb_app;

    ALTER DEFAULT PRIVILEGES FOR ROLE sb_migrator IN SCHEMA public
        GRANT SELECT ON TABLES TO sb_readonly;

    ALTER DEFAULT PRIVILEGES FOR ROLE sb_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO sb_app;

    ALTER DEFAULT PRIVILEGES FOR ROLE sb_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO sb_readonly;

    -- Note: Flyway migrations (Phase 4) will create tables and then:
    -- REVOKE UPDATE, DELETE ON ledger_entries, ledger_transactions FROM sb_app;
    -- This is documented here as a reminder for the migration author.
EOSQL

echo "Postgres initialization complete: roles, extensions, and defaults configured"
