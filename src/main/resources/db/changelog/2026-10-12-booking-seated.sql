-- «Гость сел» (#151): the moment the guest sat down, stamped by the hostess button «🪑 Гость сел» in the staff chat
-- or by the booking's state in the venue's own system (Saby, ASTOR_SABY_SEATED_STATES). Goes with the order status
-- SEATED; null for every order seated before this column existed or never seated at all.

ALTER TABLE table_reservation_orders
    ADD COLUMN IF NOT EXISTS seated_at TIMESTAMP WITH TIME ZONE;
