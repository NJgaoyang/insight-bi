package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Draft-only semantic candidate references, never reader or publish credentials. */
public final class SemanticDashboardDraftBindingGuard {
  private SemanticDashboardDraftBindingGuard(){}

  public static void requireDraftBindings(Object content,DatabaseStore store,JdbcTemplate db){
    if(!(content instanceof Map<?,?> root)||
       !(root.get("widgets") instanceof List<?> widgets)||widgets.size()>200)
      throw new IllegalArgumentException("分析主题组件列表无效");
    SemanticRelationApi relations=null;
    for(Object raw:widgets){
      if(!(raw instanceof Map<?,?> widget))throw new IllegalArgumentException("组件结构无效");
      if(widget.containsKey("semanticRelation"))
        throw new IllegalArgumentException("语义候选引用只能放在 analysisV2 内");
      if(!(widget.get("analysisV2") instanceof Map<?,?> analysis)||
         !analysis.containsKey("semanticRelation"))continue;
      Object rawBinding=analysis.get("semanticRelation");
      if(!(rawBinding instanceof Map<?,?> binding)||binding.size()!=3||
         !Set.of("relationId","readerId","releaseNo").equals(binding.keySet()))
        throw new IllegalArgumentException("语义候选引用格式无效");
      if(!(binding.get("relationId") instanceof String relation)||relation.isBlank()||
         relation.length()>128||
         !(binding.get("readerId") instanceof String reader)||reader.isBlank()||
         reader.length()>128||
         !(binding.get("releaseNo") instanceof Integer release)||release<1||
         !(widget.get("datasetId") instanceof String dataset)||dataset.isBlank())
        throw new IllegalArgumentException("候选版本引用字段无效");
      if(Boolean.TRUE.equals(analysis.get("usePipeline")))
        throw new IllegalArgumentException("语义候选不可同时启用加工源");
      if(relations==null)relations=new SemanticRelationApi(store,db,new FieldCatalog(store,db));
      relations.requireSealedCandidate(relation,reader,release,dataset);
    }
  }

  /** Current runtime has no dual-sided RLS; ALL candidate references are blocked. */
  public static void rejectPublishedBindings(Object content){
    if(!(content instanceof Map<?,?> root)||
       !(root.get("widgets") instanceof List<?> widgets)||widgets.size()>200)
      throw new IllegalArgumentException("发布组件列表无效");
    for(Object raw:widgets){
      if(!(raw instanceof Map<?,?> widget))continue;
      if(widget.containsKey("semanticRelation")||
         (widget.get("analysisV2") instanceof Map<?,?> analysis&&
          analysis.containsKey("semanticRelation")))
        throw new IllegalStateException("语义候选只可保存草稿；双侧行列权限及 JOIN 尚未实现，禁止发布");
    }
  }
}
