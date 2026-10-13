package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Focused tests for cumulative formula/field expansion limits and graph cycles. */
class FormulaComplexityTest {
  private static FieldCatalog.Field physical(){
    return new FieldCatalog.Field("amount","金额","amount","DECIMAL","BOTH",true,false,"ROW",null);
  }
  private static FormulaCompiler compiler(Map<String,FieldCatalog.Field> fields){
    return new FormulaCompiler(fields);
  }
  @Test void manyNestedCalculatedFieldsFailBeforeUnboundedExpansion(){
    Map<String,FieldCatalog.Field> fields=new HashMap<>();
    fields.put("amount",physical());
    for(int i=1;i<=20;i++){
      String expression=i==20?"[amount]":"[calc"+(i+1)+"]+1";
      fields.put("calc"+i,new FieldCatalog.Field("calc"+i,"层"+i,null,"DECIMAL",
        "MEASURE",true,true,"ROW",expression));
    }
    var compiler=compiler(fields);
    var problem=assertThrows(IllegalArgumentException.class,()->compiler.compile("[calc1]"));
    assertTrue(problem.getMessage().contains("层级"),problem.getMessage());
    // Compilation state is not retained across calls after an error.
    assertEquals("ROW",compiler.compile("[amount]+1").phase());
  }
  @Test void sharedNodeCountRejectsBroadExpressions(){
    Map<String,FieldCatalog.Field> fields=Map.of("v",new FieldCatalog.Field(
      "v","数值","value","DECIMAL","BOTH",true,false,"ROW",null));
    String expression=String.join("+",Collections.nCopies(300,"[v]"));
    assertTrue(expression.length()<=1500);
    IllegalArgumentException ex=assertThrows(IllegalArgumentException.class,
      ()->compiler(fields).compile(expression));
    assertTrue(ex.getMessage().contains("节点"),ex.getMessage());
    assertEquals("DECIMAL",compiler(fields).compile("[v]+[v]").outputType());
  }
  @Test void cycleStillRejectedAndNoGlobalStateLeaked(){
    Map<String,FieldCatalog.Field> fields=new HashMap<>();
    fields.put("amount",physical());
    fields.put("left",new FieldCatalog.Field("left","左",null,"DECIMAL","MEASURE",true,true,"ROW","[right]+1"));
    fields.put("right",new FieldCatalog.Field("right","右",null,"DECIMAL","MEASURE",true,true,"ROW","[left]+1"));
    var compiler=compiler(fields);
    assertTrue(assertThrows(IllegalArgumentException.class,()->compiler.compile("[left]"))
      .getMessage().contains("循环"));
    assertEquals("ROW",compiler.compile("[amount]+1").phase());
  }
}
