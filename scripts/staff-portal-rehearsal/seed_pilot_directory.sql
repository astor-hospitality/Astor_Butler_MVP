-- A staff directory entered by hand, in the shape the glasses pilot uses (GLASSES_VOICE_TASKS.md):
-- staff_id is a short name chosen by the operator, not the subject of a Keycloak account.
-- Synthetic people only.
INSERT INTO astor_staff_members (tenant, staff_id, display_name, role, active, shift_open)
VALUES ('AERIS', 'pilot-manager', 'Pilot Manager', 'MANAGER', TRUE, TRUE),
       ('AERIS', 'pilot-hostess', 'Pilot Hostess', 'HOSTESS', TRUE, TRUE),
       ('AERIS', 'pilot-waiter-1', 'Pilot Waiter One', 'WAITER', TRUE, TRUE),
       ('AERIS', 'pilot-waiter-2', 'Pilot Waiter Two', 'WAITER', TRUE, TRUE)
ON CONFLICT (tenant, staff_id) DO NOTHING;
