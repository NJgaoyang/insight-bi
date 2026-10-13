package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Validated V2 semantic request -> parameterized SQL plan. All identifiers come from FieldCatalog. */
public final class AnalysisQueryPlanner {
  public record Plan(String sql,List<Object> params,List<Map<String,Object>> columns,int limit) {}
  private record Metric(String sql,String alias,String type) {}
  private final Map<String,FieldCatalog.Field> fields;
  private final FormulaCompiler formulas;

  public AnalysisQueryPlanner(List<FieldCatalog.Field> known){
    fields=FieldCatalog.index(known);formulas=new FormulaCompiler(fields);
  }
  public Plan compile(String sourceSql,Map<String,Object> request){
    if(request==null)throw new IllegalArgumentException("查询参数不能为空");
    String from=ReadOnlySqlGuard.validate(sourceSql);
    List<Map<String,Object>> dims=objects(request.get("dimensions"),3,"维度");
    List<Map<String,Object>> measures=objects(request.get("measures"),5,"指标");
    if(dims.isEmpty()&&measures.isEmpty())throw new IllegalArgumentException("至少选择一个维度或指标");
    List<String> select=new ArrayList<>(),group=new ArrayList<>(),where=new ArrayList<>(),having=new ArrayList<>();
    Map<String,String> sortable=new HashMap<>();
    Map<String,Metric> metrics=new HashMap<>();
    List<Map<String,Object>> columns=new ArrayList<>();
    List<Object> params=new ArrayList<>();
    Set<String> aliases=new HashSet<>(),selectedDimensions=new HashSet<>();

    int index=0;
    for(Map<String,Object> d:dims){
      String id=required(d,"fieldId");
      if(!selectedDimensions.add(id))throw new IllegalArgumentException("同一维度不能重复选择: "+id);
      FieldCatalog.Field f=field(id);
      String phase=f.calculated()?formulas.compile(f.expression()).phase():"ROW";
      if(!phase.equals("ROW"))throw new IllegalArgumentException("聚合计算字段不能作为分组维度");
      String expr=ref(f);
      String granularity=string(d.get("granularity"),"");
      if(!granularity.isBlank()){
        if(!Set.of("DATE","DATETIME").contains(f.logicalType()))throw new IllegalArgumentException("只有日期字段支持时间粒度");
        expr=switch(granularity){
          case "year"->"DATE_FORMAT("+expr+",'%Y')";
          case "month"->"DATE_FORMAT("+expr+",'%Y-%m')";
          case "day"->"DATE_FORMAT("+expr+",'%Y-%m-%d')";
          default->throw new IllegalArgumentException("不支持的日期粒度");
        };
      }
      String alias="d"+(++index);
      select.add(expr+" AS "+FormulaCompiler.quote(alias));
      group.add(expr);sortable.put(id,alias);
      columns.add(Map.of("id",id,"name",alias,"label",f.name(),"type",granularity.isBlank()?f.logicalType():"STRING","kind","dimension"));
    }
    for(Map<String,Object> m:measures){
      String id=required(m,"id");
      if(!id.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")||!aliases.add(id)||sortable.containsValue(id))
        throw new IllegalArgumentException("指标 ID 不合法或重复");
      String fieldId=required(m,"fieldId");
      String agg=string(m.get("aggregate"),"SUM").toUpperCase(Locale.ROOT);
      if(!Set.of("SUM","AVG","COUNT","MIN","MAX").contains(agg))throw new IllegalArgumentException("不支持的聚合方式");
      if(m.get("distinct")!=null&&!(m.get("distinct") instanceof Boolean))
        throw new IllegalArgumentException("去重选项必须是布尔值");
      boolean distinct=Boolean.TRUE.equals(m.get("distinct"));
      if(distinct&&(!agg.equals("COUNT")||fieldId.equals("*")))
        throw new IllegalArgumentException("当前仅 COUNT(字段) 支持去重计数");
      String expression,type;
      if(fieldId.equals("*")&&agg.equals("COUNT")){expression="COUNT(*)";type="INTEGER";}
      else{
        FieldCatalog.Field f=field(fieldId);
        if(f.calculated()&&f.phase().equals("AGGREGATE")){
          FormulaCompiler.Compiled c=formulas.compile(f.expression());
          if(!agg.equals("SUM"))throw new IllegalArgumentException("聚合公式不能再次选择聚合方式");
          expression=c.sql();type=c.outputType();
        }else{
          if(Set.of("SUM","AVG").contains(agg)&&!f.numeric())
            throw new IllegalArgumentException("SUM/AVG 指标必须是数值字段");
          expression=agg+"("+(distinct?"DISTINCT ":"")+ref(f)+")";
          type=agg.equals("COUNT")?"INTEGER":(agg.equals("MIN")||agg.equals("MAX"))?f.logicalType():"DECIMAL";
        }
      }
      select.add(expression+" AS "+FormulaCompiler.quote(id));
      String label=string(m.get("alias"),id);
      if(label.length()>150)throw new IllegalArgumentException("指标标题过长");
      columns.add(Map.of("id",id,"name",id,"label",label,"type",type,"kind","measure"));
      metrics.put(id,new Metric(expression,id,type));sortable.put(id,id);
    }
    if(select.size()>8)throw new IllegalArgumentException("字段槽过多");
    Object rawFilters=request.get("filters");
    Map<String,Object> filters=rawFilters==null?Map.of():object(rawFilters,"filters");
    for(Map<String,Object> f:objects(filters.get("pre"),20,"明细过滤")){
      FieldCatalog.Field field=field(required(f,"fieldId"));
      String expression=ref(field);
      if(field.calculated()&&!field.phase().equals("ROW"))throw new IllegalArgumentException("聚合字段不可参与明细过滤");
      where.add(predicate(expression,f,params,field.logicalType()));
    }
    for(Map<String,Object> f:objects(filters.get("post"),20,"结果过滤")){
      Metric metric=metrics.get(required(f,"ref"));
      if(metric==null)throw new IllegalArgumentException("结果过滤必须引用本次指标");
      having.add(predicate(metric.sql(),f,params,metric.type()));
    }
    StringBuilder sql=new StringBuilder("SELECT ").append(String.join(", ",select))
        .append(" FROM (").append(from).append(") biq");
    if(!where.isEmpty())sql.append(" WHERE ").append(String.join(" AND ",where));
    // Dimension-only analysis must produce unique dimension combinations,
    // not duplicate source rows. Keep the same GROUP BY semantics as a
    // dimension + measure query.
    if(!group.isEmpty())sql.append(" GROUP BY ").append(String.join(", ",group));
    if(!having.isEmpty()){
      if(measures.isEmpty())throw new IllegalArgumentException("结果过滤必须先选择指标");
      sql.append(" HAVING ").append(String.join(" AND ",having));
    }
    for(Map<String,Object> order:objects(request.get("sort"),4,"排序")){
      String ref=required(order,"ref"),alias=sortable.get(ref);
      if(alias==null)throw new IllegalArgumentException("排序字段不属于本次分析");
      String direction=string(order.get("direction"),"asc").toUpperCase(Locale.ROOT);
      if(!Set.of("ASC","DESC").contains(direction))throw new IllegalArgumentException("排序方向无效");
      sql.append(sql.indexOf(" ORDER BY ")<0?" ORDER BY ":", ");
      sql.append(FormulaCompiler.quote(alias)).append(" ").append(direction);
    }
    int limit=number(request.get("limit"),200);
    if(limit<1||limit>500)throw new IllegalArgumentException("返回行数必须在 1~500 之间");
    sql.append(" LIMIT ?");
    // Fetch one sentinel row, while exposing at most the requested number
    // of records to clients. This makes 'truncated' factual even at 500.
    params.add(limit+1);
    return new Plan(sql.toString(),Collections.unmodifiableList(new ArrayList<>(params)),List.copyOf(columns),limit);
  }
  private FieldCatalog.Field field(String id){
    FieldCatalog.Field f=fields.get(id);if(f==null)throw new IllegalArgumentException("字段不存在或无权限: "+id);return f;
  }
  private String ref(FieldCatalog.Field f){
    if(!f.calculated())return "biq."+FormulaCompiler.quote(f.sourceColumn());
    FormulaCompiler.Compiled c=formulas.compile(f.expression());
    if(!c.phase().equals("ROW"))throw new IllegalArgumentException("聚合公式不能用于行级字段");
    return "("+c.sql()+")";
  }
  private static String predicate(String expression,Map<String,Object> f,List<Object> params,String type){
    String op=string(f.get("op"),"eq");
    if(op.equals("isNull")||op.equals("notNull"))return "("+expression+(op.equals("isNull")?" IS NULL)":" IS NOT NULL)");
    if(op.equals("in")){
      List<?> values=f.get("values") instanceof List<?> list?list:List.of();
      if(values.isEmpty()||values.size()>50)throw new IllegalArgumentException("IN 参数数量超限");
      List<String> binds=new ArrayList<>();
      for(Object v:values){params.add(convert(v,type));binds.add("?");}
      return "("+expression+" IN ("+String.join(",",binds)+"))";
    }
    String operator=switch(op){case "eq"->"=";case "ne"->"<>";case "gt"->">";case "gte"->">=";case "lt"->"<";case "lte"->"<=";case "contains"->"LIKE";default->throw new IllegalArgumentException("过滤符号无效");};
    if(op.equals("contains")&&!type.equals("STRING"))throw new IllegalArgumentException("文本包含过滤必须作用于字符串字段");
    Object value=convert(f.get("value"),type);
    // Escape using a single, explicit character across MySQL/StarRocks dialects.
    // Backslash literals differ between JDBC and server SQL modes.
    if(op.equals("contains"))
      value="%"+String.valueOf(value).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    params.add(value);
    return "("+expression+" "+operator+" ?"+(op.equals("contains")?" ESCAPE '!'":"")+")";
  }
  private static Object convert(Object value,String type){
    if(value==null)throw new IllegalArgumentException("过滤值不可为 NULL，请使用 isNull");
    String v=String.valueOf(value);
    if(v.length()>500)throw new IllegalArgumentException("过滤值过长");
    try{
      return switch(type){
        case "INTEGER","DECIMAL","DOUBLE"->new BigDecimal(v);
        case "DATE"->java.sql.Date.valueOf(LocalDate.parse(v));
        case "DATETIME"->java.sql.Timestamp.valueOf(LocalDateTime.parse(v.replace(" ","T")));
        case "BOOLEAN"-> {if(!Set.of("true","false").contains(v.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("布尔值无效");yield Boolean.valueOf(v);}
        default->v;
      };
    }catch(java.time.format.DateTimeParseException|NumberFormatException ex){throw new IllegalArgumentException("过滤值类型错误");}
  }
  static List<Map<String,Object>> objects(Object value,int max,String label){
    if(value==null)return List.of();
    if(!(value instanceof List<?> list)||list.size()>max)throw new IllegalArgumentException(label+"格式无效或超过数量上限");
    List<Map<String,Object>> result=new ArrayList<>();
    for(Object item:list)result.add(object(item,label));
    return result;
  }
  static Map<String,Object> object(Object value,String label){
    if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException(label+"必须是对象");
    Map<String,Object> r=new HashMap<>();for(var e:map.entrySet())r.put(String.valueOf(e.getKey()),e.getValue());return r;
  }
  private static int number(Object v,int defaultValue){
    if(v==null)return defaultValue;
    try{return Integer.parseInt(String.valueOf(v));}catch(NumberFormatException ex){throw new IllegalArgumentException("行数必须为整数");}
  }
  static String required(Map<String,Object> m,String k){
    String x=string(m.get(k),"");if(x.isBlank()||x.length()>180)throw new IllegalArgumentException("缺少有效参数 "+k);return x;
  }
  static String string(Object x,String fallback){return x==null?fallback:String.valueOf(x);}
}
