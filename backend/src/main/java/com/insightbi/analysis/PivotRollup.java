package com.insightbi.analysis;

import java.math.*;
import java.util.*;

/**
 * Server-only V2 pivot rollups. The same safe V2 planner, pinned dataset and
 * mandatory reader prefilters are reused for each grouped query.
 * Deliberately refuse rollups when post-aggregate semantics would diverge.
 */
public final class PivotRollup {
  private PivotRollup(){}
  @FunctionalInterface public interface Runner {
    List<Map<String,Object>> query(Map<String,Object> request) throws Exception;
  }
  public static boolean requested(Map<String,Object> request){
    Object raw=request.get("pivot");
    if(raw==null)return false;
    if(!(raw instanceof Map<?,?> options)||options.size()>3||
       options.keySet().stream().anyMatch(key->!Set.of("showTotals","rowOrder","columnOrder").contains(key)))
      throw new IllegalArgumentException("交叉表配置无效");
    if(options.containsKey("showTotals")&&!(options.get("showTotals") instanceof Boolean))
      throw new IllegalArgumentException("交叉表合计开关必须为布尔值");
    for(String order:List.of("rowOrder","columnOrder")){
      if(options.containsKey(order)&&!Set.of("asc","desc").contains(options.get(order)))
        throw new IllegalArgumentException("交叉表排序方向无效");
    }
    return !Boolean.FALSE.equals(options.get("showTotals"));
  }
  private static Map<String,Object> unavailable(String reason){
    return Map.of("exact",false,"reason",reason);
  }
  private static boolean hasAny(Object value){
    return value instanceof List<?> list?!list.isEmpty():value!=null;
  }
  public static Map<String,Object> compute(Map<String,Object> request,
      List<Map<String,Object>> detail,boolean truncated,Runner runner) throws Exception{
    if(truncated)return unavailable("结果已截断，无法计算完整合计；请缩小查询范围");
    if(detail.isEmpty())return unavailable("没有可合计的数据");
    Object rawDimensions=request.get("dimensions"),rawMeasures=request.get("measures");
    if(!(rawDimensions instanceof List<?> dims)||dims.size()!=2||
       !(rawMeasures instanceof List<?> measures)||measures.isEmpty()||measures.size()>5)
      return unavailable("交叉表合计要求两个维度和最多五个指标");
    if(hasAny(request.get("rank"))||hasAny(request.get("share"))||
       hasAny(request.get("running"))||hasAny(request.get("periodCompare")))
      return unavailable("窗口计算/派生指标暂不支持服务端小计");
    Object rawFilters=request.get("filters");
    if(rawFilters instanceof Map<?,?> filters&&hasAny(filters.get("post")))
      return unavailable("当前已启用结果过滤，无法计算语义一致的小计");
    // Refuse server rollups on a group set larger than the finite display bounds.
    Set<Object> rows=new HashSet<>(),columns=new HashSet<>();
    for(Map<String,Object> group:detail){
      rows.add(group.get("d1"));columns.add(group.get("d2"));
    }
    if(rows.size()>100||columns.size()>24)
      return unavailable("交叉表分组数量超过合计安全上限");
    var rowRequest=rollup(request,List.of(dims.get(0)),101);
    var columnRequest=rollup(request,List.of(dims.get(1)),25);
    var grandRequest=rollup(request,List.of(),1);
    var rowResult=runner.query(rowRequest);
    var columnResult=runner.query(columnRequest);
    var grandResult=runner.query(grandRequest);
    if(rowResult.size()!=rows.size()||columnResult.size()!=columns.size()||
       grandResult.size()!=1||rowResult.size()>100||columnResult.size()>24)
      return unavailable("分组数据在合计期间发生变化或超限，合计已禁用");
    // Do not expose totals for dimensions not present in the bounded detail.
    for(var row:rowResult)if(!rows.contains(row.get("d1")))return unavailable("行分组已变化");
    for(var column:columnResult)if(!columns.contains(column.get("d1")))return unavailable("列分组已变化");
    return Map.of("exact",true,"rows",convert(rowResult),"columns",convert(columnResult),
      "grand",convert(grandResult).get(0));
  }
  private static Map<String,Object> rollup(Map<String,Object> request,List<?> dimensions,int limit){
    Map<String,Object> copy=new LinkedHashMap<>(request);
    copy.remove("sort");copy.remove("pivot");
    copy.put("dimensions",dimensions);
    copy.put("limit",limit);
    return copy;
  }
  private static List<Map<String,Object>> convert(List<Map<String,Object>> records){
    List<Map<String,Object>> out=new ArrayList<>();
    for(var record:records){
      Map<String,Object> safe=new LinkedHashMap<>();
      record.forEach((key,value)->{
        // Preserve JDBC decimal precision across JSON and browser boundaries.
        if(value instanceof BigDecimal d)safe.put(key,d.toPlainString());
        else if(value instanceof BigInteger i)safe.put(key,i.toString());
        else if(value instanceof Long l&&!"d1".equals(key))safe.put(key,l.toString());
        else safe.put(key,value);
      });
      out.add(safe);
    }
    return out;
  }
}
