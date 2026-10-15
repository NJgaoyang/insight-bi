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
    if(!(raw instanceof Map<?,?> options)||options.size()>4||
       options.keySet().stream().anyMatch(key->!Set.of("showTotals","rowOrder","columnOrder","showSubtotals").contains(key)))
      throw new IllegalArgumentException("交叉表配置无效");
    if(options.containsKey("showTotals")&&!(options.get("showTotals") instanceof Boolean))
      throw new IllegalArgumentException("交叉表合计开关必须为布尔值");
    if(options.containsKey("showSubtotals")&&!(options.get("showSubtotals") instanceof Boolean))
      throw new IllegalArgumentException("交叉表小计开关必须为布尔值");
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
  private static List<Object> rowKey(Map<String,Object> row,int rowLevels){
    List<Object> key=new ArrayList<>();
    for(int i=1;i<=rowLevels;i++)key.add(row.get("d"+i));
    return key;
  }
  public static Map<String,Object> compute(Map<String,Object> request,
      List<Map<String,Object>> detail,boolean truncated,Runner runner) throws Exception{
    if(truncated)return unavailable("结果已截断，无法计算完整合计；请缩小查询范围");
    if(detail.isEmpty())return unavailable("没有可合计的数据");
    Object rawDimensions=request.get("dimensions"),rawMeasures=request.get("measures");
    if(!(rawDimensions instanceof List<?> dims)||(dims.size()!=2&&dims.size()!=3)||
       !(rawMeasures instanceof List<?> measures)||measures.isEmpty()||measures.size()>5)
      return unavailable("交叉表合计要求二至三个维度和最多五个指标");
    if(hasAny(request.get("rank"))||hasAny(request.get("share"))||
       hasAny(request.get("running"))||hasAny(request.get("periodCompare")))
      return unavailable("窗口计算/派生指标暂不支持服务端小计");
    Object rawFilters=request.get("filters");
    if(rawFilters instanceof Map<?,?> filters&&hasAny(filters.get("post")))
      return unavailable("当前已启用结果过滤，无法计算语义一致的小计");
    int rowLevels=dims.size()-1;
    Set<List<Object>> rowKeys=new HashSet<>();
    Set<Object> columnKeys=new HashSet<>(),parentKeys=new HashSet<>();
    for(Map<String,Object> group:detail){
      rowKeys.add(rowKey(group,rowLevels));
      columnKeys.add(group.get("d"+dims.size()));
      if(rowLevels==2)parentKeys.add(group.get("d1"));
    }
    if(rowKeys.size()>100||columnKeys.size()>24)
      return unavailable("交叉表分组数量超过合计安全上限");
    var rowRequest=rollup(request,dims.subList(0,rowLevels),101);
    var columnRequest=rollup(request,List.of(dims.get(dims.size()-1)),25);
    var grandRequest=rollup(request,List.of(),1);
    var rowResult=runner.query(rowRequest);
    var columnResult=runner.query(columnRequest);
    var grandResult=runner.query(grandRequest);
    if(rowResult.size()!=rowKeys.size()||columnResult.size()!=columnKeys.size()||
       grandResult.size()!=1||rowResult.size()>100||columnResult.size()>24)
      return unavailable("分组数据在合计期间发生变化或超限，合计已禁用");
    for(var row:rowResult)if(!rowKeys.contains(rowKey(row,rowLevels)))
      return unavailable("行分组已变化");
    for(var column:columnResult)if(!columnKeys.contains(column.get("d1")))
      return unavailable("列分组已变化");
    Map<String,Object> output=new LinkedHashMap<>();
    output.put("exact",true);
    output.put("rows",convert(rowResult));
    output.put("columns",convert(columnResult));
    output.put("grand",convert(grandResult).get(0));
    if(rowLevels==2){
      boolean subtotalEnabled=!(request.get("pivot") instanceof Map<?,?> opts&&
        Boolean.FALSE.equals(opts.get("showSubtotals")));
      if(subtotalEnabled){
        var parentRequest=rollup(request,List.of(dims.get(0)),101);
        var parentResult=runner.query(parentRequest);
        if(parentResult.size()!=parentKeys.size()||parentResult.size()>100)
          return unavailable("层次小计行数变化或超限");
        for(var parent:parentResult)if(!parentKeys.contains(parent.get("d1")))
          return unavailable("层次小计行维度已变化");
        // Subtotals for every visible column require grouping by parent AND
        // column dimension. Never sum AVG or DISTINCT leaf results in JavaScript.
        Set<List<Object>> parentColumnKeys=new HashSet<>();
        for(var detailRow:detail)
          parentColumnKeys.add(Arrays.asList(detailRow.get("d1"),detailRow.get("d"+dims.size())));
        var parentCellsRequest=rollup(request,List.of(dims.get(0),dims.get(dims.size()-1)),500);
        var parentCells=runner.query(parentCellsRequest);
        if(parentCells.size()!=parentColumnKeys.size()||parentCells.size()>500)
          return unavailable("层级列小计分组变化或超限");
        Set<List<Object>> seenParentCells=new HashSet<>();
        for(var group:parentCells){
          var tuple=Arrays.asList(group.get("d1"),group.get("d2"));
          if(!parentColumnKeys.contains(tuple)||!seenParentCells.add(tuple))
            return unavailable("层级列小计包含无效分组");
        }
        output.put("parents",convert(parentResult));
        output.put("parentCells",convert(parentCells));
      }
    }
    return output;
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
