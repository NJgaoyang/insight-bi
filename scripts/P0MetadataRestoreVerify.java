import java.sql.*;

/**
 * Standalone read-only verification of an EXTRACTED H2 metadata backup.
 * Connection credentials come only from environment variables, never argv.
 * This must not point to the live production H2 file.
 */
public final class P0MetadataRestoreVerify {
  public static void main(String[] args)throws Exception{
    if(args.length!=1)throw new IllegalArgumentException("Expected extracted temporary H2 path");
    String user=System.getenv().getOrDefault("P0_H2_RESTORE_USER","sa");
    String password=System.getenv().getOrDefault("P0_H2_RESTORE_PASSWORD","");
    // ACCESS_MODE_DATA=r and IFEXISTS=TRUE prevent creating a new metadata DB.
    String url="jdbc:h2:file:"+args[0]+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;MODE=MySQL";
    try(Connection conn=DriverManager.getConnection(url,user,password)){
      conn.setReadOnly(true);
      for(String table:new String[]{
          "insight_dataset","insight_dashboard","insight_dashboard_version",
          "insight_dataset_field","insight_calculated_field"}){
        try(Statement stmt=conn.createStatement();
            ResultSet count=stmt.executeQuery("SELECT COUNT(*) FROM "+table)){
          if(!count.next()||count.getLong(1)<0)
            throw new IllegalStateException("Invalid recovered metadata table: "+table);
        }
      }
      System.out.println("H2 isolated metadata restore: required tables are readable");
    }
  }
}
