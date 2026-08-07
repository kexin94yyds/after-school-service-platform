ALTER TABLE leave_request
    DROP CHECK ck_leave_state,
    ADD CONSTRAINT ck_leave_state CHECK (
        (
            status = 'PENDING'
            AND reviewed_by IS NULL
            AND reviewed_at IS NULL
            AND withdrawn_by IS NULL
            AND withdrawn_at IS NULL
        )
        OR (
            status IN ('APPROVED', 'REJECTED')
            AND reviewed_by IS NOT NULL
            AND reviewed_at IS NOT NULL
            AND withdrawn_by IS NULL
            AND withdrawn_at IS NULL
        )
        OR (
            status = 'WITHDRAWN'
            AND withdrawn_by IS NOT NULL
            AND withdrawn_at IS NOT NULL
            AND (
                (
                    reviewed_by IS NULL
                    AND reviewed_at IS NULL
                )
                OR (
                    reviewed_by IS NOT NULL
                    AND reviewed_at IS NOT NULL
                )
            )
        )
    );
