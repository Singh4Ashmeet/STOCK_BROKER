-- V008__add_full_name_to_users.sql
-- Add full_name column to users table for registration

ALTER TABLE users ADD COLUMN IF NOT EXISTS full_name text;

-- Add index for potential lookups (optional)
-- CREATE INDEX IF NOT EXISTS idx_users_full_name ON users (full_name);