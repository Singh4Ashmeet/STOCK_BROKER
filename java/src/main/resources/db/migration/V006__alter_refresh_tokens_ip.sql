-- V006__alter_refresh_tokens_ip.sql
-- Alter refresh_tokens ip column to varchar(45) to match JPA entity mapping

ALTER TABLE refresh_tokens ALTER COLUMN ip TYPE varchar(45);
