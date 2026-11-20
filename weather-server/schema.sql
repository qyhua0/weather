-- 墨迹天气对标 App P0+P1 后端 schema (MySQL 5.7)
CREATE TABLE IF NOT EXISTS city (
  id INT AUTO_INCREMENT PRIMARY KEY,
  source_code VARCHAR(96) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL,
  name_en VARCHAR(96) DEFAULT NULL,
  admin1 VARCHAR(64) DEFAULT NULL,
  admin2 VARCHAR(64) DEFAULT NULL,
  country VARCHAR(32) DEFAULT NULL,
  province VARCHAR(32) DEFAULT NULL,
  lat DECIMAL(9,6) NOT NULL,
  lon DECIMAL(9,6) NOT NULL,
  tz VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  pop INT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS realtime (
  city_id INT PRIMARY KEY,
  cond_code INT, cond_text VARCHAR(32),
  temp DECIMAL(5,1), feels DECIMAL(5,1),
  humidity INT, pressure INT,
  wind_dir INT, wind_speed DECIMAL(6,1), wind_gust DECIMAL(6,1), wind_scale VARCHAR(8),
  visibility INT, precip DECIMAL(6,2), uv DECIMAL(4,1),
  is_day TINYINT, sunrise VARCHAR(8), sunset VARCHAR(8),
  obs_time DATETIME, updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hourly (
  id INT AUTO_INCREMENT PRIMARY KEY,
  city_id INT NOT NULL, ts DATETIME NOT NULL,
  cond_code INT, cond_text VARCHAR(32), temp DECIMAL(5,1),
  pop INT, precip_mm DECIMAL(6,2),
  wind_dir INT, wind_speed DECIMAL(6,1), wind_scale VARCHAR(8),
  humidity INT, uv DECIMAL(4,1),
  UNIQUE KEY uk_city_ts (city_id, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS daily (
  id INT AUTO_INCREMENT PRIMARY KEY,
  city_id INT NOT NULL, d DATE NOT NULL,
  cond_day INT, cond_night INT, cond_text VARCHAR(32),
  tmax DECIMAL(5,1), tmin DECIMAL(5,1), pop INT,
  wind_dir_day INT, wind_speed_day DECIMAL(6,1),
  sunrise VARCHAR(8), sunset VARCHAR(8), uv_max DECIMAL(4,1),
  UNIQUE KEY uk_city_day (city_id, d)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS aqi_realtime (
  city_id INT PRIMARY KEY,
  aqi INT, level VARCHAR(16), primary_pollutant VARCHAR(16),
  pm25 DECIMAL(8,1), pm10 DECIMAL(8,1), o3 DECIMAL(8,1),
  no2 DECIMAL(8,1), so2 DECIMAL(8,1), co DECIMAL(8,2),
  health_advice VARCHAR(255), updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS aqi_forecast (
  id INT AUTO_INCREMENT PRIMARY KEY,
  city_id INT NOT NULL, d DATE NOT NULL, aqi_max INT,
  UNIQUE KEY uk_city_aqiday (city_id, d)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alert (
  alert_id VARCHAR(64) PRIMARY KEY,
  province VARCHAR(64), title VARCHAR(255),
  type_code VARCHAR(32), level VARCHAR(16),
  issued_at VARCHAR(32), detail TEXT, url VARCHAR(255),
  updated_at DATETIME, INDEX idx_prov (province)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS life_index (
  city_id INT NOT NULL, d DATE NOT NULL, type VARCHAR(16) NOT NULL,
  score INT, status VARCHAR(16), descr VARCHAR(512),
  updated_at DATETIME,
  PRIMARY KEY (city_id, d, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS geo_cache (
  k VARCHAR(96) PRIMARY KEY,          -- nowcast "lat,lon" / radar / 通用
  payload LONGTEXT, updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_city (
  device_id VARCHAR(96) NOT NULL,
  city_id INT NOT NULL,
  sort_order INT DEFAULT 0,
  added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (device_id, city_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
