/* Public Keycloak client: authorization code + PKCE. Tokens stay in memory, never localStorage. */
(function () {
  "use strict";
  const apiBase = new URL((window.AstorStaffConfig || {}).apiBase || "/", window.location.origin);
  if (apiBase.origin !== window.location.origin) throw new Error("Staff API must be same-origin behind the server proxy");
  let config, accessToken;
  const base64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const random = () => base64url(crypto.getRandomValues(new Uint8Array(32)));
  const redirect = () => window.location.origin + window.location.pathname;
  async function timedRequest(url, options, read) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try { return await read(await fetch(url, { ...options, signal: controller.signal })); }
    catch (error) {
      if (error.name === "AbortError") throw new Error("Сервер не ответил вовремя. Запрос не подтверждён.");
      throw error;
    } finally { clearTimeout(timer); }
  }
  async function request(path, options = {}) {
    const url = new URL(path, apiBase);
    if (url.origin !== window.location.origin) throw new Error("Invalid staff API origin");
    return timedRequest(url, { ...options, cache: "no-store", credentials: "omit",
      headers: { ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(accessToken ? { Authorization: "Bearer " + accessToken } : {}), ...(options.headers || {}) } }, async (response) => {
    if (!response.ok) {
      const error = new Error(response.status === 401 ? "Сессия истекла. Войдите снова." : "Сервер отклонил запрос.");
      error.status = response.status;
      try { error.code = (await response.json()).error.code; } catch (e) { /* no response details in logs */ }
      throw error;
    }
    return response.status === 204 ? null : response.json();
    });
  }
  async function initialize() {
    config = await request("/api/staff/login-config");
    if (!config.enabled) throw new Error("Кабинет ещё не включён на сервере Astor.");
    const issuer = new URL(config.issuer);
    if (issuer.protocol !== "https:" || issuer.username || issuer.password || issuer.search || issuer.hash)
      throw new Error("Неверная настройка входа Astor.");
    config.issuer = issuer.href.replace(/\/$/, "");
    const query = new URLSearchParams(window.location.search);
    if (query.has("error")) {
      history.replaceState(null, "", redirect());
      sessionStorage.removeItem("astor-staff-pkce");
      throw new Error("Вход отменён или отклонён.");
    }
    if (!query.has("code")) return false;
    const pending = JSON.parse(sessionStorage.getItem("astor-staff-pkce") || "null");
    sessionStorage.removeItem("astor-staff-pkce");
    const code = query.get("code"), state = query.get("state");
    history.replaceState(null, "", redirect());
    if (!pending || pending.state !== state || pending.issuer !== config.issuer || Date.now() - pending.at > 300000)
      throw new Error("Проверка входа не прошла. Начните вход заново.");
    const tokens = await timedRequest(config.issuer + "/protocol/openid-connect/token", { method: "POST",
      credentials: "omit", cache: "no-store", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "authorization_code", client_id: config.clientId,
        redirect_uri: redirect(), code, code_verifier: pending.verifier }) }, async (response) => {
      if (!response.ok) throw new Error("Не удалось завершить вход Astor.");
      return response.json();
    });
    if (!tokens.access_token) throw new Error("Вход не выдал доступ к Astor.");
    accessToken = tokens.access_token;
    return true;
  }
  async function login() {
    if (!config || !config.enabled) throw new Error("Вход на сервере не настроен.");
    const verifier = random(), state = random();
    const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
    sessionStorage.setItem("astor-staff-pkce", JSON.stringify({ verifier, state, issuer: config.issuer, at: Date.now() }));
    const url = new URL(config.issuer + "/protocol/openid-connect/auth");
    url.search = new URLSearchParams({ client_id: config.clientId, response_type: "code", scope: "openid",
      redirect_uri: redirect(), state, code_challenge: challenge, code_challenge_method: "S256" });
    window.location.assign(url.href);
  }
  function logout() {
    accessToken = null;
    sessionStorage.removeItem("astor-staff-pkce");
    // Also end the Keycloak SSO session; no ID/refresh token is persisted or placed in the URL.
    window.location.assign(config.issuer + "/protocol/openid-connect/logout");
  }
  window.AstorStaffApi = { request, initialize, login, logout };
})();
