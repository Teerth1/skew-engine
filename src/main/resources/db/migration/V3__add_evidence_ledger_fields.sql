CREATE TABLE IF NOT EXISTS news_articles (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    source VARCHAR(200),
    url VARCHAR(1000),
    published_at TIMESTAMP NOT NULL,
    summary VARCHAR(2000),
    provider VARCHAR(50),
    raw_sentiment VARCHAR(50),
    data_category VARCHAR(50) DEFAULT 'NARRATIVE_EVENT',
    is_deterministic BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_news_symbol ON news_articles (symbol);
CREATE INDEX IF NOT EXISTS idx_news_published_at ON news_articles (published_at);

-- Add fields if the table was already created by Hibernate ddl-auto:
ALTER TABLE news_articles ADD COLUMN IF NOT EXISTS data_category VARCHAR(50) DEFAULT 'NARRATIVE_EVENT';
ALTER TABLE news_articles ADD COLUMN IF NOT EXISTS is_deterministic BOOLEAN DEFAULT FALSE;
