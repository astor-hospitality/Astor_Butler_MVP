-- A guest who books through the Concierge has no chat of the Butler's bot (negotiation plan, task 7): the bill and
-- the review of such a visit are keyed by the reservation and carry no chat; nothing is sent to a chat that is not there.

ALTER TABLE guest_bills ALTER COLUMN chat_id DROP NOT NULL;
ALTER TABLE visit_reviews ALTER COLUMN chat_id DROP NOT NULL;
