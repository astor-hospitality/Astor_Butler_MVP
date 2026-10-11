#!/usr/bin/env node

import { execFileSync, execSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const args = new Map(
  process.argv.slice(2).map((arg) => {
    const [key, ...rest] = arg.replace(/^--/, "").split("=");
    return [key, rest.join("=") || "true"];
  }),
);

const baseUrl = (args.get("base-url") ?? process.env.BASE_URL ?? "http://localhost:8089").replace(/\/$/, "");
const runId = args.get("run-id") ?? `aeris-analysis-${new Date().toISOString().replace(/[-:TZ.]/g, "").slice(0, 14)}`;
const chatId = Number.parseInt(
  args.get("chat-id") ?? process.env.ANALYSIS_CHAT_ID ?? String(7_781_000_000 + Math.floor(Math.random() * 100_000)),
  10,
);
const ragChatId = Number.parseInt(args.get("rag-chat-id") ?? process.env.ANALYSIS_RAG_CHAT_ID ?? String(chatId + 1), 10);
const timeoutMs = Number.parseInt(args.get("timeout-ms") ?? process.env.ANALYSIS_TIMEOUT_MS ?? "90000", 10);
const expectAgent = (args.get("expect-agent") ?? process.env.ANALYSIS_EXPECT_AGENT ?? "true") !== "false";
const dumpChatsFile = args.get("dump-chats-file") ?? process.env.ANALYSIS_DUMP_CHATS_FILE ?? "";
const includeMessageText = (args.get("include-message-text") ?? process.env.ANALYSIS_INCLUDE_MESSAGE_TEXT ?? "false") === "true";

const psqlCommand = args.get("psql-command") ?? process.env.PSQL_COMMAND ?? "";
const databaseUrl = args.get("db-url") ?? process.env.DATABASE_URL ?? "";
const sshHost = args.get("ssh-host") ?? process.env.ASTOR_VM_HOST ?? "";
const sshUser = args.get("ssh-user") ?? process.env.ASTOR_VM_USER ?? "ubuntu";
const sshPort = args.get("ssh-port") ?? process.env.ASTOR_VM_SSH_PORT ?? "2222";
const sshKey = args.get("ssh-key") ?? process.env.ASTOR_VM_SSH_KEY ?? "";
const postgresContainer = args.get("postgres-container") ?? process.env.POSTGRES_CONTAINER ?? "astor_postgres_test";
const postgresUser = args.get("postgres-user") ?? process.env.POSTGRES_USER ?? "oracle";
const postgresDb = args.get("postgres-db") ?? process.env.POSTGRES_DB ?? "aether";
const appDir = args.get("app-dir") ?? process.env.ASTOR_VM_APP_DIR ?? "/opt/astor-butler";

const liteRubPer1k = Number.parseFloat(args.get("lite-rub-per-1k") ?? process.env.YANDEX_GPT_LITE_RUB_PER_1K_TOKENS ?? "0.20");
const qualityRubPer1k = Number.parseFloat(args.get("quality-rub-per-1k") ?? process.env.YANDEX_GPT_QUALITY_RUB_PER_1K_TOKENS ?? "0.80");

const correlationIds = {
  contact: `${runId}-contact`,
  booking: `${runId}-booking`,
  ragContact: `${runId}-rag-contact`,
  rag: `${runId}-rag`,
};

function shQuote(value) {
  return `'${String(value).replaceAll("'", "'\"'\"'")}'`;
}

function sqlQuote(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function sshArgs(remoteCommand) {
  const parts = [];
  if (sshPort) parts.push("-p", sshPort);
  if (sshKey) parts.push("-i", sshKey);
  parts.push(`${sshUser}@${sshHost}`, remoteCommand);
  return parts;
}

function runSsh(remoteCommand) {
  if (!sshHost) {
    return "";
  }
  return execFileSync("ssh", sshArgs(remoteCommand), {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

function runSql(sql) {
  if (psqlCommand) {
    return execSync(`${psqlCommand} -AtX -v ON_ERROR_STOP=1 -c ${JSON.stringify(sql)}`, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  }
  if (databaseUrl) {
    return execFileSync("psql", [databaseUrl, "-AtX", "-v", "ON_ERROR_STOP=1", "-c", sql], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  }
  if (sshHost) {
    return runSsh(
      `docker exec ${shQuote(postgresContainer)} psql -U ${shQuote(postgresUser)} -d ${shQuote(postgresDb)} -AtX -v ON_ERROR_STOP=1 -c ${shQuote(sql)}`,
    );
  }
  return "";
}

async function getJson(pathname) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const startedAt = performance.now();
  try {
    const response = await fetch(`${baseUrl}${pathname}`, {
      method: "GET",
      signal: controller.signal,
      headers: { Accept: "application/json" },
    });
    const payload = await response.json().catch(async () => ({ raw: await response.text() }));
    return {
      ok: response.ok,
      status: response.status,
      durationMs: Math.round(performance.now() - startedAt),
      payload,
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function postMessage(correlationId, body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const startedAt = performance.now();
  try {
    const response = await fetch(`${baseUrl}/api/messages`, {
      method: "POST",
      signal: controller.signal,
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ ...body, correlationId }),
    });
    const payload = await response.json().catch(async () => ({ raw: await response.text() }));
    return {
      ok: response.ok,
      status: response.status,
      durationMs: Math.round(performance.now() - startedAt),
      correlationId,
      payload,
    };
  } finally {
    clearTimeout(timeout);
  }
}

function safeParse(raw, fallback = null) {
  if (!raw) {
    return fallback;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

function usageTokens(usage) {
  if (!usage || typeof usage !== "object") {
    return 0;
  }
  const total = Number(usage.totalTokens ?? usage.total_tokens ?? usage.total ?? 0);
  if (Number.isFinite(total) && total > 0) {
    return total;
  }
  const input = Number(usage.inputTextTokens ?? usage.input_tokens ?? usage.inputTokens ?? usage.promptTokens ?? usage.prompt_tokens ?? 0);
  const output = Number(usage.completionTokens ?? usage.output_tokens ?? usage.outputTokens ?? usage.completion_tokens ?? 0);
  return (Number.isFinite(input) ? input : 0) + (Number.isFinite(output) ? output : 0);
}

function estimateRub(model, usage) {
  const totalTokens = usageTokens(usage);
  const rate = /5\.1|quality|pro/i.test(model ?? "") ? qualityRubPer1k : liteRubPer1k;
  return {
    totalTokens,
    rubPer1kTokens: rate,
    estimatedRub: Number(((totalTokens / 1000) * rate).toFixed(6)),
  };
}

function nonSecretRuntimeEnv() {
  if (!sshHost) {
    return { skipped: true, reason: "Pass --ssh-host to read VM runtime env" };
  }
  const pattern = [
    "ASTOR_MODEL_PROVIDER",
    "YANDEX_FOLDER_ID",
    "YANDEX_MODEL",
    "YANDEX_QUALITY_MODEL",
    "YANDEX_AGENT_ID",
    "YANDEX_AGENT_MODEL",
    "YANDEX_RESPONSES_BASE_URL",
    "ASTOR_UNDERSTANDING_LLM_ENABLED",
    "ASTOR_SCENARIO_REPLY_LLM_ENABLED",
    "TELEGRAM_BOT_ENABLED",
  ].join("|");
  const raw = runSsh(
    `cd ${shQuote(appDir)} && grep -E ${shQuote(`^(${pattern})=`)} .env.production 2>/dev/null || true`,
  );
  return Object.fromEntries(raw.split("\n").filter(Boolean).map((line) => {
    const index = line.indexOf("=");
    return [line.slice(0, index), line.slice(index + 1)];
  }));
}

function databaseSnapshot() {
  if (!psqlCommand && !databaseUrl && !sshHost) {
    return { skipped: true, reason: "Pass --ssh-host, --psql-command or --db-url for DB analysis" };
  }
  const ids = Object.values(correlationIds).map(sqlQuote).join(",");
  const latestTextExpression = includeMessageText
    ? "left(coalesce(tm_latest.text, ''), 360)"
    : "case when tm_latest.text is null or tm_latest.text = '' then '' else '<hidden; pass --include-message-text=true>' end";
  const sql = `
WITH semantic AS (
  SELECT jsonb_build_object(
    'vectorExtension', EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector'),
    'sources', (SELECT count(*) FROM semantic_sources WHERE active),
    'chunks', (SELECT count(*) FROM semantic_chunks),
    'embeddings', (SELECT count(*) FROM semantic_embeddings),
    'aerisSources', (
      SELECT coalesce(jsonb_agg(jsonb_build_object(
        'sourceCode', s.source_code,
        'sourceType', s.source_type,
        'title', s.title,
        'mediaAssetCode', s.media_asset_code,
        'chunks', coalesce(c.chunks, 0),
        'embeddings', coalesce(e.embeddings, 0)
      ) ORDER BY s.source_code), '[]'::jsonb)
      FROM semantic_sources s
      LEFT JOIN (
        SELECT source_id, count(*) chunks FROM semantic_chunks GROUP BY source_id
      ) c ON c.source_id = s.source_id
      LEFT JOIN (
        SELECT sc.source_id, count(*) embeddings
        FROM semantic_chunks sc
        JOIN semantic_embeddings se ON se.chunk_id = sc.chunk_id
        GROUP BY sc.source_id
      ) e ON e.source_id = s.source_id
      WHERE s.venue_code = 'AERIS' AND s.active
    )
  ) value
),
audit AS (
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'correlationId', correlation_id,
    'scenario', scenario,
    'state', state,
    'purpose', purpose,
    'provider', provider,
    'model', model,
    'generated', generated,
    'fallbackUsed', fallback_used,
    'success', success,
    'latencyMs', latency_ms,
    'agentId', metadata->'modelResponse'->>'agentId',
    'responseId', metadata->'modelResponse'->>'responseId',
    'usage', coalesce(metadata->'modelResponse'->'usage', metadata->'usage'),
    'responsePreview', left(coalesce(response_text, ''), 260),
    'createdAt', created_at
  ) ORDER BY created_at DESC), '[]'::jsonb) value
  FROM model_interaction_audit
  WHERE correlation_id IN (${ids})
),
chat_dump AS (
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'chatId', tp.chat_id,
    'telegramUserId', tp.telegram_user_id,
    'username', tp.username,
    'firstName', tp.first_name,
    'sourceChannel', tp.source_channel,
    'lastSeenAt', tp.last_seen_at,
    'messageCount', coalesce(mc.count, 0),
    'latestMessageAt', mc.latest_message_at,
    'latestMessagePreview', coalesce(mc.latest_message_preview, '')
  ) ORDER BY tp.last_seen_at DESC), '[]'::jsonb) value
  FROM telegram_profiles tp
  LEFT JOIN LATERAL (
    SELECT
      (SELECT count(*) FROM telegram_messages tm_count WHERE tm_count.chat_id = tp.chat_id) count,
      tm_latest.received_at latest_message_at,
      (${latestTextExpression}) latest_message_preview
    FROM telegram_messages tm_latest
    WHERE tm_latest.chat_id = tp.chat_id
    ORDER BY tm_latest.received_at DESC
    LIMIT 1
  ) mc ON true
),
counts AS (
  SELECT jsonb_build_object(
    'telegramProfiles', (SELECT count(*) FROM telegram_profiles),
    'telegramMessages', (SELECT count(*) FROM telegram_messages),
    'webSessions', (SELECT count(*) FROM web_sessions),
    'webMessages', (SELECT count(*) FROM web_messages),
    'tableReservationsForBookingChat', (
      SELECT count(*) FROM table_reservation_orders WHERE chat_id = ${chatId}
    ),
    'tableReservationsForRagChat', (
      SELECT count(*) FROM table_reservation_orders WHERE chat_id = ${ragChatId}
    )
  ) value
)
SELECT jsonb_build_object(
  'semantic', (SELECT value FROM semantic),
  'audit', (SELECT value FROM audit),
  'counts', (SELECT value FROM counts),
  'telegramChats', (SELECT value FROM chat_dump)
)::text;
`;
  return safeParse(runSql(sql), { skipped: true, reason: "DB query returned invalid JSON" });
}

function summarizeHttpCheck(name, check) {
  return {
    name,
    ok: check.ok,
    status: check.status,
    durationMs: check.durationMs,
    correlationId: check.correlationId,
    nextState: check.payload?.nextState,
    actions: check.payload?.actions ?? [],
    replyGenerated: check.payload?.metadata?.replyGenerated ?? null,
    replyProvider: check.payload?.metadata?.replyProvider ?? "",
    understandingIntent: check.payload?.metadata?.understandingIntent ?? "",
    understandingConfidence: check.payload?.metadata?.understandingConfidence ?? null,
    ragContextSize: Array.isArray(check.payload?.metadata?.ragContext) ? check.payload.metadata.ragContext.length : 0,
    textPreview: String(check.payload?.text ?? check.payload?.raw ?? "").slice(0, 280),
  };
}

function evaluate(report) {
  const failures = [];
  const healthStatus = report.health?.readiness?.payload?.status;
  if (healthStatus !== "UP") {
    failures.push(`readiness is ${healthStatus ?? "unknown"}`);
  }
  if (!report.http.contact?.ok) {
    failures.push("contact bootstrap failed");
  }
  if (!report.http.booking?.ok) {
    failures.push("booking message failed");
  }
  if (!report.http.rag?.ok) {
    failures.push("RAG message failed");
  }
  const bookingActions = report.http.booking?.payload?.actions ?? [];
  if (
    !bookingActions.includes("RESERVATION_CREATED")
    && !bookingActions.includes("ASK_BOOKING_DETAILS")
    && !bookingActions.includes("ASK_TABLE_SELECTION")
    && !bookingActions.includes("ASK_BOOKING_CONFIRMATION")
    && !bookingActions.includes("TABLE_SELECTION_REJECTED")
  ) {
    failures.push("booking scenario did not create/continue a reservation");
  }
  const ragMetadata = report.http.rag?.payload?.metadata ?? {};
  if (expectAgent && ragMetadata.replyProvider !== "yandex-ai-studio-agent") {
    failures.push(`expected yandex-ai-studio-agent replyProvider, got ${ragMetadata.replyProvider ?? "<empty>"}`);
  }
  if (expectAgent && ragMetadata.replyGenerated !== true) {
    failures.push("expected generated RAG reply");
  }
  if (expectAgent && (!Array.isArray(ragMetadata.ragContext) || ragMetadata.ragContext.length === 0)) {
    failures.push("expected non-empty RAG context");
  }
  const audits = report.database?.audit ?? [];
  if (!Array.isArray(audits) || audits.length === 0) {
    failures.push("model_interaction_audit has no rows for this run");
  }
  const agentAudit = audits.find((row) => row.provider === "yandex-ai-studio-agent");
  if (expectAgent && !agentAudit) {
    failures.push("model_interaction_audit has no yandex-ai-studio-agent row");
  }
  if (expectAgent && agentAudit && usageTokens(agentAudit.usage) <= 0) {
    failures.push("agent audit row has no token usage");
  }
  const semantic = report.database?.semantic ?? {};
  if (expectAgent && Number(semantic.embeddings ?? 0) <= 0) {
    failures.push("semantic_embeddings is empty");
  }
  return { ok: failures.length === 0, failures };
}

const health = {
  readiness: await getJson("/actuator/health/readiness"),
  liveness: await getJson("/actuator/health/liveness"),
};

const runtimeEnv = nonSecretRuntimeEnv();

const contact = await postMessage(correlationIds.contact, {
  channel: "TELEGRAM",
  chatId,
  externalUserId: String(chatId),
  firstName: "System Analysis",
  username: "system_analysis",
  contactPhone: "+79000000000",
  text: "",
  payload: { runId, analysis: true, bootstrap: "contact-consent" },
});

const ragContact = await postMessage(correlationIds.ragContact, {
  channel: "TELEGRAM",
  chatId: ragChatId,
  externalUserId: String(ragChatId),
  firstName: "System Analysis",
  username: "system_analysis_rag",
  contactPhone: "+79000000000",
  text: "",
  payload: { runId, analysis: true, bootstrap: "contact-consent", flow: "rag" },
});

const booking = await postMessage(correlationIds.booking, {
  channel: "TELEGRAM",
  chatId,
  externalUserId: String(chatId),
  firstName: "System Analysis",
  username: "system_analysis",
  text: "Хочу забронировать стол завтра в 20:00 на двоих, тихий стол",
  payload: { runId, analysis: true, scenario: "booking" },
});

const rag = await postMessage(correlationIds.rag, {
  channel: "TELEGRAM",
  chatId: ragChatId,
  externalUserId: String(ragChatId),
  firstName: "System Analysis",
  username: "system_analysis_rag",
  text: "Покажи винную карту и подскажи шампанское для сабража",
  payload: { runId, analysis: true, scenario: "rag-agent" },
});

const database = databaseSnapshot();
const auditCost = Array.isArray(database.audit)
  ? database.audit.map((row) => ({
    correlationId: row.correlationId,
    provider: row.provider,
    purpose: row.purpose,
    model: row.model,
    usage: row.usage,
    cost: estimateRub(row.model, row.usage),
  }))
  : [];

const report = {
  runId,
  baseUrl,
  chatId,
  ragChatId,
  correlationIds,
  runtimeEnv,
  health,
  http: { contact, ragContact, booking, rag },
  checks: [
    summarizeHttpCheck("contact", contact),
    summarizeHttpCheck("rag-contact", ragContact),
    summarizeHttpCheck("booking", booking),
    summarizeHttpCheck("rag-agent", rag),
  ],
  database,
  auditCost,
};

report.result = evaluate(report);
report.reportHash = crypto.createHash("sha256").update(JSON.stringify(report)).digest("hex").slice(0, 16);

if (dumpChatsFile) {
  const resolved = path.resolve(dumpChatsFile === "auto" ? `output/chat-state-${runId}.json` : dumpChatsFile);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  fs.writeFileSync(resolved, JSON.stringify({
    runId,
    createdAt: new Date().toISOString(),
    includeMessageText,
    counts: database.counts ?? {},
    telegramChats: database.telegramChats ?? [],
  }, null, 2));
  report.chatDumpFile = resolved;
}

console.log(JSON.stringify(report, null, 2));

if (!report.result.ok) {
  process.exitCode = 1;
}
