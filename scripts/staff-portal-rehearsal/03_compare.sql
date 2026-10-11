-- Runs inside the scratch "expected" base (built from the target changelog on an empty base).
-- rehearsal_report.copy_log holds the DATABASECHANGELOG rows of the copy, rehearsal_report.valid_sums
-- the validCheckSum values declared in the changelog. Nothing here touches the copy.
\pset pager off
\pset null '-'

CREATE OR REPLACE VIEW rehearsal_report.compared AS
SELECT coalesce(e.id, c.id) AS id,
       coalesce(e.author, c.author) AS author,
       CASE
         WHEN c.id IS NULL THEN 'PENDING'
         WHEN e.id IS NULL THEN 'UNKNOWN'
         WHEN c.filename <> e.filename THEN 'PATH DIFFERS'
         WHEN c.md5sum IS NOT DISTINCT FROM e.md5sum THEN 'same'
         WHEN EXISTS (SELECT 1 FROM rehearsal_report.valid_sums v
                      WHERE v.id = c.id AND (v.md5sum = c.md5sum OR lower(v.md5sum) IN ('any', '1:any')))
           THEN 'MD5 DIFFERS, ACCEPTED'
         ELSE 'MD5 DIFFERS'
       END AS verdict,
       c.orderexecuted AS copy_order,
       e.orderexecuted AS expected_order,
       c.filename AS copy_path,
       e.filename AS expected_path,
       c.md5sum AS copy_md5,
       e.md5sum AS expected_md5,
       c.exectype AS copy_exectype,
       e.exectype AS expected_exectype,
       c.liquibase AS copy_liquibase,
       c.dateexecuted AS copy_executed_at
FROM public.databasechangelog e
FULL JOIN rehearsal_report.copy_log c ON c.id = e.id AND c.author = e.author;

\echo
\echo == Every changeset: the copy against the target changelog ==
\echo    same = id, author, path and MD5SUM are equal
\echo    PENDING = in the target changelog, not in the copy: the update will run it
\echo    UNKNOWN = in the copy, not in the target changelog
\echo    PATH DIFFERS = Liquibase would take it for a new changeset and run it again
\echo    MD5 DIFFERS, ACCEPTED = the stored sum is one of the validCheckSum values of the changeset
\echo    MD5 DIFFERS = validation fails; a finding to record, not to clear
SELECT coalesce(expected_order, 0) AS target_no, coalesce(copy_order::text, '-') AS copy_no, id, author, verdict
FROM rehearsal_report.compared
ORDER BY coalesce(expected_order, 100000 + copy_order), id;

\echo
\echo == Rows that are not "same", in full ==
SELECT id, author, verdict, copy_path, expected_path, copy_md5, expected_md5, copy_exectype, expected_exectype
FROM rehearsal_report.compared
WHERE verdict <> 'same'
ORDER BY coalesce(expected_order, 100000 + copy_order), id;

\echo
\echo == Order: changesets present on both sides whose relative position differs ==
SELECT id, copy_rank, expected_rank
FROM (SELECT id,
             row_number() OVER (ORDER BY copy_order, id) AS copy_rank,
             row_number() OVER (ORDER BY expected_order, id) AS expected_rank
      FROM rehearsal_report.compared
      WHERE copy_order IS NOT NULL AND expected_order IS NOT NULL) ranked
WHERE copy_rank <> expected_rank
ORDER BY expected_rank;

\echo
\echo == What wrote the rows of the copy ==
SELECT coalesce(liquibase, '-') AS liquibase_version,
       coalesce(split_part(md5sum, ':', 1), '-') AS checksum_format,
       exectype,
       count(*) AS changesets,
       min(dateexecuted) AS first_run,
       max(dateexecuted) AS last_run
FROM rehearsal_report.copy_log
GROUP BY 1, 2, 3
ORDER BY first_run;

\echo
\echo == validCheckSum values declared in the target changelog ==
SELECT v.id, v.md5sum AS declared, c.md5sum AS stored_in_copy, e.md5sum AS computed_now
FROM rehearsal_report.valid_sums v
LEFT JOIN rehearsal_report.copy_log c ON c.id = v.id
LEFT JOIN public.databasechangelog e ON e.id = v.id
ORDER BY v.id;
