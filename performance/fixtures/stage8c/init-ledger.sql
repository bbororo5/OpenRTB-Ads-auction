INSERT INTO regional_campaign_budget (
    campaign_id,
    responsibility_micros,
    available_micros,
    campaign_starts_at,
    campaign_ends_at
) VALUES (
    'campaign-1',
    1000000000000,
    1000000000000,
    transaction_timestamp() - interval '1 hour',
    transaction_timestamp() + interval '7 days'
);
