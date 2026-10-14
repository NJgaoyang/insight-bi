package com.insightbi.analysis;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * P1-02 incremental, ordered in-memory data-processing plan.
 * The dataset source is a validated SELECT, identifiers come only from its
 * catalog and earlier steps, literals become JDBC bind parameters.
 * No DDL, write queries or untrusted SQL fragments can be supplied as steps.
 */
public final class AnalysisPipelineCompiler {
  public record Plan(String sql,List<Object> params,List<Map<String,Object>> columns,
                     int limit,int stepCount) {}
  private record State(String sql, LinkedHashMap<String,FieldCatalog.Field> fields,
                       List<Object> params,boolean grouped) {}
  /** Authorized sources are resolved by the API, not supplied as SQL by clients. */
  public record UnionSource(String sourceSql,List<FieldCatalog.Field> fields) {}
  private static final int MAX_STEPS=12,MAX_SQL=160000;
  private final List<FieldCatalog.Field> initial;
  private final Map<String,UnionSource> unionSources;
  public AnalysisPipelineCompiler(List<FieldCatalog.Field> initial){this(initial,Map.of());}
  public AnalysisPipelineCompiler(List<FieldCatalog.Field> initial,Map<String,UnionSource> unionSources){
    this.initial=List.copyOf(initial);this.unionSources=Map.copyOf(unionSources);
  }

  public Plan compile(String sourceSql,Map<String,Object> request){
    if(request==null)throw new IllegalArgumentException("加工请求不能为空");
    Object raw=request.get("steps");
    if(!(raw instanceof List<?> steps)||steps.size()>MAX_STEPS)
      throw new IllegalArgumentException("加工步骤必须为列表，最多 12 步");
    var fields=new LinkedHashMap<String,FieldCatalog.Field>();
    for(var f:initial)fields.put(f.id(),f);
    State state=new State(ReadOnlySqlGuard.validate(sourceSql),fields,new ArrayList<>(),false);
    Set<String> stepIds=new HashSet<>();
    int index=0;
    for(Object rawStep:steps){
      index++;
      if(!(rawStep instanceof Map<?,?> loose))
        throw new IllegalArgumentException("第 "+index+" 步不是对象");
      Map<String,Object> step=object(loose);
      String id=required(step,"id"),type=required(step,"type");
      if(!id.matches("[A-Za-z_][A-Za-z0-9_]{0,48}")||!stepIds.add(id))
        throw new IllegalArgumentException("步骤 ID 不合法或重复");
      state=switch(type){
        case "formula"->formula(state,step);
        case "filter"->filter(state,step);
        case "case"->caseColumn(state,step);
        case "union"->union(state,step,index);
        case "join"->join(state,step,index);
        case "group"->group(state,step);
        default->throw new IllegalArgumentException("未实现的加工步骤："+type);
      };
      if(state.sql().length()>MAX_SQL)
        throw new IllegalArgumentException("加工 SQL 过长，请减少步骤");
    }
    int limit=integer(request.getOrDefault("limit",100),"预览行数");
    if(limit<1||limit>200)throw new IllegalArgumentException("预览行数必须在 1~200");
    List<Object> params=new ArrayList<>(state.params());
    params.add(limit+1); // sentinel row for truthful truncation
    List<Map<String,Object>> cols=new ArrayList<>();
    for(var f:state.fields().values()){
      if(f.calculated())continue; // base catalog formulas are virtual unless materialized
      cols.add(Map.of("id",f.id(),"name",f.sourceColumn(),"label",f.name(),
        "type",f.logicalType(),"numeric",f.numeric()));
    }
    String sql="SELECT * FROM ("+state.sql()+") biq LIMIT ?";
    return new Plan(sql,List.copyOf(params),List.copyOf(cols),limit,steps.size());
  }

