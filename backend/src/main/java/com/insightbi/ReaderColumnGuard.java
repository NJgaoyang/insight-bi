package com.insightbi;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fail-closed gate for the isolated reader surface.
 *
 * A denied source field makes the entire published analysis inaccessible if
 * any V2 widget references that field. We do not mask an already aggregated
 * result: that would leave sensitive filter/group/count calculations intact.
 *
 * When any deny rule exists for a dataset, calculated fields and processed
 * pipelines are also disallowed until lineage-aware column permissions exist.
 */
final class ReaderColumnGuard {
  private final GovernanceApi governance;
  private final JdbcTemplate db;
  ReaderColumnGuard(GovernanceApi governance,JdbcTemplate db){
    this.governance=governance;this.db=db;
  }

  boolean allowed(String readerId,Map<String,Object> publishedContent){
    if(!(publishedContent.get("widgets") instanceof List<?> widgets)||widgets.size()>200)
      return false;
    for(Object raw:widgets){
      if(!(raw instanceof Map<?,?> widget)||
         !(widget.get("analysisV2") instanceof Map<?,?> config)||
         !(widget.get("datasetId") instanceof String datasetId)||datasetId.isBlank())
        return false;
      Set<String> denied=governance.deniedFieldIds(readerId,datasetId);
      if(denied.isEmpty())continue;
      if(Boolean.TRUE.equals(config.get("usePipeline")))return false;
      // A calculated field can depend on any source column. Until the
      // expression dependency graph is ACL-aware, block all computed usage.
      Set<String> forbidden=new HashSet<>(denied);
      forbidden.addAll(db.queryForList(
        "SELECT id FROM insight_calculated_field WHERE dataset_id=?",
        String.class,datasetId));
      if(references(widget,forbidden))return false;
      // A restricted source cannot be used even through a server-side
      // mandatory row predicate (which would affect aggregates).
      for(Map<String,Object> predicate:governance.mandatoryRowFilters(readerId,datasetId))
        if(forbidden.contains(predicate.get("fieldId")))return false;
    }
    return true;
  }

  private static boolean references(Object value,Set<String> forbidden){
    if(value instanceof String text){
      for(String id:forbidden)if(text.contains(id))return true;
    }else if(value instanceof Map<?,?> map){
      for(var entry:map.entrySet()){
        if(references(entry.getKey(),forbidden)||references(entry.getValue(),forbidden))
          return true;
      }
    }else if(value instanceof Iterable<?> list){
      for(Object item:list)if(references(item,forbidden))return true;
    }
    return false;
  }
}
