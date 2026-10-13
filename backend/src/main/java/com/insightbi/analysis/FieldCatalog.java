package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.*;

/** Immutable source identity: display-name changes never alter a formula's field id. */
@Service
public class FieldCatalog {
  public record Field(String id,String name,String sourceColumn,String logicalType,
                      String role,boolean numeric,boolean calculated,String phase,String expression) {}
  private final DatabaseStore store;
  private final JdbcTemplate jdbc;
  public FieldCatalog(DatabaseStore store,JdbcTemplate jdbc){this.store=store;this.jdbc=jdbc;}

  public static String typeOf(String sqlType){
    String t=sqlType==null?"":sqlType.toUpperCase(Locale.ROOT);
    if(t.contains("BOOL"))return "BOOLEAN";
    if(t.contains("DATETIME")||t.contains("TIMESTAMP"))return "DATETIME";
    if(t.equals("DATE"))return "DATE";
    if(t.contains("DECIMAL")||t.contains("NUMERIC"))return "DECIMAL";
    if(t.contains("DOUBLE")||t.contains("FLOAT")||t.contains("REAL"))return "DOUBLE";
    if(t.contains("INT")||t.contains("LONG"))return "INTEGER";
    if(t.contains("CHAR")||t.contains("TEXT")||t.contains("STRING")||t.contains("JSON"))return "STRING";
    return "UNKNOWN";
  }
  public List<Field> fields(String datasetId){
    Map<String,Object> ds=store.dataset(datasetId);
    List<Map<String,Object>> legacy=store.arr(String.valueOf(ds.get("fields_json")));
    List<Field> result=new ArrayList<>();
    int ordinal=0;
    for(Map<String,Object> f:legacy){
      String name=String.valueOf(f.get("name"));
      if(name.length()>128||name.isBlank())throw new IllegalArgumentException("无效源字段");
      String stable=UUID.nameUUIDFromBytes((datasetId+"\u0000"+name).getBytes(StandardCharsets.UTF_8)).toString();
      String type=typeOf(String.valueOf(f.getOrDefault("type","")));
      boolean numeric=Set.of("INTEGER","DOUBLE","DECIMAL").contains(type)||Boolean.TRUE.equals(f.get("numeric"));
      String role=numeric?"BOTH":"DIMENSION";
      // A concurrent /fields request may insert the same stable ID after our
      // NOT EXISTS check. The composite UNIQUE key is authoritative.
      try{
        jdbc.update("INSERT INTO insight_dataset_field(id,dataset_id,source_name,display_name,logical_type,role,ordinal) "+
                    "SELECT ?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM insight_dataset_field WHERE dataset_id=? AND source_name=?)",
                    stable,datasetId,name,name,type,role,ordinal,datasetId,name);
      }catch(DuplicateKeyException race){
        // Another request has already created the same dataset field.
      }
      ordinal++;
      // A dataset's SELECT may change the physical JDBC type without renaming
      // the selected column. Keep the persisted field ID and custom label,
      // but refresh its logical type/default role so formulas and filters stay safe.
      jdbc.update("UPDATE insight_dataset_field SET logical_type=?,role=? "+
                  "WHERE dataset_id=? AND source_name=? AND logical_type<>?",
                  type,role,datasetId,name,type);
    }
    for(Map<String,Object> f:jdbc.queryForList("SELECT id,source_name,display_name,logical_type,role FROM insight_dataset_field WHERE dataset_id=? ORDER BY ordinal",datasetId)){
      String source=String.valueOf(f.get("source_name"));
      if(legacy.stream().noneMatch(v->source.equals(v.get("name"))))continue;
      String type=String.valueOf(f.get("logical_type"));
      result.add(new Field(String.valueOf(f.get("id")),String.valueOf(f.get("display_name")),source,type,
          String.valueOf(f.get("role")),Set.of("INTEGER","DOUBLE","DECIMAL").contains(type),false,"ROW",null));
    }
    for(Map<String,Object> f:jdbc.queryForList("SELECT id,name,expression,output_type,phase FROM insight_calculated_field WHERE dataset_id=? ORDER BY name",datasetId)){
      String type=String.valueOf(f.get("output_type"));
      result.add(new Field(String.valueOf(f.get("id")),String.valueOf(f.get("name")),null,type,
          "MEASURE",Set.of("INTEGER","DOUBLE","DECIMAL").contains(type),true,
          String.valueOf(f.get("phase")),String.valueOf(f.get("expression"))));
    }
    return result;
  }
  /** Update only the presentation label; physical source and immutable field ID stay unchanged. */
  public Field renameDisplayName(String datasetId,String fieldId,String displayName){
    if(displayName==null||displayName.isBlank()||displayName.trim().length()>150)
      throw new IllegalArgumentException("展示名称必须为 1 到 150 个字符");
    List<Field> known=fields(datasetId);
    if(known.stream().noneMatch(f->f.id().equals(fieldId)&&!f.calculated()))
      throw new NoSuchElementException("可重命名的源字段不存在");
    int updated=jdbc.update("UPDATE insight_dataset_field SET display_name=? WHERE dataset_id=? AND id=?",
       displayName.trim(),datasetId,fieldId);
    if(updated!=1)throw new NoSuchElementException("源字段不存在");
    return fields(datasetId).stream().filter(f->f.id().equals(fieldId)).findFirst().orElseThrow();
  }
  public static Map<String,Field> index(List<Field> list){
    Map<String,Field> map=new LinkedHashMap<>();
    for(Field f:list)if(map.putIfAbsent(f.id(),f)!=null)throw new IllegalArgumentException("重复字段标识");
    return map;
  }
}
