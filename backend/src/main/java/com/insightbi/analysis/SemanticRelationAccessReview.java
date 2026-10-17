package com.insightbi.analysis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Conservative pre-publication metadata gate for ONE reader identity.
 *
 * A semantic JOIN has no reader row-predicate pushdown or source-SQL lineage
 * analysis yet. Therefore ANY denied column or row policy on EITHER dataset
 * blocks review (even if a denied column was not explicitly projected).
 * This grants no report/reader permissions and does not run any business SQL.
 */
public final class SemanticRelationAccessReview {
  public record Gate(boolean eligible,String reason,String fingerprint){}
  private SemanticRelationAccessReview(){}

  public static Gate evaluate(JdbcTemplate db,String readerId,String leftId,String rightId){
    var users=db.queryForList(
      "SELECT role,enabled,auth_version FROM insight_governance_user WHERE id=?",readerId);
    if(users.size()!=1)return new Gate(false,"读者账号不存在","");
    var u=users.get(0);
    String role=String.valueOf(u.get("role"));
    Object enabled=u.get("enabled");
    boolean active=Boolean.TRUE.equals(enabled)||
      (enabled instanceof Number n&&n.intValue()==1);
    if(!active||!Set.of("VIEWER","EDITOR").contains(role))
      return new Gate(false,"读者账号未启用或不是受支持的读者角色","");
    Object version=u.get("auth_version");
    if(!(version instanceof Number n)||n.intValue()<0)
      return new Gate(false,"读者认证版本不存在","");
    int denied=count(db,"insight_reader_column_deny",readerId,leftId,rightId);
    int rowPolicies=count(db,"insight_reader_row_policy",readerId,leftId,rightId);
    if(denied>0||rowPolicies>0)
      return new Gate(false,"存在列拒绝或行级限制；语义 JOIN 尚无安全的双侧权限下推","");
    return new Gate(true,"无双侧列拒绝和行限制；仍须独立完成报表授权及发布安全审查",
      hash(List.of("semantic-access-v1",readerId,role,String.valueOf(n.intValue()),
                   leftId,rightId,"NO_ROW_POLICY","NO_COLUMN_DENY")));
  }

  private static int count(JdbcTemplate db,String table,String reader,String left,String right){
    // The table name is a hard-coded choice; identifiers never come from a client.
    Integer count=db.queryForObject(
      "SELECT COUNT(*) FROM "+table+" WHERE user_id=? AND dataset_id IN (?,?)",
      Integer.class,reader,left,right);
    if(count==null||count<0)throw new IllegalStateException("读者授权元数据异常");
    return count;
  }
  private static String hash(List<String> values){
    try{
      MessageDigest d=MessageDigest.getInstance("SHA-256");
      for(String value:values){
        byte[] b=value.getBytes(StandardCharsets.UTF_8);
        d.update(ByteBuffer.allocate(4).putInt(b.length).array());
        d.update(b);
      }
      return HexFormat.of().formatHex(d.digest());
    }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 不可用",e);}
  }
}
