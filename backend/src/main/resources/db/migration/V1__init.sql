-- Users table
CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(180) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_users_email UNIQUE (email)
);

-- (Optional) Weather cache table for KMA responses - reserved for later use
CREATE TABLE IF NOT EXISTS weather_cache (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  grid_nx INT NOT NULL,
  grid_ny INT NOT NULL,
  base_date VARCHAR(8) NOT NULL,
  base_time VARCHAR(4) NOT NULL,
  type VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  cached_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_weather_cache_key (grid_nx, grid_ny, base_date, base_time, type)
);