  /**
   * UNION ALL of two pre-existing SELECT datasets on the same configured
   * connection. The API resolves source IDs and enforces connection identity.
   * First-step only: otherwise a join-like row shape mismatch would silently
   * discard calculated columns and duplicate filter semantics.
   */
  private State union(State state,Map<String,Object> step,int position){
    if(position!=1)throw new IllegalArgumentException("上下合并只能放在加工步骤第一位");
    String sourceId=required(step,"sourceDatasetId");
    UnionSource source=unionSources.get(sourceId);
    if(source==null)throw new IllegalArgumentException("合并数据集不存在或不属于当前数据连接");
    List<FieldCatalog.Field> left=initial.stream().filter(f->!f.calculated()).toList();
    List<FieldCatalog.Field> right=source.fields().stream().filter(f->!f.calculated()).toList();
    if(left.isEmpty()||left.size()>100||right.size()!=left.size())
      throw new IllegalArgumentException("上下合并的数据集字段结构不一致");
    // Older configurations without mappings keep their original exact-name
    // matching semantics. Explicit mapping uses server catalog IDs only.
    Object rawMappings=step.get("mappings");
    Map<String,FieldCatalog.Field> matching=new HashMap<>();
    if(rawMappings==null){
      for(var f:right){
        if(matching.put(f.sourceColumn(),f)!=null)
          throw new IllegalArgumentException("上下合并数据集存在重名字段");
      }
    }else{
      if(!(rawMappings instanceof List<?> mappings)||mappings.size()!=left.size())
        throw new IllegalArgumentException("上下合并映射必须覆盖全部左表字段");
      Map<String,FieldCatalog.Field> leftById=new HashMap<>(),rightById=new HashMap<>();
      for(var f:left)if(leftById.put(f.id(),f)!=null)
        throw new IllegalArgumentException("左侧字段 ID 重复");
      for(var f:right)if(rightById.put(f.id(),f)!=null)
        throw new IllegalArgumentException("右侧字段 ID 重复");
      Set<String> usedRight=new HashSet<>();
      for(Object rawMapping:mappings){
        if(!(rawMapping instanceof Map<?,?> m))
          throw new IllegalArgumentException("上下合并字段映射格式无效");
        Map<String,Object> entry=object(m);
        String leftId=required(entry,"leftFieldId"),rightId=required(entry,"rightFieldId");
        FieldCatalog.Field l=leftById.get(leftId),r=rightById.get(rightId);
        if(l==null||r==null||!l.logicalType().equals(r.logicalType())||
            matching.putIfAbsent(l.sourceColumn(),r)!=null||!usedRight.add(rightId))
          throw new IllegalArgumentException("上下合并字段映射重复、未知或类型不匹配");
      }
    }
    List<String> lhs=new ArrayList<>(),rhs=new ArrayList<>();
    for(var l:left){
      FieldCatalog.Field r=matching.get(l.sourceColumn());
      if(r==null||!l.logicalType().equals(r.logicalType()))
        throw new IllegalArgumentException("上下合并字段名称或类型不一致："+l.name());
      String col=FormulaCompiler.quote(l.sourceColumn());
      lhs.add("biq."+col+" AS "+col);
      rhs.add("bis."+FormulaCompiler.quote(r.sourceColumn())+" AS "+col);
    }
    String secondary=ReadOnlySqlGuard.validate(source.sourceSql());
    String sql="SELECT "+String.join(", ",lhs)+" FROM ("+state.sql()+") biq "+
      "UNION ALL SELECT "+String.join(", ",rhs)+" FROM ("+secondary+") bis";
    return new State(sql,state.fields(),state.params(),false);
  }

