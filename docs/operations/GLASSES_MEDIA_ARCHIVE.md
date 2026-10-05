# Astor Glass — private media archive

Updated 2026-10-05. This route stores session media independently of assist, training photo checkpoints and staff task evidence.

## Contract

POST /api/glasses/media over HTTPS, with the expiring pilot bearer bound to a server-side venue/staff scope.

| Header | Value |
| --- | --- |
| Content-Type | image/jpeg, audio/mp4 or video/mp4 |
| Content-Length | 1 through 67,108,864 bytes; required |
| X-Glasses-File-Id | Canonical lowercase UUID, immutable for one file |
| X-Glasses-Session-Id | Canonical lowercase session UUID |
| X-Content-SHA256 | Standard SHA-256 of exact uploaded bytes, lowercase hex |

The body is binary, without base64 or JSON. Minimal JPEG/MP4 container signatures are checked; the archive does not decode, transcribe or analyze its contents.

Only after both object and commit record are stored does the server return:

    {"fileId":"…","sessionId":"…","sha256":"…","size":1234,"archived":true}

The response contains no object key, bucket, public URL or model answer. Invalid authentication is rejected before body access. Maximum one upload in flight and ten attempts/minute; 429 includes Retry-After. Missing length returns 411, oversize 413, changed digest 400 HASH_MISMATCH. A client may retry a failed upload, but cannot acknowledge/delete its local copy without a matching receipt.

## Durable retry and storage

The server hashes the bearer scope into a private prefix. Original archive objects and archive-receipt.json are separate from input media/reply.json of assist. No client-chosen S3 path is accepted.

On retry the server reads only the existing commit record. Exact file ID, session, digest, size and MIME return the same receipt without rewriting media. Changed context/content for a committed ID returns 409 FILE_ID_CONFLICT. A failed or unreadable commit returns 503 and does not acknowledge the file.

One pilot process serializes check/write/commit. Receipts survive a process restart while retained. This is not a multi-writer transaction: horizontal scaling requires coordination. IDs must never be reused after expiration. The pilot lifecycle expires materials after one day, with asynchronous daily cleanup; reference documents use a separate prefix.

The storage service account has PUT on its material prefix and GET only on its reference document and scoped archive-receipt.json. Existing raw media GET, bucket listing, DELETE and another scope's PUT are denied. All allowed policy statements require SecureTransport. Cloud keys stay on the server.

Configuration defaults off: ASTOR_GLASSES_MEDIA_ARCHIVE_ENABLED=true plus configured S3 enables capabilities.mediaArchive. It reports enabled and maxFileBytes; each upload still requires a successful storage commit.

## Phone behavior

The explicitly opened phoneCapture session stores the prepared analysis JPEG and finished AAC question in a private queue. Standby recognition buffers are not included. Files are held until a live Glass charging event or explicit manual upload. Case charging is insufficient.

Background uploads use file-backed NSURLSession tasks. The queue checks all receipt fields, keeps copies on interruption/failure, and freezes the session inventory for retry. This archive receipt does not replace a training photoReceipt or complete a staff task.

SDK memory import for original stored photos/video requires Wi-Fi and supported Hotspot signing. Personal Team Debug has that path disabled. See clients/ios-glasses/docs/DOCK_ARCHIVE.md.

## Verified release

Isolated release v4-media-archive promoted on 2026-10-05. Previous v3 retained stopped for rollback. Gateway template and active config gained an exact /api/glasses/media location with a 64 MiB limit and request buffering off; nginx validation and reload passed.

- Maven package: 347 tests, zero failures/errors/skips.
- Candidate: JPEG, AAC and MP4 receipts; same-file retry; changed session 409; SHA mismatch 400; identical receipt after restarting only the candidate.
- Candidate preserved voice assist 200, silence 400 NO_SPEECH, JPEG training receipt 200.
- Public HTTPS: three MIME types, exact receipt comparison, retry, conflict, SHA rejection and missing bearer 401; valid synthetic MP4 larger than 5 MiB uploaded successfully.
- Live storage policy: receipt PUT/GET 200; an existing raw object's GET, list, delete and other-scope PUT 403.

Synthetic server tests do not prove wear greeting, wake phrase, physical camera/audio or charge-triggered phone uploads. Those require separate iPhone/glasses evidence. The companion source is reviewable; licensed SDK and private access files are excluded.
