-- infra/clickhouse/init/03-create-grants.sql
-- Create grants for the sb_app user
-- This runs after the server starts and users are created

-- Create the read-only role
CREATE ROLE IF NOT EXISTS sb_ro;

-- Grant SELECT on stockbroker database to the role
GRANT SELECT ON stockbroker.* TO sb_ro;

-- Grant the role to the sb_app user
GRANT sb_ro TO sb_app;
