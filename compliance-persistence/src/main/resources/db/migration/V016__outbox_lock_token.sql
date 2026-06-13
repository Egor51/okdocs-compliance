-- Fence outbox relay updates after publish-outside-transaction.
-- A stale relay can no longer mark or unlock a row after another instance has reclaimed the lease.
ALTER TABLE outbox_events ADD COLUMN lock_token UUID;
