-- Run once using an authorized MySQL administration client.
-- Provision a dedicated application account and password securely outside source control.
-- Grant that account SELECT, INSERT, UPDATE, CREATE on this database for local startup.
CREATE DATABASE IF NOT EXISTS vehicle_finance
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;