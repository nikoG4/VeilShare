-- VeilShare signaling server
-- MVP intentionally requires NO persistent file database.
-- This schema is optional only if operational persistence becomes necessary.

-- If a DB is introduced, keep it minimal and create a new ADR first.

CREATE TABLE IF NOT EXISTS server_instance_metadata (
    key VARCHAR(100) PRIMARY KEY,
    value VARCHAR(500) NOT NULL
);

-- No users/files/contacts/messages tables in MVP.
-- Presence is in memory.
-- Reference code derives from public identity.
