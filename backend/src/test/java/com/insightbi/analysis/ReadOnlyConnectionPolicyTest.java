package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.insightbi.DatabaseStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Scoped fail-closed tests: a connection refusing readOnly never executes SQL. */
class ReadOnlyConnectionPolicyTest {
  @Test void reportsReadOnlyOrFailsWithoutLeakingDriverDetails() throws Exception {
    Connection fine=mock(Connection.class);
    when(fine.isReadOnly()).thenReturn(true);
    assertDoesNotThrow(()->ReadOnlyConnectionPolicy.require(fine));
    verify(fine).setReadOnly(true);

    Connection ignored=mock(Connection.class);
    when(ignored.isReadOnly()).thenReturn(false);
    IllegalStateException error=assertThrows(IllegalStateException.class,
      ()->ReadOnlyConnectionPolicy.require(ignored));
    assertTrue(error.getMessage().contains("只读"));

    Connection throwsError=mock(Connection.class);
    doThrow(new SQLException("secret_password_for_test")).when(throwsError).setReadOnly(true);
    var refused=assertThrows(IllegalStateException.class,
      ()->ReadOnlyConnectionPolicy.require(throwsError));
    assertFalse(refused.getMessage().contains("secret_password"));
  }

  @Test void analysisApiDoesNotRunSqlWhenDriverIgnoresReadOnly() throws Exception{
    DatabaseStore store=mock(DatabaseStore.class);
    JdbcTemplate db=mock(JdbcTemplate.class);
    FieldCatalog catalog=mock(FieldCatalog.class);
    String dataset="demo";
    when(store.dataset(dataset)).thenReturn(Map.of(
      "connection_id","read-only","sql_body","SELECT amount FROM sales"));
    when(catalog.fields(dataset)).thenReturn(List.of(
      new FieldCatalog.Field("amount","金额","amount","DECIMAL","BOTH",true,false,"ROW",null)));
    when(store.connection("read-only")).thenReturn(Map.of("id","read-only"));
    Connection conn=mock(Connection.class);
    when(conn.isReadOnly()).thenReturn(false);
    when(store.open(anyMap())).thenReturn(conn);
    AnalysisApi api=new AnalysisApi(store,db,catalog);
    assertThrows(IllegalStateException.class,()->api.query(Map.of(
      "datasetId",dataset,"measures",List.of(Map.of(
        "id","m1","fieldId","amount","aggregate","SUM")))));
    verify(store,never()).readAnalysisRows(any(),anyString(),anyList(),anyInt());
    verify(conn).close();
  }
}
