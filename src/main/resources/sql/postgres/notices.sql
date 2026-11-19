-- notification-service: case notifications (schema notify, SPEC-NOT-02 §4). Statement ids: notices.<name>

-- name: saveCaseRef
INSERT INTO notify.case_ref (case_id, grower_id, country_code)
VALUES (:caseId, :growerId, :countryCode)
ON CONFLICT (case_id) DO NOTHING

-- name: caseRef
SELECT case_id, grower_id, country_code
  FROM notify.case_ref
 WHERE case_id = :caseId

-- name: visibleStatuses
SELECT status_code, closes
  FROM notify.visible_status

-- name: statusLabel
SELECT label
  FROM notify.status_label
 WHERE status_code = :status AND language = :language

-- name: timeZone
SELECT time_zone
  FROM notify.country_time_zone
 WHERE country_code = :countryCode

-- name: lockWindow
SELECT window_ends_at, pending_count, pending_template, pending_status, due_at, batch_seq
  FROM notify.grower_window
 WHERE case_id = :caseId
   FOR UPDATE

-- name: saveWindow
INSERT INTO notify.grower_window (case_id, window_ends_at, pending_count, pending_template, pending_status, due_at, batch_seq)
VALUES (:caseId, :windowEndsAt, :pendingCount, :pendingTemplate, :pendingStatus, :dueAt, :batchSeq)
ON CONFLICT (case_id) DO UPDATE
   SET window_ends_at   = EXCLUDED.window_ends_at,
       pending_count    = EXCLUDED.pending_count,
       pending_template = EXCLUDED.pending_template,
       pending_status   = EXCLUDED.pending_status,
       due_at           = EXCLUDED.due_at,
       batch_seq        = EXCLUDED.batch_seq,
       updated_at       = now()

-- name: dueCases
SELECT case_id
  FROM notify.grower_window
 WHERE pending_count > 0 AND due_at <= :now
 ORDER BY due_at
 LIMIT :limit

-- name: lockDueWindow
SELECT window_ends_at, pending_count, pending_template, pending_status, due_at, batch_seq
  FROM notify.grower_window
 WHERE case_id = :caseId AND pending_count > 0 AND due_at <= :now
   FOR UPDATE SKIP LOCKED
