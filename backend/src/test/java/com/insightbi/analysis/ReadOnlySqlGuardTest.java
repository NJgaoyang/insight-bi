package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ReadOnlySqlGuardTest {
  @Test void ordinaryScalarAndAggregateFunctionsRemainAvailable() {
    String sql="SELECT city, DATE_FORMAT(order_date, '%Y-%m') AS month_key, "+
        "ROUND(SUM(revenue),2) AS total FROM sales GROUP BY city, DATE_FORMAT(order_date, '%Y-%m')";
    assertEquals(sql,ReadOnlySqlGuard.validate(sql));
    assertEquals("SELECT 'GET_LOCK(1)' AS literal_value FROM sales",
        ReadOnlySqlGuard.validate("SELECT 'GET_LOCK(1)' AS literal_value FROM sales"));
  }

  @Test void dangerousOrUnknownCallsAreDeniedEvenInsideSelect() {
    assertThrows(IllegalArgumentException.class,
        ()->ReadOnlySqlGuard.validate("SELECT GET_LOCK('report',10) FROM sales"));
    assertThrows(IllegalArgumentException.class,
        ()->ReadOnlySqlGuard.validate("SELECT DATABASE()"));
    assertThrows(IllegalArgumentException.class,
        ()->ReadOnlySqlGuard.validate("SELECT custom_side_effect(revenue) FROM sales"));
    assertThrows(IllegalArgumentException.class,
        ()->ReadOnlySqlGuard.validate("SELECT * FROM sales FOR UPDATE"));
    assertThrows(IllegalArgumentException.class,
        ()->ReadOnlySqlGuard.validate("SELECT * FROM sales; DELETE FROM sales"));
  }
}
