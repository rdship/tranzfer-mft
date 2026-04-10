CREATE TABLE IF NOT EXISTS crypto_key_pairs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_name VARCHAR(100) UNIQUE NOT NULL,
    public_key TEXT NOT NULL,
    private_key TEXT NOT NULL
);
