-- The operator's step from STAFF_PORTAL_RUNBOOK.md, section 2: the first manager is a subject that
-- already exists in the Astor Keycloak, bound to one venue. Values come in as psql variables
-- (-v tenant=... -v staff_id=... -v display_name=...), never typed into this file.
INSERT INTO astor_staff_members (tenant, staff_id, display_name, role, active)
VALUES (:'tenant', :'staff_id', :'display_name', 'MANAGER', TRUE);
