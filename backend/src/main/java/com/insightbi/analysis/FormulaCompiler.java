package com.insightbi.analysis;

import java.math.BigDecimal;
import java.util.*;

/**
 * Small, intentionally strict formula language. Never evaluates arbitrary Java/SQL text.
 * Field references are stable ids in square brackets. Aggregate functions decide
 * the calculation stage, rather than frontend chart settings.
 */
public final class FormulaCompiler {
  public record Compiled(String sql,String phase,String outputType,Set<String> references,
                         Map<String,Object> canonicalAst){}
  private record Expr(String sql,String type,boolean aggregate,boolean bare,
                      Set<String> refs,Map<String,Object> ast){}
  private final Map<String,FieldCatalog.Field> fields;
  private final Set<String> resolving=new HashSet<>();
  public FormulaCompiler(Map<String,FieldCatalog.Field> fields){this.fields=Map.copyOf(fields);}
  public Compiled compile(String formula){
    Expr x=parse(formula);
    if(x.aggregate&&x.bare)throw new IllegalArgumentException("聚合表达式不能同时直接引用明细字段");
    return new Compiled(x.sql,x.aggregate?"AGGREGATE":"ROW",x.type,Set.copyOf(x.refs),x.ast);
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
    if(!f.calculated())return new Expr("biq."+quote(f.sourceColumn()),f.logicalType(),false,true,
        Set.of(id),Map.of("kind","field","fieldId",id));
    if(!resolving.add(id))throw new IllegalArgumentException("计算字段存在循环依赖: "+id);
    try{
      Expr nested=parse(f.expression());
      if(nested.aggregate&&!f.phase().equals("AGGREGATE"))throw new IllegalArgumentException("计算阶段不匹配");
      if(!nested.aggregate&&f.phase().equals("AGGREGATE"))throw new IllegalArgumentException("聚合计算字段必须含聚合函数");
      Set<String> refs=new LinkedHashSet<>(nested.refs);refs.add(id);
      return new Expr("("+nested.sql+")",nested.type,nested.aggregate,nested.bare,
          refs,Map.of("kind","field","fieldId",id));
    }finally{resolving.remove(id);}
  }
  private static Set<String> union(List<Expr> exprs){
    Set<String> refs=new LinkedHashSet<>();for(Expr x:exprs)refs.addAll(x.refs);return refs;
  }
  private static void numeric(Expr x){
    if(!Set.of("INTEGER","DECIMAL","DOUBLE").contains(x.type))throw new IllegalArgumentException("数值公式不能引用非数值字段");
  }
  private static boolean numericType(String type){
    return Set.of("INTEGER","DECIMAL","DOUBLE").contains(type);
  }
  private static String compatibleType(String left,String right){
    if("NULL".equals(left))return right;
    if("NULL".equals(right))return left;
    if(left.equals(right))return left;
    if(numericType(left)&&numericType(right)){
      return "DOUBLE".equals(left)||"DOUBLE".equals(right)?"DOUBLE":"DECIMAL";
    }
    throw new IllegalArgumentException("条件分支或函数参数类型不一致: "+left+" / "+right);
  }
  private static String resultType(List<Expr> args){
    String type="NULL";
    for(Expr value:args)type=compatibleType(type,value.type);
    return type;
  }
  private static boolean comparable(String left,String right){
    return "NULL".equals(left)||"NULL".equals(right)||left.equals(right)||
        (numericType(left)&&numericType(right));
  }
  private static Expr combined(String sql,String type,List<Expr> expressions,Map<String,Object> ast){
    boolean aggregate=expressions.stream().anyMatch(x->x.aggregate);
    boolean bare=expressions.stream().anyMatch(x->x.bare);
    if(aggregate&&bare)throw new IllegalArgumentException("不能混用明细字段和聚合结果");
    return new Expr(sql,type,aggregate,bare,union(expressions),ast);
  }
  private Expr operation(String op,Expr a,Expr b){
    if(Set.of("+","-","*","/").contains(op)){numeric(a);numeric(b);}
    else if(!Set.of("=","<>",">",">=","<","<=").contains(op))throw new IllegalArgumentException("非法公式操作符");
    boolean comparison=Set.of("=","<>",">",">=","<","<=").contains(op);
    if(comparison&&!comparable(a.type,b.type))
      throw new IllegalArgumentException("比较两侧的字段类型不兼容: "+a.type+" / "+b.type);
    String type=comparison?"BOOLEAN":("DOUBLE".equals(a.type)||"DOUBLE".equals(b.type)?"DOUBLE":"DECIMAL");
    return combined("("+a.sql+" "+op+" "+b.sql+")",type,List.of(a,b),
       Map.of("kind","binary","operator",op,"left",a.ast,"right",b.ast));
  }
  private Expr function(String name,List<Expr> args){
    String n=name.toUpperCase(Locale.ROOT);
    if(Set.of("SUM","AVG","MIN","MAX","COUNT").contains(n)){
      if(args.size()!=1)throw new IllegalArgumentException(n+" 需要一个参数");
      Expr a=args.get(0);
      if(a.aggregate)throw new IllegalArgumentException("不允许嵌套聚合");
      if(Set.of("SUM","AVG").contains(n))numeric(a);
      String type=n.equals("COUNT")?"INTEGER":Set.of("MIN","MAX").contains(n)?a.type:"DECIMAL";
      return new Expr(n+"("+a.sql+")",type,true,false,a.refs,
          Map.of("kind","call","function",n,"arguments",List.of(a.ast)));
    }
    if(!Set.of("ABS","ROUND","COALESCE","NULLIF","IF").contains(n))throw new IllegalArgumentException("不支持的公式函数: "+name);
    int count=args.size();
    if((n.equals("ABS")&&count!=1)||(n.equals("ROUND")&&(count<1||count>2))||
       (Set.of("NULLIF","IF").contains(n)&&count!=(n.equals("IF")?3:2))||
       (n.equals("COALESCE")&&(count<2||count>8)))throw new IllegalArgumentException(n+" 的参数数量无效");
    if(n.equals("IF")&&!args.get(0).type.equals("BOOLEAN"))throw new IllegalArgumentException("IF 条件必须是布尔表达式");
    if(Set.of("ABS","ROUND").contains(n))numeric(args.get(0));
    if(n.equals("ROUND")&&count==2)numeric(args.get(1));
    if(n.equals("NULLIF")&&!comparable(args.get(0).type,args.get(1).type))
      throw new IllegalArgumentException("NULLIF 参数类型不兼容");
    String type=switch(n){
      case "IF"->compatibleType(args.get(1).type,args.get(2).type);
      case "COALESCE"->resultType(args);
      default->args.get(0).type;
    };
    String sql=n+"("+String.join(",",args.stream().map(x->x.sql).toList())+")";
    return combined(sql,type,args,
       Map.of("kind","call","function",n,"arguments",args.stream().map(x->x.ast).toList()));
  }
  private final class Parser {
    final String input;int pos=0,depth=0;
    Parser(String input){this.input=input;}
    void spaces(){while(pos<input.length()&&Character.isWhitespace(input.charAt(pos)))pos++;}
    boolean accept(char c){spaces();if(pos<input.length()&&input.charAt(pos)==c){pos++;return true;}return false;}
    String matchOp(String... ops){spaces();for(String op:ops)if(input.startsWith(op,pos)){pos+=op.length();return op;}return null;}
    boolean keyword(String word){
      spaces();
      int end=pos+word.length();
      if(end>input.length()||!input.regionMatches(true,pos,word,0,word.length()))return false;
      if(end<input.length()&&(Character.isLetterOrDigit(input.charAt(end))||input.charAt(end)=='_'))return false;
      pos=end;return true;
    }
    void expectKeyword(String word){
      if(!keyword(word))throw new IllegalArgumentException("CASE 条件公式缺少 "+word+"，位置 "+pos);
    }
    Expr caseExpression(){
      List<Expr> parts=new ArrayList<>(),values=new ArrayList<>();
      StringBuilder sql=new StringBuilder("CASE");
      List<Map<String,Object>> branchesAst=new ArrayList<>();
      Map<String,Object> fallbackAst=null;
      int branches=0;
      while(keyword("WHEN")){
        if(++branches>8)throw new IllegalArgumentException("CASE 最多支持 8 个 WHEN 分支");
        Expr condition=expression();
        if(!condition.type.equals("BOOLEAN"))throw new IllegalArgumentException("CASE WHEN 条件必须是布尔表达式");
        expectKeyword("THEN");
        Expr value=expression();
        parts.add(condition);parts.add(value);values.add(value);
        branchesAst.add(Map.of("condition",condition.ast,"value",value.ast));
        sql.append(" WHEN ").append(condition.sql).append(" THEN ").append(value.sql);
      }
      if(branches==0)throw new IllegalArgumentException("CASE 至少需要一个 WHEN");
      if(keyword("ELSE")){
        Expr fallback=expression();parts.add(fallback);values.add(fallback);
        fallbackAst=fallback.ast;
        sql.append(" ELSE ").append(fallback.sql);
      }
      expectKeyword("END");
      sql.append(" END");
      Map<String,Object> ast=fallbackAst==null
          ?Map.of("kind","case","branches",List.copyOf(branchesAst))
          :Map.of("kind","case","branches",List.copyOf(branchesAst),"else",fallbackAst);
      return combined(sql.toString(),resultType(values),parts,ast);
    }
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
      if(accept('-')){Expr v=primary();numeric(v);return new Expr("(-"+v.sql+")",v.type,v.aggregate,v.bare,v.refs,
          Map.of("kind","unary","operator","-","operand",v.ast));}
      if(keyword("CASE"))return caseExpression();
      if(keyword("NULL"))return new Expr("NULL","NULL",false,false,Set.of(),Map.of("kind","literal","type","NULL"));
      if(keyword("TRUE"))return new Expr("TRUE","BOOLEAN",false,false,Set.of(),
          Map.of("kind","literal","type","BOOLEAN","value",true));
      if(keyword("FALSE"))return new Expr("FALSE","BOOLEAN",false,false,Set.of(),
          Map.of("kind","literal","type","BOOLEAN","value",false));
      if(accept('\'')){
        StringBuilder literal=new StringBuilder();
        boolean ended=false;
        while(pos<input.length()){
          char ch=input.charAt(pos++);
          if(ch=='\''){
            if(pos<input.length()&&input.charAt(pos)=='\''){literal.append('\'');pos++;continue;}
            ended=true;break;
          }
          if(Character.isISOControl(ch)||literal.length()>=200)throw new IllegalArgumentException("文本常量包含非法字符或过长");
          literal.append(ch);
        }
        if(!ended)throw new IllegalArgumentException("字符串缺少结束引号");
        return new Expr("'"+literal.toString().replace("'","''")+"'","STRING",false,false,Set.of(),
          Map.of("kind","literal","type","STRING","value",literal.toString()));
      }
      if(accept('[')){
        int start=pos;while(pos<input.length()&&input.charAt(pos)!=']')pos++;
        if(!accept(']'))throw new IllegalArgumentException("字段引用缺少 ]");
        return field(input.substring(start,pos-1));
      }
      if(pos<input.length()&&(Character.isDigit(input.charAt(pos))||input.charAt(pos)=='.')){
        int from=pos;
        while(pos<input.length()&&(Character.isDigit(input.charAt(pos))||input.charAt(pos)=='.'))pos++;
        String num=input.substring(from,pos);
        try{return new Expr(new BigDecimal(num).toPlainString(),"DECIMAL",false,false,Set.of(),
            Map.of("kind","literal","type","DECIMAL","value",new BigDecimal(num).toPlainString()));}
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
