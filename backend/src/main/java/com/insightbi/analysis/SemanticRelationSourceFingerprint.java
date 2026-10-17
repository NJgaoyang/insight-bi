package com.insightbi.analysis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Approval fingerprint of both source datasets. Does not access external JDBC,
 * persist SQL or authorize any runtime reader query.
 * Only display names and field order are deliberately ignored.
 */
public final class SemanticRelationSourceFingerprint {
  private SemanticRelationSourceFingerprint(){}
  public static String compute(Map<String,Object> left,Map<String,Object> right,
                               List<FieldCatalog.Field> leftFields,
                               List<FieldCatalog.Field> rightFields){
    try{
      MessageDigest d=MessageDigest.getInstance("SHA-256");
      add(d,"semantic-relation-source-v1");
      append(d,left,leftFields);
      append(d,right,rightFields);
      return HexFormat.of().formatHex(d.digest());
    }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 不可用",e);}
  }
  /**
   * New approvals with a persisted connection also pin its effective endpoint,
   * account and encrypted credential version. The stored approval hash changes
   * on any connection edit, even when connection_id itself stays unchanged.
   * Do NOT return this hash or the credential outside the server.
   */
  public static String computeWithConnection(Map<String,Object> left,
      Map<String,Object> right,List<FieldCatalog.Field> leftFields,
      List<FieldCatalog.Field> rightFields,Map<String,Object> connection){
    if(connection==null)throw new IllegalStateException("连接元数据未登记");
    String connectionId=required(connection,"id");
    if(!connectionId.equals(left.get("connection_id"))||
       !connectionId.equals(right.get("connection_id")))
      throw new IllegalStateException("关系双方没有使用相同的登记连接");
    try{
      MessageDigest d=MessageDigest.getInstance("SHA-256");
      add(d,"semantic-relation-source-v2-connection");
      add(d,compute(left,right,leftFields,rightFields));
      for(String key:List.of("id","engine","host","db_name","username","credential"))
        add(d,required(connection,key));
      Object port=connection.get("port");
      if(!(port instanceof Number p)||p.intValue()<1||p.intValue()>65535)
        throw new IllegalStateException("连接端口元数据无效");
      add(d,Integer.toString(p.intValue()));
      return HexFormat.of().formatHex(d.digest());
    }catch(NoSuchAlgorithmException e){
      throw new IllegalStateException("SHA-256 不可用",e);
    }
  }
  private static String required(Map<String,Object> data,String key){
    if(!(data.get(key) instanceof String s)||s.isBlank())
      throw new IllegalStateException("数据库连接配置缺少必要项: "+key);
    return s;
  }

  private static void append(MessageDigest d,Map<String,Object> source,
                             List<FieldCatalog.Field> fields){
    if(source==null||fields==null)throw new IllegalArgumentException("关系源快照不存在");
    for(String key:List.of("id","connection_id","sql_body")){
      if(!(source.get(key) instanceof String value)||value.isBlank())
        throw new IllegalArgumentException("关系数据集来源定义不完整");
      add(d,value);
    }
    List<FieldCatalog.Field> physical=fields.stream().filter(f->!f.calculated())
      .sorted(Comparator.comparing(FieldCatalog.Field::id)).toList();
    if(physical.isEmpty()||physical.size()>500)
      throw new IllegalArgumentException("关系物理字段目录缺失或超限");
    add(d,Integer.toString(physical.size()));
    Set<String> used=new HashSet<>();
    for(var f:physical){
      if(f.id()==null||f.sourceColumn()==null||f.logicalType()==null||
         f.role()==null||f.sourceColumn().isBlank()||!used.add(f.id()))
        throw new IllegalArgumentException("关系物理字段定义损坏");
      add(d,f.id());
      add(d,f.sourceColumn());
      add(d,f.logicalType());
      add(d,f.role());
      add(d,Boolean.toString(f.hidden()));
    }
  }
  private static void add(MessageDigest d,String value){
    byte[] utf8=value.getBytes(StandardCharsets.UTF_8);
    d.update(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
    d.update(utf8);
  }
}