  /**
   * An explicit same-connection, first-step equi-JOIN. Exactly named right
   * fields are projected under new stable IDs; never SELECT * from both sides.
   * One-to-many fan-out is possible for non-unique keys and must be explicitly
   * acknowledged by the editor/user. No cross join, ON expression or SQL input.
   */
  private State join(State state,Map<String,Object> step,int position){
    if(position!=1)throw new IllegalArgumentException("关联只能放在加工步骤第一位");
    String sourceId=required(step,"sourceDatasetId");
    UnionSource source=unionSources.get(sourceId);
    if(source==null)throw new IllegalArgumentException("关联数据集不存在或不属于当前数据连接");
    String joinType=required(step,"joinType").toUpperCase(Locale.ROOT);
    if(!Set.of("INNER","LEFT").contains(joinType))
      throw new IllegalArgumentException("只支持 INNER 和 LEFT JOIN");
    if(!Boolean.TRUE.equals(step.get("acknowledgeFanout")))
      throw new IllegalArgumentException("关联键可能重复并扩大行数，请明确确认行数放大风险");

    List<FieldCatalog.Field> left=initial.stream().filter(f->!f.calculated()).toList();
    List<FieldCatalog.Field> right=source.fields().stream().filter(f->!f.calculated()).toList();
    if(left.isEmpty()||left.size()>100||right.isEmpty()||right.size()>100)
      throw new IllegalArgumentException("关联字段目录不存在或超过限制");
    Map<String,FieldCatalog.Field> leftFields=new LinkedHashMap<>();
    for(var f:left)leftFields.put(f.id(),f);
    Map<String,FieldCatalog.Field> rightFields=new LinkedHashMap<>();
    for(var f:right)rightFields.put(f.id(),f);
    if(leftFields.size()!=left.size()||rightFields.size()!=right.size())
      throw new IllegalArgumentException("关联数据集存在重复字段 ID");

    Object rawConditions=step.get("conditions"),rawFields=step.get("fields");
    if(!(rawConditions instanceof List<?> conditions)||conditions.isEmpty()||conditions.size()>4)
      throw new IllegalArgumentException("关联必须包含 1~4 个等值条件");
    if(!(rawFields instanceof List<?> projected)||projected.isEmpty()||projected.size()>30)
      throw new IllegalArgumentException("关联必须选择 1~30 个右表输出字段");
    List<String> on=new ArrayList<>();
    Set<String> keys=new HashSet<>();
    for(Object raw:conditions){
      if(!(raw instanceof Map<?,?> m))throw new IllegalArgumentException("关联条件格式无效");
      Map<String,Object> condition=object(m);
      String lid=required(condition,"leftFieldId"),rid=required(condition,"rightFieldId");
      FieldCatalog.Field l=leftFields.get(lid),r=rightFields.get(rid);
      if(l==null||r==null||!l.logicalType().equals(r.logicalType()))
        throw new IllegalArgumentException("关联键不存在或类型不一致");
      if(!keys.add(lid+":"+rid))throw new IllegalArgumentException("关联条件不能重复");
      on.add("biq."+FormulaCompiler.quote(l.sourceColumn())+
        " = bis."+FormulaCompiler.quote(r.sourceColumn()));
    }
    var fields=new LinkedHashMap<>(state.fields());
    List<String> columns=new ArrayList<>();
    Set<String> aliases=new HashSet<>();
    for(var f:left){
      String physical=f.sourceColumn();
      if(!aliases.add(physical.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("左表存在重复物理字段名");
      columns.add("biq."+FormulaCompiler.quote(physical)+" AS "+FormulaCompiler.quote(physical));
    }
    for(Object raw:projected){
      if(!(raw instanceof Map<?,?> m))throw new IllegalArgumentException("关联输出字段格式无效");
      Map<String,Object> requested=object(m);
      String srcId=required(requested,"sourceFieldId"),id=required(requested,"fieldId"),
        name=required(requested,"name");
      if(!id.matches("[A-Za-z_][A-Za-z0-9_]{0,48}")||fields.containsKey(id)||
         name.length()>100)
        throw new IllegalArgumentException("关联输出 ID 或名称无效/冲突");
      FieldCatalog.Field sourceField=rightFields.get(srcId);
      if(sourceField==null)throw new IllegalArgumentException("关联输出字段不存在");
      String alias="join_"+id;
      if(!aliases.add(alias.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("关联输出物理字段名冲突");
      columns.add("bis."+FormulaCompiler.quote(sourceField.sourceColumn())+
        " AS "+FormulaCompiler.quote(alias));
      fields.put(id,new FieldCatalog.Field(id,name,alias,sourceField.logicalType(),
        "BOTH",sourceField.numeric(),false,"ROW",null));
    }
    String sql="SELECT "+String.join(", ",columns)+" FROM ("+state.sql()+") biq "+
      joinType+" JOIN ("+ReadOnlySqlGuard.validate(source.sourceSql())+") bis ON "+
      String.join(" AND ",on);
    return new State(sql,fields,state.params(),false);
  }

  private State formula(State state,Map<String,Object> step){
    String id=required(step,"fieldId"),name=required(step,"name"),expression=required(step,"expression");
    if(!id.matches("[A-Za-z_][A-Za-z0-9_]{0,48}")||state.fields().containsKey(id))
      throw new IllegalArgumentException("计算列 ID 不合法或与已有字段冲突");
    if(name.length()>100)throw new IllegalArgumentException("计算列名称过长");
    var compiled=new FormulaCompiler(state.fields()).compile(expression);
    if(!"ROW".equals(compiled.phase()))
      throw new IllegalArgumentException("加工步骤只允许行级公式，聚合请使用分组汇总");
    String col="step_"+id;
    String sql="SELECT biq.*, "+compiled.sql()+" AS "+FormulaCompiler.quote(col)+
      " FROM ("+state.sql()+") biq";
    var fields=new LinkedHashMap<>(state.fields());
    String type=compiled.outputType();
    fields.put(id,new FieldCatalog.Field(id,name,col,type,"BOTH",
      Set.of("INTEGER","DECIMAL","DOUBLE").contains(type),false,"ROW",null));
    return new State(sql,fields,state.params(),state.grouped());
  }

  private State filter(State state,Map<String,Object> step){
    List<Object> params=new ArrayList<>(state.params());
    String predicate=predicate(state,step,params);
    return new State("SELECT biq.* FROM ("+state.sql()+") biq WHERE ("+predicate+")",
      state.fields(),params,state.grouped());
  }

  /**
   * Condition column: exactly one controlled WHEN predicate and two typed
   * branch literals. No user SQL, no dynamic column identifiers and no
   * untyped implicit conversion. Branch binds follow predicate binds.
   */
  private State caseColumn(State state,Map<String,Object> step){
    String id=required(step,"fieldId"),name=required(step,"name");
    if(!id.matches("[A-Za-z_][A-Za-z0-9_]{0,48}")||state.fields().containsKey(id))
      throw new IllegalArgumentException("条件列 ID 不合法或与已有字段冲突");
    if(name.length()>100)throw new IllegalArgumentException("条件列名称过长");
    String outputType=required(step,"outputType").toUpperCase(Locale.ROOT);
    if(!Set.of("STRING","DECIMAL").contains(outputType))
      throw new IllegalArgumentException("条件列仅支持文本或数值类型");
    // SELECT-list CASE placeholders occur before the nested FROM subquery.
    // Build binds in their actual SQL order: predicate, branches, prior steps.
    List<Object> conditionParams=new ArrayList<>();
    // The output fieldId belongs to the new column; sourceFieldId is the
    // field tested by the predicate. Never reuse one ID for both purposes.
    Map<String,Object> conditionStep=new HashMap<>(step);
    conditionStep.put("fieldId",required(step,"sourceFieldId"));
    String condition=predicate(state,conditionStep,conditionParams);
    Object yes=convert(step.get("thenValue"),outputType);
    Object no=convert(step.get("elseValue"),outputType);
    List<Object> params=new ArrayList<>(conditionParams);
    params.add(yes);params.add(no);params.addAll(state.params());
    String column="step_"+id;
    String sql="SELECT biq.*, CASE WHEN ("+condition+") THEN ? ELSE ? END AS "+
      FormulaCompiler.quote(column)+" FROM ("+state.sql()+") biq";
    var fields=new LinkedHashMap<>(state.fields());
    fields.put(id,new FieldCatalog.Field(id,name,column,outputType,
      "BOTH","DECIMAL".equals(outputType),false,"ROW",null));
    return new State(sql,fields,params,state.grouped());
  }

  private static String predicate(State state,Map<String,Object> step,List<Object> params){
    FieldCatalog.Field field=find(state,required(step,"fieldId"));
    if(field.calculated()&&!"ROW".equals(field.phase()))
      throw new IllegalArgumentException("不能把聚合公式用于过滤条件");
    String expr=fieldExpr(state,field);
    String op=required(step,"op");
    if("isNull".equals(op)||"notNull".equals(op))
      return expr+("isNull".equals(op)?" IS NULL":" IS NOT NULL");
    if("in".equals(op)){
      Object values=step.get("values");
      if(!(values instanceof List<?> list)||list.isEmpty()||list.size()>30)
        throw new IllegalArgumentException("IN 必须包含 1~30 个值");
      List<String> binds=new ArrayList<>();
      for(Object value:list){params.add(convert(value,field.logicalType()));binds.add("?");}
      return expr+" IN ("+String.join(",",binds)+")";
    }
    String operator=switch(op){
      case "eq"->"=";case "ne"->"<>";
      case "gt"->">";case "gte"->">=";
      case "lt"->"<";case "lte"->"<=";
      case "contains"->"LIKE";
      default->throw new IllegalArgumentException("不支持的过滤操作："+op);
    };
    if("contains".equals(op)&&!"STRING".equals(field.logicalType()))
      throw new IllegalArgumentException("文本包含只能用于字符串字段");
    Object value=convert(step.get("value"),field.logicalType());
    if("contains".equals(op))
      value="%"+value.toString().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    params.add(value);
    return expr+" "+operator+" ?"+("contains".equals(op)?" ESCAPE '!'":"");
  }

  private State group(State state,Map<String,Object> step){
    if(state.grouped())throw new IllegalArgumentException("首批步骤引擎仅允许一次分组汇总");
    Object rawDims=step.get("dimensions"),rawMeasures=step.get("measures");
    if(!(rawDims instanceof List<?> dims)||dims.size()>3||
        !(rawMeasures instanceof List<?> measures)||measures.isEmpty()||measures.size()>5)
      throw new IllegalArgumentException("汇总必须包含 1~5 个指标和至多 3 个维度");
    var fields=new LinkedHashMap<String,FieldCatalog.Field>();
    var select=new ArrayList<String>();var group=new ArrayList<String>();var ids=new HashSet<String>();
    for(Object v:dims){
      if(!(v instanceof String id)||!ids.add(id))throw new IllegalArgumentException("维度重复或无效");
      var f=find(state,id);
      if(f.calculated()&&!"ROW".equals(f.phase()))
        throw new IllegalArgumentException("聚合字段不能作为维度");
      String alias="d_"+id;
      String expr=fieldExpr(state,f);
      select.add(expr+" AS "+FormulaCompiler.quote(alias));group.add(expr);
      fields.put(id,new FieldCatalog.Field(id,f.name(),alias,f.logicalType(),
        "DIMENSION",f.numeric(),false,"ROW",null));
    }
    for(Object raw:measures){
      if(!(raw instanceof Map<?,?> loose))throw new IllegalArgumentException("指标配置无效");
      Map<String,Object> measure=object(loose);
      String id=required(measure,"id"),ref=required(measure,"fieldId"),
             agg=required(measure,"aggregate").toUpperCase(Locale.ROOT);
      if(!id.matches("[A-Za-z_][A-Za-z0-9_]{0,48}")||!ids.add(id))
        throw new IllegalArgumentException("指标 ID 不合法或冲突");
      if(!Set.of("SUM","AVG","COUNT","MIN","MAX").contains(agg))
        throw new IllegalArgumentException("聚合方式无效");
      if(Boolean.TRUE.equals(measure.get("distinct"))&&!"COUNT".equals(agg))
        throw new IllegalArgumentException("仅 COUNT 允许去重");
      if(measure.containsKey("distinct")&&!(measure.get("distinct") instanceof Boolean))
        throw new IllegalArgumentException("去重字段必须为布尔值");
      boolean distinct=Boolean.TRUE.equals(measure.get("distinct"));
      FieldCatalog.Field f=null;
      if("*".equals(ref)){
        if(!"COUNT".equals(agg)||distinct)
          throw new IllegalArgumentException("COUNT(*) 不支持其它聚合或去重");
      }else{
        f=find(state,ref);
        if(f.calculated()&&!"ROW".equals(f.phase()))
          throw new IllegalArgumentException("已有聚合公式不能再次分组");
        if(Set.of("SUM","AVG").contains(agg)&&!f.numeric())
          throw new IllegalArgumentException("SUM/AVG 必须使用数值字段");
      }
      String expression=f==null?"COUNT(*)":agg+"("+(distinct?"DISTINCT ":"")+
        fieldExpr(state,f)+")";
      String type="COUNT".equals(agg)?"INTEGER":
        Set.of("MIN","MAX").contains(agg)?f.logicalType():"DECIMAL";
      String alias="m_"+id;
      select.add(expression+" AS "+FormulaCompiler.quote(alias));
      String label=String.valueOf(measure.getOrDefault("name",id));
      if(label.isBlank()||label.length()>100)throw new IllegalArgumentException("指标名称无效");
      fields.put(id,new FieldCatalog.Field(id,label,alias,type,"MEASURE",
        Set.of("INTEGER","DECIMAL","DOUBLE").contains(type),false,"ROW",null));
    }
    String sql="SELECT "+String.join(", ",select)+" FROM ("+state.sql()+") biq"+
      (group.isEmpty()?"":" GROUP BY "+String.join(", ",group));
    return new State(sql,fields,state.params(),true);
  }

  private static FieldCatalog.Field find(State state,String id){
    var f=state.fields().get(id);
    if(f==null)throw new IllegalArgumentException("当前步骤没有字段："+id);
    return f;
  }
  private static String fieldExpr(State state,FieldCatalog.Field f){
    if(!f.calculated())return "biq."+FormulaCompiler.quote(f.sourceColumn());
    var result=new FormulaCompiler(state.fields()).compile(f.expression());
    if(!"ROW".equals(result.phase()))throw new IllegalArgumentException("不能使用聚合计算字段");
    return "("+result.sql()+")";
  }
  private static Map<String,Object> object(Map<?,?> source){
    Map<String,Object> result=new HashMap<>();
    for(var entry:source.entrySet()){
      if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("步骤键名必须为文本");
      result.put(key,entry.getValue());
    }
    return result;
  }
  private static String required(Map<String,Object> obj,String key){
    Object value=obj.get(key);
    if(!(value instanceof String s)||s.isBlank()||s.length()>1500)
      throw new IllegalArgumentException("加工步骤缺少有效字段："+key);
    return s.trim();
  }
  private static int integer(Object value,String label){
    if(!(value instanceof Integer i))throw new IllegalArgumentException(label+"必须为整数");
    return i;
  }
  private static Object convert(Object value,String type){
    if(value==null)throw new IllegalArgumentException("NULL 请使用 isNull 操作");
    String text=String.valueOf(value);
    if(text.length()>500)throw new IllegalArgumentException("过滤值过长");
    try{
      return switch(type){
        case "INTEGER","DECIMAL","DOUBLE"->new BigDecimal(text);
        case "DATE"->Date.valueOf(LocalDate.parse(text));
        case "DATETIME"->Timestamp.valueOf(LocalDateTime.parse(text.replace(' ','T')));
        case "BOOLEAN"->{
          if(!Set.of("true","false").contains(text.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("布尔值无效");
          yield Boolean.valueOf(text);
        }
        default->text;
      };
    }catch(NumberFormatException|java.time.format.DateTimeParseException e){
      throw new IllegalArgumentException("过滤值类型不兼容");
    }
  }
}
