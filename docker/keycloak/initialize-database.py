#!/usr/bin/env python3
"""Operator-only setup of the NEW identity PostgreSQL; never the Butler DB.

Run after `compose up -d postgres`, before Keycloak. App owns only its own
database/schema and is NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION.
SQL with credentials travels through stdin and is never logged by this script.
Existing role passwords are NOT reset. Only public-schema ownership changes;
no realm user/data rows or shared template DB/tablespaces are mutated.
"""
import json
import os
import re
import subprocess
from pathlib import Path


def query(operator, sql, check=True):
    result = subprocess.run(['docker', 'exec', '-i', 'astor_identity_postgres',
        'psql', '-X', '-A', '-t', '-q', '-v', 'ON_ERROR_STOP=1', '-U', operator,
        '-d', 'astor_identity'], input=sql, text=True, capture_output=True)
    if result.returncode and check:
        raise RuntimeError('Identity database setup rejected; operator diagnosis required (raw SQL suppressed)')
    return result


def main():
    if os.geteuid() != 0:
        raise RuntimeError('Root operator required on the approved identity host')
    passwords = {}
    for name in ['db-password', 'postgres-admin-password']:
        passwords[name] = Path('/opt/astor-identity/runtime/' + name).read_text().strip()
        if not re.fullmatch('[0-9a-f]{64}', passwords[name]):
            raise RuntimeError('Unexpected secret format; no writes performed')
    operator = 'astor_identity_admin'
    result = query(operator, 'SELECT current_user;', check=False)
    if result.returncode:
        # Initial deployed pilot used this role as bootstrap; only this new DB.
        operator = 'astor_identity'
        query(operator, 'SELECT current_user;')
    roles = set(query(operator,
        "SELECT rolname FROM pg_roles WHERE rolname IN ('astor_identity','astor_identity_admin','astor_keycloak');").stdout.split())
    statements = ['BEGIN;']
    if 'astor_identity_admin' not in roles:
        statements.append("CREATE ROLE astor_identity_admin LOGIN SUPERUSER PASSWORD '" + passwords['postgres-admin-password'] + "';")
    if 'astor_keycloak' not in roles:
        statements.append("CREATE ROLE astor_keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '" + passwords['db-password'] + "';")
    # PostgreSQL's original bootstrap role cannot lose SUPERUSER. Move only
    # identity public-schema tables/sequences to a DIFFERENT application role,
    # not REASSIGN OWNED (which also transfers shared template DB/tablespaces).
    statements += [
        """DO $$ DECLARE obj record; kind text; BEGIN
          FOR obj IN SELECT n.nspname,c.relname,c.relkind FROM pg_class c
            JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m') LOOP
            kind := CASE obj.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW'
              WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END;
            EXECUTE format('ALTER %s %I.%I OWNER TO astor_keycloak',kind,obj.nspname,obj.relname);
          END LOOP;
        END $$;""",
        'ALTER DATABASE astor_identity OWNER TO astor_keycloak;',
        'REVOKE CREATE ON SCHEMA public FROM PUBLIC;',
        'ALTER SCHEMA public OWNER TO astor_keycloak;',
        'ALTER ROLE astor_keycloak NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;']
    if 'astor_identity' in roles:
        statements.append('ALTER ROLE astor_identity NOLOGIN;')
    statements.append('COMMIT;')
    query(operator, '\n'.join(statements))
    result = query('astor_identity_admin', """
      SELECT json_build_object('app_login', rolcanlogin, 'app_superuser', rolsuper,
        'app_createdb', rolcreatedb, 'app_createrole', rolcreaterole, 'app_replication', rolreplication,
        'db_owner', pg_get_userbyid((SELECT datdba FROM pg_database WHERE datname='astor_identity')))
      FROM pg_roles WHERE rolname='astor_keycloak';
    """)
    state = json.loads(result.stdout)
    if (state['app_login'] is not True or state['db_owner'] != 'astor_keycloak' or
            any(state[k] for k in ['app_superuser', 'app_createdb', 'app_createrole', 'app_replication'])):
        raise RuntimeError('Identity application role permissions mismatch')
    print('PASS dedicated identity app role has only its own DB/schema permissions; passwords preserved.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('FAIL ' + (str(error) if isinstance(error, RuntimeError) else type(error).__name__))
        raise SystemExit(1)
