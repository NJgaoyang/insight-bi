package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** No JDBC traffic; only the dedicated DataForge test-cluster policy. */
class DataForgeStarRocksTestAccessTest {
  @Test void recognizedDataForgeTestFeAllowsExplicitlyConfirmedAdmin(){
    assertDoesNotThrow(()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://172.17.0.12:9030/ods","root",true));
    assertDoesNotThrow(()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://81.69.15.136:9030/ods","root",true));
  }

  @Test void adminRequiresBothConfirmationAndExactTestFe(){
    assertThrows(IllegalStateException.class,()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://172.17.0.12:9030/ods","root",false));
    assertThrows(IllegalStateException.class,()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://172.17.0.12:9031/ods","root",true));
    assertThrows(IllegalStateException.class,()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://172.17.0.120:9030/ods","root",true));
    assertThrows(IllegalStateException.class,()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://other-host:9030/ods","admin",true));
    assertThrows(IllegalStateException.class,()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://81.69.15.136:9030.evil/ods","root",true));
  }

  @Test void nonAdminAllowedWithSeparateMandatoryReadOnlyGate(){
    assertDoesNotThrow(()->DataForgeStarRocksTestAccess.require(
      "jdbc:mysql://dataforge-test:9030/ods","insight_ro",false));
    assertThrows(IllegalArgumentException.class,()->DataForgeStarRocksTestAccess.require(
      "jdbc:other://172.17.0.12:9030/ods","root",true));
  }
}
