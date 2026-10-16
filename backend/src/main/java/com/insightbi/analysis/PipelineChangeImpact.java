package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Metadata-only publication impact snapshot. A fresh token prevents a stale
 * browser confirmation from applying after another dashboard is published.
 */
public final class PipelineChangeImpact {
  private PipelineChangeImpact(){}
  public record PublishedReport(String dashboardId,String dashboardName,int widgetCount){}
  public record Snapshot(int revision,List<PublishedReport> reports,String token){
    public boolean requiresAcknowledgment(){return !reports.isEmpty();}
  }
  public static Snapshot inspect(DatabaseStore store,JdbcTemplate db,String datasetId,int revision){
    if(revision<1)return new Snapshot(revision,List.of(),"");
    var rows=db.queryForList(
      "SELECT id,name,published_json FROM insight_dashboard WHERE published_json IS NOT NULL "+
      "ORDER BY id LIMIT 201");
    if(rows.size()>200)
      throw new IllegalStateException("已发布分析主题超过风险扫描上限，禁止未经检查保存");
    List<PublishedReport> reports=new ArrayList<>();
    StringBuilder fingerprint=new StringBuilder(datasetId).append(':').append(revision);
    for(var row:rows){
      String json=(String)row.get("published_json");
      if(json==null||json.isBlank())continue;
      if(json.length()>300_000)
        throw new IllegalStateException("已发布内容过大，无法确认模型变更影响");
      Map<String,Object> snapshot;
      try{snapshot=store.map(json);}
      catch(RuntimeException invalid){
        throw new IllegalStateException("已发布内容无法解析，禁止未经检查保存");
      }
      if(!(snapshot.get("widgets") instanceof List<?> widgets)||widgets.size()>150)
        throw new IllegalStateException("已发布组件内容异常，无法确认模型变更影响");
      int matched=0;
      for(Object raw:widgets){
        if(!(raw instanceof Map<?,?> widget)||!datasetId.equals(widget.get("datasetId"))||
           !(widget.get("analysisV2") instanceof Map<?,?> config)||
           !Boolean.TRUE.equals(config.get("usePipeline")))continue;
        if(config.get("pipelineRevision") instanceof Number pinned&&
           pinned.intValue()==revision)matched++;
      }
      if(matched>0){
        String id=String.valueOf(row.get("id"));
        reports.add(new PublishedReport(id,String.valueOf(row.get("name")),matched));
        // Include raw published snapshot so any change to affected report's
        // contents during confirmation invalidates the token.
        fingerprint.append('|').append(id).append(':').append(json);
      }
    }
    if(reports.isEmpty())return new Snapshot(revision,List.of(),"");
    return new Snapshot(revision,List.copyOf(reports),sha256(fingerprint.toString()));
  }
  private static String sha256(String text){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(java.security.NoSuchAlgorithmException impossible){
      throw new IllegalStateException(impossible);
    }
  }
}
