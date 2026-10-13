package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Preserve referenced datasets used by saved JOIN / UNION steps. */
public final class AnalysisPipelineDependencyGuard {
  private AnalysisPipelineDependencyGuard(){}

  public static void rejectDatasetDeletion(String datasetId,DatabaseStore store,JdbcTemplate db){
    for(var row:db.queryForList(
        "SELECT dataset_id,config_json FROM insight_dataset_pipeline WHERE dataset_id<>?",datasetId)){
      final Map<String,Object> config;
      try{config=store.map(String.valueOf(row.get("config_json")));}
      catch(Exception ex){throw new IllegalStateException("已保存加工配置损坏，请修复后再删除数据集");}
      Object rawSteps=config.get("steps");
      if(!(rawSteps instanceof List<?> steps))
        throw new IllegalStateException("已保存加工步骤损坏，请修复后再删除数据集");
      for(Object raw:steps){
        if(!(raw instanceof Map<?,?> step))
          throw new IllegalStateException("已保存加工步骤结构损坏");
        Object type=step.get("type");
        if(("join".equals(type)||"union".equals(type))&&
            datasetId.equals(step.get("sourceDatasetId")))
          throw new IllegalStateException("数据集被其他数据集的关联或上下合并步骤引用，不能删除");
      }
    }
  }
}
