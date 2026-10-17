package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An encrypted, expiring, administrator-only consistency RECEIPT for a
 * compile-only bilateral RLS plan. It is NOT a query credential or a model
 * release snapshot, does not authorize publishing and never opens source JDBC.
 */
public final class SemanticRelationRlsPlanSnapshot {
  public static final int TTL_SECONDS=600;
  private SemanticRelationRlsPlanSnapshot(){}

  public record Verification(String status,String reason,boolean executable){}

  public static String policyEvidence(JdbcTemplate db,DatabaseStore store,
                                     String reader,String left,String right){
    // Hard-coded table names and deterministic ordering. Capture every allowed
    // value, not only counts/auth_version: even out-of-band ACL edits go stale.
    var users=db.queryForList(
      "SELECT role,enabled,auth_version FROM insight_governance_user WHERE id=?",reader);
    var deny=db.queryForList(
      "SELECT dataset_id,field_id FROM insight_reader_column_deny "+
      "WHERE user_id=? AND dataset_id IN (?,?) ORDER BY dataset_id,field_id",reader,left,right);
    var policies=db.queryForList(
      "SELECT dataset_id,field_id,allowed_value FROM insight_reader_row_policy "+
      "WHERE user_id=? AND dataset_id IN (?,?) ORDER BY dataset_id,field_id,allowed_value",
      reader,left,right);
    var values=db.queryForList(
      "SELECT dataset_id,allowed_value FROM insight_reader_row_policy_value "+
      "WHERE user_id=? AND dataset_id IN (?,?) ORDER BY dataset_id,allowed_value",
      reader,left,right);
    var evidence=new LinkedHashMap<String,Object>();
    evidence.put("reader",reader);
    evidence.put("left",left);
    evidence.put("right",right);
    evidence.put("identity",users);
    evidence.put("denies",deny);
    evidence.put("policies",policies);
    evidence.put("values",values);
    return hash(store.json(evidence));
  }

  public static String seal(DatabaseStore store,String relation,String reader,int revision,
                            String sourceHash,String policyHash,String bindingsHash,
                            Map<String,Object> binding,
                            SemanticRelationRlsPlanPreview.Preview plan){
    // Keep the exact binding inside AEAD. Never send raw permissions, SQL hash
    // or a low-entropy policy digest as a separately guessable public field.
    var canonical=new LinkedHashMap<String,Object>();
    canonical.put("version",2);
    canonical.put("relation",relation);
    canonical.put("reader",reader);
    canonical.put("revision",revision);
    canonical.put("sourceHash",sourceHash);
    canonical.put("policyHash",policyHash);
    canonical.put("bindingsHash",bindingsHash);
    canonical.put("planHash",hash(plan.sqlPreview()));
    canonical.put("binding",binding);
    canonical.put("issuedAt",Instant.now().getEpochSecond());
    return store.encrypt(store.json(canonical));
  }

  public static Map<String,Object> open(DatabaseStore store,String ticket){
    if(ticket==null||ticket.isBlank()||ticket.length()>20000)
      throw new IllegalArgumentException("计划一致性票据格式无效");
    try{
      Map<String,Object> data=store.map(store.decrypt(ticket));
      if(!data.keySet().equals(Set.of("version","relation","reader","revision","sourceHash",
          "policyHash","bindingsHash","planHash","binding","issuedAt"))||
         !(data.get("version") instanceof Number n)||n.intValue()!=2||
         !(data.get("relation") instanceof String)||
         !(data.get("reader") instanceof String)||
         !(data.get("revision") instanceof Integer version)||version<1||
         !(data.get("sourceHash") instanceof String s)||!s.matches("[0-9a-f]{64}")||
         !(data.get("policyHash") instanceof String p)||!p.matches("[0-9a-f]{64}")||
         !(data.get("bindingsHash") instanceof String b)||!b.matches("[0-9a-f]{64}")||
         !(data.get("planHash") instanceof String q)||!q.matches("[0-9a-f]{64}")||
         !(data.get("binding") instanceof Map<?,?> binding)||
         !binding.keySet().equals(Set.of("joinType","leftFieldIds","rightFieldIds"))||
         !(data.get("issuedAt") instanceof Number))
        throw new IllegalArgumentException("计划一致性票据内容异常");
      return data;
    }catch(RuntimeException invalid){
      throw new IllegalArgumentException("计划一致性票据无效或已损坏");
    }
  }

  public static boolean expired(Map<String,Object> data){
    long timestamp=((Number)data.get("issuedAt")).longValue();
    long now=Instant.now().getEpochSecond();
    return timestamp>now+30||timestamp<now-TTL_SECONDS;
  }

  public static String hash(String plain){
    try{
      byte[] bytes=MessageDigest.getInstance("SHA-256").digest(
        plain.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 不可用",e);}
  }
}
