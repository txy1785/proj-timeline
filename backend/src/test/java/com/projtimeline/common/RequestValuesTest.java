package com.projtimeline.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestValuesTest {
    @Test
    void parsesBoundaryValues() {
        Map<String, Object> body = Map.of("date", "2026-10-18", "days", "0.1", "id", 9);
        assertThat(RequestValues.date(body, "date")).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(RequestValues.decimal(body, "days")).isEqualByComparingTo(new BigDecimal("0.1"));
        assertThat(RequestValues.longValue(body, "id")).isEqualTo(9);
    }

    @Test
    void rejectsInvalidDate() {
        assertThatThrownBy(() -> RequestValues.date(Map.of("date", "18/10/2026"), "date"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    void rejectsIntegerOverflowAsBadRequest() {
        assertThatThrownBy(() -> RequestValues.intValue(Map.of("value", Long.MAX_VALUE), "value"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超出整数范围");
    }
}
