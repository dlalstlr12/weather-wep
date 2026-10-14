-- Add 'name' column to users table to match JPA entity
ALTER TABLE users
  ADD COLUMN name VARCHAR(80) NOT NULL;
