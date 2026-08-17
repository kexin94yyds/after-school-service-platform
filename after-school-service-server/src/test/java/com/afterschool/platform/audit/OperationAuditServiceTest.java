package com.afterschool.platform.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.afterschool.platform.auth.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperationAuditServiceTest {

    private final OperationAuditMapper mapper = mock(OperationAuditMapper.class);
    private final OperationAuditService service = new OperationAuditService(
            mapper, mock(CurrentUser.class));

    @Test
    void acceptsExactlyOnePersistedAuditRecord() {
        when(mapper.insert(event())).thenReturn(1);

        assertThatCode(() -> service.record(event())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void rejectsMissingOrAmbiguousAuditWrites(int affectedRows) {
        when(mapper.insert(event())).thenReturn(affectedRows);

        assertThatThrownBy(() -> service.record(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one row");
    }

    private AuditEvent event() {
        return new AuditEvent(
                7,
                "SCHOOL_ADMIN",
                3L,
                3L,
                "POST",
                "/api/courses",
                201,
                "a".repeat(64));
    }
}
