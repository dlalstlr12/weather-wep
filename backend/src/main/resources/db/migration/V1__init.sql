-- Users table (PostgreSQL)
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(180) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_users_email UNIQUE (email)
);
