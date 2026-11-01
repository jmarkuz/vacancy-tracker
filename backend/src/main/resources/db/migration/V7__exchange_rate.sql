-- Daily reference exchange rates (T-051, ADR-011). Base is EUR; other pairs are computed from these rows.
CREATE TABLE exchange_rate (
    base        TEXT           NOT NULL,
    quote       TEXT           NOT NULL,
    rate_date   DATE           NOT NULL,
    rate        NUMERIC(18, 8) NOT NULL,
    source      TEXT           NOT NULL,
    fetched_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (base, quote, rate_date)
);
