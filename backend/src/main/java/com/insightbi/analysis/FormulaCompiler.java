package com.insightbi.analysis;

import java.math.BigDecimal;
import java.util.*;

/**
 * Small, intentionally strict formula language. Never evaluates arbitrary Java/SQL text.
 * Field references are stable ids in square brackets. Aggregate functions decide
 * the calculation stage, rather than frontend chart settings.
 */
public final class FormulaCompiler {
  public record Compiled(String sql,String phase,String outputType,Set<String> references){}
  private record Expr(String sql,String type,boolean aggregate,boolean bare,Set<String> refs){}
  private final Map<String,FieldCatalog.Field> fields;
  private final Set<String> resolving=new HashSet<>();
  public FormulaCompiler(Map<String,FieldCatalog.Field> fields){this.fields=Map.copyOf(fields);}
  public Compiled compile(String formula){
    Expr x=parse(formula);
    if(x.aggregate&&x.bare)throw new IllegalArgumentException("聚合表达式不能同时直接引用明细字段");
    return new Compiled(x.sql,x.aggregate?"AGGREGATE":"ROW",x.type,Set.copyOf(x.refs));
  }
  private Expr parse(String formula){
    if(formula==null||formula.isBlank()||formula.length()>1500)throw new IllegalArgumentException("公式不能为空或过长");
    Parser p=new Parser(formula);Expr x=p.expression();
    p.spaces();if(p.pos!=formula.length())throw new IllegalArgumentException("公式存在未解析内容，位置 "+p.pos);
    return x;
  }
  public static String quote(String s){
    if(s==null||!s.matches("[\\p{L}\\p{N}_.$-]{1,128}"))throw new IllegalArgumentException("非法数据库字段标识");
    return "`"+s.replace("`","``")+"`";
  }
  private Expr field(String id){
    FieldCatalog.Field f=fields.get(id);
    if(f==null)throw new IllegalArgumentException("无权使用或不存在的字段: "+id);
    if(!f.calculated())return new Expr("biq."+quote(f.sourceColumn()),f.logicalType(),false,true,Set.of(id));
    if(!resolving.add(id))throw new IllegalArgumentException("计算字段存在循环依赖: "+id);
    try{
      Expr nested=parse(f.expression());
      if(nested.aggregate&&!f.phase().equals("AGGREGATE"))throw new IllegalArgumentException("计算阶段不匹配");
      if(!nested.aggregate&&f.phase().equals("AGGREGATE"))throw new IllegalArgumentException("聚合计算字段必须含聚合函数");
      Set<String> refs=new LinkedHashSet<>(nested.refs);refs.add(id);
      return new Expr("("+nested.sql+")",nested.type,nested.aggregate,nested.bare,refs);
    }finally{resolving.remove(id);}
  }
  private static Set<String> union(List<Expr> exprs){
    Set<String> refs=new LinkedHashSet<>();for(Expr x:exprs)refs.addAll(x.refs);return refs;
  }
  private static void numeric(Expr x){
    if(!Set.of("INTEGER","DECIMAL","DOUBLE").contains(x.type))throw new IllegalArgumentException("数值公式不能引用非数值字段");
  }
  private Expr operation(String op,Expr a,Expr b){
    if(Set.of("+","-","*","/").contains(op)){numeric(a);numeric(b);}
    else if(!Set.of("=","<>",">",">=","<","<=").contains(op))throw new IllegalArgumentException("非法公式操作符");
    boolean aggregate=a.aggregate||b.aggregate, bare=a.bare||b.bare;
    if(aggregate&&bare)throw new IllegalArgumentException("不能混用明细字段和聚合结果");
    return new Expr("("+a.sql+" "+op+" "+b.sql+")",
      Set.of("=","<>",">",">=","<","<=").contains(op)?"BOOLEAN":"DECIMAL",
      aggregate,bare,union(List.of(a,b)));
  }
  private Expr function(String name,List<Expr> args){
    String n=name.toUpperCase(Locale.ROOT);
    if(Set.of("SUM","AVG","MIN","MAX","COUNT").contains(n)){
      if(args.size()!=1)throw new IllegalArgumentException(n+" 需要一个参数");
      Expr a=args.get(0);
      if(a.aggregate)throw new IllegalArgumentException("不允许嵌套聚合");
      if(!n.equals("COUNT"))numeric(a);
      return new Expr(n+"("+a.sql+")",n.equals("COUNT")?"INTEGER":"DECIMAL",true,false,a.refs);
    }
    if(!Set.of("ABS","ROUND","COALESCE","NULLIF","IF").contains(n))throw new IllegalArgumentException("不支持的公式函数: "+name);
    int count=args.size();
    if((n.equals("ABS")&&count!=1)||(n.equals("ROUND")&&(count<1||count>2))||
       (Set.of("NULLIF","IF").contains(n)&&count!=(n.equals("IF")?3:2))||
       (n.equals("COALESCE")&&(count<2||count>8)))throw new IllegalArgumentException(n+" 的参数数量无效");
    if(n.equals("IF")&&!args.get(0).type.equals("BOOLEAN"))throw new IllegalArgumentException("IF 条件必须是布尔表达式");
    if(Set.of("ABS","ROUND").contains(n))numeric(args.get(0));
    boolean aggregate=args.stream().anyMatch(x->x.aggregate);
    boolean bare=args.stream().anyMatch(x->x.bare);
    if(aggregate&&bare)throw new IllegalArgumentException("函数不能混合聚合值和明细字段");
    String sql=n+"("+String.join(",",args.stream().map(x->x.sql).toList())+")";
    return new Expr(sql,n.equals("IF")?args.get(1).type:args.get(0).type,aggregate,bare,union(args));
  }
  private final class Parser {
    final String input;int pos=0,depth=0;
    Parser(String input){this.input=input;}
    void spaces(){while(pos<input.length()&&Character.isWhitespace(input.charAt(pos)))pos++;}
    boolean accept(char c){spaces();if(pos<input.length()&&input.charAt(pos)==c){pos++;return true;}return false;}
    String matchOp(String... ops){spaces();for(String op:ops)if(input.startsWith(op,pos)){pos+=op.length();return op;}return null;}
    Expr expression(){return comparison();}
    Expr comparison(){
      Expr x=additive();String op;
      while((op=matchOp(">=","<=","<>",">","<","="))!=null)x=operation(op,x,additive());
      return x;
    }
    Expr additive(){
      Expr x=product();String op;
      while((op=matchOp("+","-"))!=null)x=operation(op,x,product());
      return x;
    }
    Expr product(){
      Expr x=primary();String op;
      while((op=matchOp("*","/"))!=null)x=operation(op,x,primary());
      return x;
    }
    Expr primary(){
      spaces();if(++depth>28)throw new IllegalArgumentException("公式嵌套过深");
      try{return atom();}finally{depth--;}
    }
    Expr atom(){
      spaces();
      if(accept('(')){
        Expr v=expression();if(!accept(')'))throw new IllegalArgumentException("缺少右括号");return v;
      }
      if(accept('-')){Expr v=primary();numeric(v);return new Expr("(-"+v.sql+")",v.type,v.aggregate,v.bare,v.refs);}
      if(accept('[')){
        int start=pos;while(pos<input.length()&&input.charAt(pos)!=']')pos++;
        if(!accept(']'))throw new IllegalArgumentException("字段引用缺少 ]");
        return field(input.substring(start,pos-1));
      }
      if(pos<input.length()&&(Character.isDigit(input.charAt(pos))||input.charAt(pos)=='.')){
        int from=pos;
        while(pos<input.length()&&(Character.isDigit(input.charAt(pos))||input.charAt(pos)=='.'))pos++;
        String num=input.substring(from,pos);
        try{return new Expr(new BigDecimal(num).toPlainString(),"DECIMAL",false,false,Set.of());}
        catch(NumberFormatException ex){throw new IllegalArgumentException("非法数值常量");}
      }
      if(pos<input.length()&&Character.isLetter(input.charAt(pos))){
        int from=pos;
        while(pos<input.length()&&Character.isLetter(input.charAt(pos)))pos++;
        String name=input.substring(from,pos);
        if(!accept('('))throw new IllegalArgumentException("仅允许受控函数，不允许自由 SQL 标识");
        List<Expr> args=new ArrayList<>();
        if(!accept(')')){
          do{if(args.size()>=8)throw new IllegalArgumentException("函数参数过多");args.add(expression());}
          while(accept(','));
          if(!accept(')'))throw new IllegalArgumentException("函数参数括号未闭合");
        }
        return function(name,args);
      }
      throw new IllegalArgumentException("无法解析公式，位置 "+pos);
    }
  }
}
