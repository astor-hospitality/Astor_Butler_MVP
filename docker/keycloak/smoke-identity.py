#!/usr/bin/env python3
"""Operator-only smoke for this dedicated Astor identity (never VEDAL).

Credentials/tokens stay in memory, no raw responses printed. Explicit mutation
flag creates ONE random synthetic waiter and deletes it in finally. Does not
call staff API or the application database. Requires system cryptography.
"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

BASE = 'https://c3ag.ru/astor-auth'
ISSUER = BASE + '/realms/astor'
CALLBACK = 'https://c3ag.ru/astor/staff/'
LOCAL = 'http://127.0.0.1:18880/astor-auth'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class LoginForm(HTMLParser):
    action = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'form' and attrs.get('id') == 'kc-form-login':
            self.action = attrs.get('action')


def request(url, data=None, headers=None, method=None, opener=None):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    try:
        result = (opener or urllib.request.build_opener(NoRedirect())).open(req, timeout=15)
    except urllib.error.HTTPError as error:
        result = error
    return result.code, result.headers, result.read()


def payload(data):
    return urllib.parse.urlencode(data).encode()


def require(condition, name):
    if not condition:
        raise RuntimeError(name)
    print('PASS ' + name, flush=True)


def b64url(data):
    return base64.urlsafe_b64encode(data).decode().rstrip('=')


def decode(segment):
    return base64.urlsafe_b64decode(segment + '=' * (-len(segment) % 4))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-ephemeral-user', action='store_true')
    args = parser.parse_args()
    discovery_url = ISSUER + '/.well-known/openid-configuration'
    status, _, body = request(discovery_url)
    require(status == 200, 'public HTTPS discovery')
    discovery = json.loads(body)
    require(discovery['issuer'] == ISSUER and
            all(discovery[k].startswith(ISSUER + '/') for k in
                ['authorization_endpoint', 'token_endpoint', 'jwks_uri']), 'fixed HTTPS issuer/endpoints')
    status, _, body = request(discovery_url, headers={
        'X-Forwarded-Host': 'attacker.invalid', 'X-Forwarded-Proto': 'http',
        'X-Forwarded-For': '127.0.0.1', 'Forwarded': 'host=attacker.invalid;proto=http'})
    require(status == 200 and json.loads(body)['issuer'] == ISSUER, 'forwarding spoof rejected')
    status, _, body = request(discovery['jwks_uri'])
    jwks = json.loads(body)
    require(status == 200 and bool(jwks['keys']), 'public JWKS')
    for path in ['/admin/master/console/', '/realms/master/.well-known/openid-configuration', '/health', '/metrics']:
        require(request(BASE + path)[0] == 404, 'public denied ' + path)

    def auth_query(redirect=CALLBACK, pkce=True, verifier=None):
        data = {'client_id': 'astor-staff-ui', 'response_type': 'code',
                'scope': 'openid', 'redirect_uri': redirect, 'state': secrets.token_urlsafe(24), 'prompt': 'login'}
        if pkce:
            data.update(code_challenge=b64url(hashlib.sha256((verifier or 'a' * 64).encode()).digest()),
                        code_challenge_method='S256')
        return data

    require(request(discovery['authorization_endpoint'] + '?' + urllib.parse.urlencode(
        auth_query(redirect='https://attacker.invalid/callback')))[0] == 400, 'foreign redirect rejected')
    status, headers, body = request(discovery['authorization_endpoint'] + '?' + urllib.parse.urlencode(
        auth_query(pkce=False)))
    error_params = urllib.parse.parse_qs(urllib.parse.urlsplit(headers.get('Location', '')).query)
    # 26.8 returns an OAuth invalid_request to the already validated callback.
    require(status == 400 or (status in [302, 303] and
            headers.get('Location', '').startswith(CALLBACK + '?') and
            error_params.get('error') == ['invalid_request'] and 'code' not in error_params), 'missing PKCE rejected')
    status, headers, body = request(discovery['authorization_endpoint'] + '?' + urllib.parse.urlencode(auth_query()))
    form = LoginForm()
    form.feed(body.decode())
    require(status == 200 and form.action and form.action.startswith(ISSUER + '/login-actions/'),
            'exact staff callback accepted with S256 login page')
    require('no-store' in ','.join(headers.get_all('Cache-Control', [])) and
            headers.get('Referrer-Policy') == 'no-referrer', 'auth cache/referrer headers')
    # Verify the actual login CSS path too; it must not redirect to VEDAL/HTTP.
    import re
    assets = re.findall(r'(?:href|src)="([^"]*/astor-auth/resources/[^\"]+)"', body.decode())
    require(bool(assets), 'Astor login resource URLs')
    asset = urllib.parse.urljoin('https://c3ag.ru', assets[0])
    require(asset.startswith(BASE + '/resources/') and request(asset)[0] == 200, 'public login asset delivered')
    if not args.allow_ephemeral_user:
        print('Read-only smoke complete; no account or token grant created.')
        return

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    # The bootstrap credential is read only on the VM by its authorized operator.
    admin_password = Path('/opt/astor-identity/runtime/bootstrap-password').read_text().strip()
    trusted = {'Host': 'c3ag.ru', 'X-Forwarded-Host': 'c3ag.ru',
               'X-Forwarded-Proto': 'https', 'X-Forwarded-Port': '443'}
    status, _, body = request(LOCAL + '/realms/master/protocol/openid-connect/token',
        payload({'client_id': 'admin-cli', 'grant_type': 'password',
                 'username': 'astor-bootstrap', 'password': admin_password}), trusted)
    require(status == 200, 'private operator bootstrap')
    admin_token = json.loads(body)['access_token']
    admin_headers = dict(trusted, Authorization='Bearer ' + admin_token, **{'Content-Type': 'application/json'})

    def admin(path, data=None, method='GET'):
        return request(LOCAL + '/admin/realms/astor' + path,
                       json.dumps(data).encode() if data is not None else None, admin_headers, method)

    status, _, body = admin('/clients?clientId=astor-staff-ui')
    client = json.loads(body)[0]
    require(status == 200 and client['redirectUris'] == [CALLBACK] and
            client['webOrigins'] == ['https://c3ag.ru'] and not client['directAccessGrantsEnabled'],
            'imported client exact origin/callback')
    status, _, body = admin('/users/profile')
    profile = json.loads(body)
    tenant = next(a for a in profile['attributes'] if a['name'] == 'tenant')
    require(status == 200 and tenant['permissions'] == {'view': ['admin'], 'edit': ['admin']} and
            profile.get('unmanagedAttributePolicy') != 'ENABLED', 'imported tenant admin-only')
    username = 'smoke-' + secrets.token_hex(8)
    password = secrets.token_urlsafe(32) + 'aA1!'
    user_id = None
    try:
        status, headers, _ = admin('/users', {'username': username, 'enabled': True,
            'firstName': 'Synthetic', 'lastName': 'Smoke', 'email': username + '@example.invalid',
            'emailVerified': True, 'attributes': {'tenant': ['ASTOR_SMOKE']},
            'credentials': [{'type': 'password', 'value': password, 'temporary': False}]}, 'POST')
        require(status == 201, 'synthetic account created in dedicated Astor only')
        user_id = headers['Location'].rstrip('/').rsplit('/', 1)[1]
        status, _, body = admin('/roles/astor-waiter')
        role = json.loads(body)
        require(status == 200 and admin('/users/' + user_id + '/role-mappings/realm', [role], 'POST')[0] == 204,
                'synthetic waiter role assigned')
        require(request(discovery['token_endpoint'], payload({'grant_type': 'password',
            'client_id': 'astor-staff-ui', 'username': username, 'password': password}))[0] in [400, 401],
            'staff password grant disabled')

        def authorization_code():
            verifier = secrets.token_urlsafe(48)
            query = auth_query(verifier=verifier)
            cookies = http.cookiejar.CookieJar()
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies), NoRedirect())
            status, _, body = request(discovery['authorization_endpoint'] + '?' + urllib.parse.urlencode(query), opener=opener)
            form = LoginForm()
            form.feed(body.decode())
            require(status == 200 and form.action and form.action.startswith(ISSUER + '/login-actions/'), 'real PKCE login form')
            status, headers, _ = request(form.action, payload({'username': username, 'password': password}), opener=opener)
            location = headers.get('Location', '')
            require(status in [302, 303] and location.startswith(CALLBACK + '?'), 'real authorization callback')
            params = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
            require(params.get('state') == [query['state']] and 'code' in params, 'callback state/code')
            return params['code'][0], verifier

        code, verifier = authorization_code()
        grant = {'grant_type': 'authorization_code', 'client_id': 'astor-staff-ui',
                 'code': code, 'redirect_uri': CALLBACK, 'code_verifier': verifier}
        status, headers, body = request(discovery['token_endpoint'], payload(grant), {'Origin': 'https://c3ag.ru'})
        require(status == 200 and headers.get('Access-Control-Allow-Origin') == 'https://c3ag.ru', 'real code exchange/CORS')
        tokens = json.loads(body)
        h, p, signature = tokens['access_token'].split('.')
        jwt_header, claims = json.loads(decode(h)), json.loads(decode(p))
        key = next(k for k in jwks['keys'] if k['kid'] == jwt_header['kid'])
        require(jwt_header['alg'] == 'RS256' and key['kty'] == 'RSA', 'RS256 signing key')
        public = rsa.RSAPublicNumbers(int.from_bytes(decode(key['e']), 'big'), int.from_bytes(decode(key['n']), 'big')).public_key()
        public.verify(decode(signature), (h + '.' + p).encode(), padding.PKCS1v15(), hashes.SHA256())
        require(claims['iss'] == ISSUER and 'astor-api' in claims['aud'] and
                claims['tenant'] == 'ASTOR_SMOKE' and claims['sub'] == user_id and
                'astor-waiter' in claims['realm_access']['roles'] and
                0 < claims['exp'] - int(time.time()) <= 300, 'signature/issuer/audience/tenant/role/expiry')
        require(request(discovery['token_endpoint'], payload(grant))[0] == 400, 'authorization code replay rejected')
        wrong_code, _ = authorization_code()
        grant.update(code=wrong_code, code_verifier='b' * 64)
        require(request(discovery['token_endpoint'], payload(grant))[0] == 400, 'wrong PKCE verifier rejected')
        status, headers, _ = request(discovery['end_session_endpoint'], payload({
            'id_token_hint': tokens['id_token'], 'post_logout_redirect_uri': CALLBACK}))
        require(status in [302, 303] and headers.get('Location', '').startswith(CALLBACK), 'exact logout callback')
    finally:
        if user_id:
            require(admin('/users/' + user_id, method='DELETE')[0] == 204, 'synthetic account removed')
    print('Ephemeral PKCE smoke complete; no user passwords or tokens persisted/printed.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Raw HTTP bodies/URLs can contain session code or credentials: never dump.
        print('FAIL ' + (str(error) if isinstance(error, RuntimeError) else type(error).__name__))
        raise SystemExit(1)
