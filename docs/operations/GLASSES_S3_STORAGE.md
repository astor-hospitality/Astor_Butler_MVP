# Astor Glass: private Yandex S3

2026-10-04. Separate backend branch `codex/glasses-backend-hardening`, based on PR #11. Roma's frontend/limited SSH work remains in PR #12.

## Cloud resources

- Bucket `astor-glasses-pilot-b1gug0tmrgmsq5pfsvhs`, STANDARD, maximum 1 GiB. Public read/list/config read disabled.
- Dedicated service account `astor-glasses-storage`, no folder roles. Bucket ACL supplies basic READ/WRITE; bucket policy narrows actual access to one server-derived document GET and the pilot staff materials prefix PUT. No list, delete, document write, material read or other-scope write.
- Operator IAM subject has a separate bucket-only policy rule for maintenance. This does not grant access to the runtime service account or mobile client.
- All policy rules require HTTPS. Objects use default private ACL; never add public or additional object ACL grants, which are a separate access mechanism.
- `documents/<framed SHA-256 of tenant>/context.txt`: bounded UTF-8, initial content from `GLASSES_PILOT_KNOWLEDGE.md`.
- `materials/<framed SHA-256 of tenant and staff>/<canonical request UUID>/`: optional `input.m4a` or `input.jpg`, plus `reply.json` with answer, kind, request UUID and timestamp. Text-only requests store reply only.
- Lifecycle: expire `materials/` after one day and abort incomplete multipart uploads after one day. Documents have no lifecycle expiration. The daily lifecycle process means deletion is not exactly 24 hours after upload. [Yandex lifecycle documentation](https://yandex.cloud/en/docs/storage/operations/buckets/lifecycles).

Scope strings and credentials stay outside Git. Keys follow the length-prefixed SHA-256 implementation in `GlassesReplyCache.digest`; never derive them from client data. Hash paths isolate the configured scope; they do not replace API authentication.

## Runtime and maintenance

Set `ASTOR_GLASSES_S3_ENABLED=true`, `ASTOR_GLASSES_S3_ENDPOINT=https://storage.yandexcloud.net`, `ASTOR_GLASSES_S3_BUCKET`, `ASTOR_GLASSES_S3_ACCESS_KEY`, `ASTOR_GLASSES_S3_SECRET_KEY` in the root-owned 0600 runtime env. Default S3 is disabled. Keys are static and require explicit operator rotation/revocation; existing mobile bearer and model API key expiry are separate.

Document reads cache 60 seconds; successful archives mark storage ready 300 seconds. `documents` and `storage` capability booleans report these observed successes. S3 failure returns sanitized 503, with no raw exception, key, bucket URL or media in HTTP/log output. STT helper's isolated environment does not inherit S3/cloud credentials.

For an approved document change, preserve the previous private object, validate UTF-8/nonempty/32 KiB, upload only the server-derived `context.txt`, wait for document cache expiry and verify an authenticated informational answer. This is bounded document injection, not semantic retrieval or a general RAG index. Do not copy existing VEDAL/C3AG buckets or restaurant data without choosing an authorized source.

Release only the isolated glasses container using a versioned staging directory. Preserve prior image ID and env for rollback. Do not apply the whole frontend Compose project or overwrite Roma's SSH/admin wrappers. Keep deployment env and `docker inspect` credentials out of output.

## Verification

Live S3 smoke passed document GET and scoped reply PUT; material GET, document PUT, other-scope PUT, bucket list, material DELETE and anonymous GET returned 403. Unit coverage includes document bounds/UTF-8/cache, scoped archive keys, sanitized storage failure, model retry, UUID conflict and late provider result after cancellation.

The initial source is a training scenario. Real assigned tasks, restaurant menus, order/table/version linkage and staff authorization are not implemented by this storage integration. Full glasses HFP acceptance remains a separate signed-iPhone test.
