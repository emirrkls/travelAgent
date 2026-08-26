# Moderation operations (closed beta)

Reports do **not** auto-ban, hide Visits, or notify the reported user. There is no admin UI. For a small closed beta, **manual** inspection of OPEN rows can be enough if someone actually does it.

Do not put production credentials in this file. Prefer a read-only database role for inspection.

## Queue

1. List OPEN reports (authorized operator, private DB).  
2. Inspect the target user or Visit **in the product** if it still exists (public/friends visibility still applies).  
3. Decide: no action, ask the user for more context via support email, or manual intervention (content/account) using a **reviewed** procedure — not ad-hoc destructive SQL.  
4. Update `reports.status` later (`REVIEWED` / `ACTIONED` / `DISMISSED`) in a transaction you can roll back. If you are not comfortable with SQL, leave status `OPEN` and record the decision outside the DB until an admin UI exists.

Sample **read-only** inspection (no writes):

```sql
SELECT id, created_at, target_type, reason, status, target_user_id, target_visit_id
FROM reports
WHERE status = 'OPEN'
ORDER BY created_at ASC;
```

Do not `SELECT` report `details` into shared chat logs. Do not `DELETE FROM users` as a moderation shortcut unless account deletion policy explicitly applies.

## Manual SQL safety

- Use a transaction (`BEGIN` … `ROLLBACK`/`COMMIT`).  
- Prefer `SELECT` first.  
- Never run unconstrained `DELETE`/`UPDATE` without a `WHERE` on primary keys you have just selected.  
- Back up before any destructive moderation on a live beta database.

## Metrics

`phokarta.report.created` versus human review capacity. A growing OPEN backlog is an operational incident, not a silent success.
