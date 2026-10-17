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
