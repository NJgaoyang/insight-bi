package com.insightbi.analysis;

import com.insightbi.DatabaseStore;

/**
 * Preserve legacy datasets when an administrator only updates metadata,
 * but require strict AST/function validation for new or changed source SQL.
 *
 * Previously saved SQL never bypasses the V2 query's own ReadOnlySqlGuard.
 */
public final class DatasetSqlChangePolicy {
  private DatasetSqlChangePolicy() {}

  public static String forSave(String proposedSql,String savedSql){
    String proposed=DatabaseStore.safeSql(proposedSql);
    if(savedSql!=null&&proposed.equals(DatabaseStore.safeSql(savedSql)))
      return proposed; // old dataset unchanged: no unannounced compatibility break
    return ReadOnlySqlGuard.validate(proposed);
  }
}
