CREATE TABLE IF NOT EXISTS strategies (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    strategy VARCHAR(255),
    ticker VARCHAR(255),
    opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(255) DEFAULT 'OPEN',
    net_cost DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS legs (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT REFERENCES strategies(id) ON DELETE CASCADE,
    option_type VARCHAR(255),
    strike_price DOUBLE PRECISION,
    expiration DATE,
    entry_price DOUBLE PRECISION,
    quantity INTEGER
);

CREATE TABLE IF NOT EXISTS gex_snapshots (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(255),
    timestamp TIMESTAMP,
    spot_price DOUBLE PRECISION,
    zero_gamma DOUBLE PRECISION,
    call_wall DOUBLE PRECISION,
    put_wall DOUBLE PRECISION,
    strike_data_json TEXT
);

CREATE TABLE IF NOT EXISTS command_logs (
    id BIGSERIAL PRIMARY KEY,
    command VARCHAR(255),
    user_id VARCHAR(255),
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    success BOOLEAN DEFAULT TRUE
);
