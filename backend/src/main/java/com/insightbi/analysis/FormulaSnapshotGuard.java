package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Protect published/history semantics of computed field dependencies.
 * Dashboard versions store field IDs, but when a saved field references another
 * calculated field the downstream expression may change indirectly. Always
 * protect the full transitive dependency closure.
 *
 * Inspect structured binding keys, never arbitrary substring occurrences in
 * descriptions, labels or Markdown comments.
 */
public final class FormulaSnapshotGuard {
  private final DatabaseStore store;
  private final JdbcTemplate db;

  public FormulaSnapshotGuard(DatabaseStore store,JdbcTemplate db){
    this.store=store;this.db=db;
  }

  public void rejectPublishedMutation(String datasetId,String formulaId){
    Set<String> affected=transitiveDependents(datasetId,formulaId);
    for(var row:db.queryForList("SELECT published_json FROM insight_dashboard")){
      if(references(row.get("published_json"),affected))
        throw new IllegalStateException("计算字段存在已发布或历史版本引用，请新建计算字段以保留历史结果");
    }
    for(var row:db.queryForList("SELECT content_json FROM insight_dashboard_version")){
      if(references(row.get("content_json"),affected))
        throw new IllegalStateException("计算字段存在已发布或历史版本引用，请新建计算字段以保留历史结果");
    }
  }

  public void rejectDeletion(String datasetId,String formulaId){
    Set<String> affected=transitiveDependents(datasetId,formulaId);
    if(affected.size()>1)
      throw new IllegalStateException("此计算字段仍被其他公式引用");
    rejectPublishedMutation(datasetId,formulaId);
    for(var row:db.queryForList("SELECT content_json FROM insight_dashboard")){
      if(references(row.get("content_json"),affected))
        throw new IllegalStateException("此计算字段仍被分析主题或发布快照引用");
    }
  }

  /** All formula IDs whose values can change when this ID changes, including it. */
  Set<String> transitiveDependents(String datasetId,String targetId){
    List<Map<String,Object>> formulas=db.queryForList(
      "SELECT id,expression FROM insight_calculated_field WHERE dataset_id=?",datasetId);
    Map<String,FieldCatalog.Field> all=FieldCatalog.index(new FieldCatalog(store,db).fields(datasetId));
    Set<String> affected=new HashSet<>();
    affected.add(targetId);
    // The compiler's actual references exclude quoted string literals that
    // happen to contain "[field-id]". It also expands multi-layer formulas.
    for(var formula:formulas){
      String id=String.valueOf(formula.get("id"));
      final FormulaCompiler.Compiled compiled;
      try{
        compiled=new FormulaCompiler(all).compile(String.valueOf(formula.get("expression")));
      }catch(IllegalArgumentException ex){
        throw new IllegalStateException("计算字段依赖无法校验，请修复元数据后再操作");
      }
      if(compiled.references().contains(targetId))affected.add(id);
    }
    return Set.copyOf(affected);
  }

  private boolean references(Object json,Set<String> impactedIds){
    if(json==null)return false;
    final Object root;
    try{root=store.map(String.valueOf(json));}
    catch(Exception error){
      // Fail closed for an unreadable historical snapshot: do not silently
      // permit a formula mutation that might break old published results.
      throw new IllegalStateException("历史快照内容无法解析，请先修复或备份");
    }
    return referencesNode(root,impactedIds);
  }

  /**
   * Legacy snapshots sometimes store formulas as text rather than a structured
   * fieldId. Match bracketed identifiers only outside quoted SQL literals,
   * escaped quote pairs and comments. Literal descriptions never count.
   */
  static boolean hasUnquotedFieldReference(String expression,Set<String> impactedIds){
    if(expression==null||impactedIds.isEmpty())return false;
    for(int i=0;i<expression.length();){
      char c=expression.charAt(i);
      // ASCII 39/34/96: single quote, double quote and backtick.
      if(c==39||c==34||c==96){
        char quote=c;
        i++;
        while(i<expression.length()){
          char next=expression.charAt(i++);
          if(next==92&&i<expression.length()){i++;continue;}
          if(next==quote){
            if(i<expression.length()&&expression.charAt(i)==quote){i++;continue;}
            break;
          }
        }
        continue;
      }
      if(c=='-'&&i+1<expression.length()&&expression.charAt(i+1)=='-'){
        i+=2;
        while(i<expression.length()&&expression.charAt(i)!=10)i++;
        continue;
      }
      if(c=='/'&&i+1<expression.length()&&expression.charAt(i+1)=='*'){
        int end=expression.indexOf("*/",i+2);
        i=end<0?expression.length():end+2;
        continue;
      }
      if(c=='['){
        int end=expression.indexOf(']',i+1);
        if(end>i+1){
          if(impactedIds.contains(expression.substring(i+1,end)))return true;
          i=end+1;
          continue;
        }
      }
      i++;
    }
    return false;
  }

  private static boolean referencesNode(Object node,Set<String> impactedIds){
    if(node instanceof List<?> list){
      for(Object child:list)if(referencesNode(child,impactedIds))return true;
    }else if(node instanceof Map<?,?> map){
      for(var entry:map.entrySet()){
        String key=String.valueOf(entry.getKey());
        Object value=entry.getValue();
        if(Set.of("fieldId","formulaId").contains(key)&&value instanceof String id&&impactedIds.contains(id))
          return true;
        if(Set.of("fieldIds","formulaIds").contains(key)&&value instanceof List<?> ids){
          for(Object id:ids)if(id instanceof String fieldId&&impactedIds.contains(fieldId))return true;
        }
        if("expression".equals(key)&&value instanceof String expression&&
           hasUnquotedFieldReference(expression,impactedIds))return true;
        if((value instanceof List<?>||value instanceof Map<?,?>)&&referencesNode(value,impactedIds))
          return true;
      }
    }
    return false;
  }
}
