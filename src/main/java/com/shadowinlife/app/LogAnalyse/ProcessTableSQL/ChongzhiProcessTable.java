package com.shadowinlife.app.LogAnalyse.ProcessTableSQL;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.max;
import static org.apache.spark.sql.functions.min;
import static org.apache.spark.sql.functions.sum;

import java.sql.Date;
import java.util.Calendar;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.hive.HiveContext;

import com.shadowinlife.app.LogAnalyse.SQLModelFactory.RoleLogin;
import com.shadowinlife.app.LogAnalyse.SQLModelFactory.RoleLogout;

public class ChongzhiProcessTable {
 // create daily user act table
    private static String tbUser_process_table_sql = 
            "SELECT "
            + "IF(logout_id is null, login_id, logout_id) AS id, "
            + "IF(tbLogout_iOnlineTime is null, 0, tbLogout_iOnlineTime) AS iOnlineTime,"
            + "IF(tbLogout_iRoleLevel is null,tbLogin_iRoleLevel, tbLogout_iRoleLevel) AS iRoleLevel, "
            + "IF(tbLogout_vClientIp is null, tbLogin_vClientIp, tbLogout_vClientIp) AS ip, "
            + "IF(in_times is null, 0, in_times) AS intime," 
            + "IF(out_times is null, 0, out_times) AS outtime,"
            
            + "(login_regTime) AS regTime,"
            
            + "(CASE WHEN logout_dtEventTime IS NULL THEN login_dtEventTime "
            + "WHEN login_dtEventTime IS NULL THEN logout_dtEventTime "
            + "WHEN logout_dtEventTime>login_dtEventTime THEN logout_dtEventTime "
            + "ELSE login_dtEventTime END) AS acttime "
            
            + "FROM tbLogin FULL OUTER JOIN tbLogout ON tbLogin.login_id=tbLogout.logout_id";
    // USER NOT ACTIVITY
    private static String tbUser_unact_account_table = "INSERT OVERWRITE TABLE fat_login_user "
            + "PARTITION(index_iaccounttype,index_dtstatdate,index_igameid,index_iworldid) "
            + "SELECT '%s', "
            + "T1.iaccounttype,"
            + "T1.suin,"
            + "T1.iregtime,"
            + "T1.igameid,"
            + "T1.iworldid,"
            + "T1.iroleid,"
            + "T1.iroleregtime,"
            + "T1.ilastacttime,"
            + "shiftleft(T1.idayacti),"
            + "T1.iWeekActi," // iWeekacti
            + "T1.iMonthActi," //iMonthacti
            + "T1.igroup,"
            + "T1.ilevel,"
            + "T1.iviplevel,"
            + "0," // times
            + "0," //onlinetime
            + "T1.iaccounttype AS index_iaccounttype,"
            + "DATE2LONG('%s') AS index_dtstatdate,"
            + "T1.igameid AS index_igameid,"
            + "T1.iworldid AS index_iworldid "
            + "FROM (SELECT * FROM fat_login_user WHERE index_dtStatDate=(DATE2LONG('%s')-1)) T1 LEFT JOIN "
            + "loginProcessTable T2 ON T1.suin = T2.id "
            + "WHERE T2.id IS NULL";

    // USER ACTIVITY 
    private static String tbUser_act_account_table = "INSERT OVERWRITE TABLE fat_login_user "
            + "PARTITION(index_iaccounttype,index_dtstatdate,index_igameid,index_iworldid) "
            + "SELECT '%s',"
            + "1," //acounttype
            + "T2.id,"
            + "IF(T1.iregtime IS NULL, T2.regTime, T1.iregtime),"
            + "1," //gameid
            + "1," //worldid
            + "1," //roleid
            + "null, " //roleregtime
            + "T2.acttime,"
            + "shiftact(T1.idayacti),"
            + "T1.iWeekActi," //iWeekacti
            + "T1.iMonthActi," //iMonthacti
            + "0," //group
            + "T2.iRoleLevel, "
            + "1," //iviplevel
            + "IF(T2.outtime is null, T2.intime, T2.outtime),"
            + "T2.iOnlinetime,"
            + "1 AS index_iaccounttype,"
            + "DATE2LONG('%s') AS index_dtstatdate,"
            + "1 AS index_igameid,"
            + "1 AS index_iworldid "
            + "FROM loginProcessTable T2 LEFT JOIN "
            + "(SELECT * FROM fat_login_user WHERE index_dtStatDate=(DATE2LONG('%s')-1)) T1 "
            + "ON T2.id=T1.suin";
    
    private static String shift_fatTable = "INSERT OVERWRITE TABLE fat_login_user "
            + "PARTITION(index_iaccounttype,index_dtstatdate,index_igameid,index_iworldid) "
            + "SELECT T1.dtstatdate, "
            + "T1.iaccounttype,"
            + "T1.suin,"
            + "T1.iregtime,"
            + "T1.igameid,"
            + "T1.iworldid,"
            + "T1.iroleid,"
            + "T1.iroleregtime,"
            + "T1.ilastacttime,"
            + "T1.idayacti,"
            + "%s," // iWeekacti
            + "%s," //iMonthacti
            + "T1.igroup,"
            + "T1.ilevel,"
            + "T1.iviplevel,"
            + "T1.iTimes," // times
            + "T1.ionlinetime," //onlinetime
            + "T1.index_iaccounttype,"
            + "T1.index_dtstatdate,"
            + "T1.index_igameid,"
            + "T1.index_iworldid FROM fat_login_user T1 WHERE index_dtstatdate=date2long('%s')";

    public static boolean process(HiveContext sqlContext, JavaRDD<String[]> loginFile,
            JavaRDD<String[]> logoutFile, String date) {
        
        try {
            
            // Create RDD from login FILES
            JavaRDD<RoleLogin> loginLogs = loginFile.map(new Function<String[], RoleLogin>() {

                private static final long serialVersionUID = -8639862064479870422L;

                @Override
                public com.shadowinlife.app.LogAnalyse.SQLModelFactory.RoleLogin call(String[] line) {
                    
                    return RoleLogin.parseFromLogFile(line);

                }

            });
            
            // Convert all the values into the spark table
            DataFrame schemaLoginRDD = sqlContext.createDataFrame(loginLogs, RoleLogin.class);
            
            schemaLoginRDD.registerTempTable("loginLog");
            
            // Dump out the group data of user login and logout
            DataFrame userLogin = schemaLoginRDD.groupBy("iUin").agg(col("iUin").as("login_id"),
                    max(col("iRoleLevel")).as("tbLogin_iRoleLevel"),
                    max(col("vClientIp")).as("tbLogin_vClientIp"),
                    count(col("iUin")).as("in_times"),
                    max(col("dtEventTime")).as("login_dtEventTime"),
                    min(col("dtCreateTime")).as("login_regTime"));         
            
            // Register temple tables to execute analysis SQL
            userLogin.registerTempTable("tbLogin");

            // Execute the analysis SQL
            DataFrame temp_RDD = sqlContext.sql(tbUser_process_table_sql);

            // Register the result RDD into hive
            sqlContext.registerDataFrameAsTable(temp_RDD, "loginProcessTable");

            // Initialization hive UDF
            sqlContext.sql("use dbprocess");
            sqlContext.sql("ADD JAR hdfs://10-4-28-24:8020//udf.jar");

            //create a temp table with every hour data
            // sqlContext.sql("INSERT INTO TABLE account_daily_fat(suin,ionlinetime,ilevel,sip,) SELECT * FROM loginProcessTable");

            /*
             * IF the day is Sunday,check last 7days of user iactivity and shift iweekacti 
             * IF the day is last day of month, check daycount of last month
             */
            Calendar c = Calendar.getInstance();
            c.setTime(Date.valueOf(date));
            int dayOfWeek = c.get(Calendar.DAY_OF_WEEK);
            int dayOfMonth = c.get(Calendar.DAY_OF_MONTH);
            c.add(Calendar.DATE, 1);
            String iWeekActi = "T1.iweekacti";
            String iMonthActi = "T1.imonthacti";

            sqlContext.sql(String.format(tbUser_unact_account_table, date, date, date));
            sqlContext.sql(String.format(tbUser_act_account_table, date, date, date));
            
            if(dayOfWeek == 1) {
                iWeekActi = "IF(useractivity(T1.iDayActi,7)=1,shiftact(T1.iweekacti),shiftleft(T1.iweekacti))";
            }
            
            if(c.get(Calendar.DAY_OF_MONTH) == 1){
                iMonthActi = String.format(
                        "IF(useractivity(T1.iDayActi,%s)=1,shiftact(T1.imonthacti),shiftleft(T1.imonthacti))",
                        dayOfMonth);
            }
            sqlContext.sql(String.format(shift_fatTable, iWeekActi, iMonthActi, date));
            
            sqlContext.dropTempTable("loginProcessTable");
            sqlContext.dropTempTable("tbLogin");
            
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

    }
    
    public static void ModifyProcessTableWithoutLogFile(HiveContext sqlContext, String date){
        String hql = "INSERT OVERWRITE TABLE fat_login_user "
                + "PARTITION(index_iaccounttype,index_dtstatdate,index_igameid,index_iworldid) "
                + "SELECT '%s', "
                + "iaccounttype,"
                + "suin,"
                + "iregtime,"
                + "igameid,"
                + "iworldid,"
                + "iroleid,"
                + "iroleregtime,"
                + "ilastacttime,"
                + "shiftleft(idayacti),"
                + "%s,"
                + "%s,"
                + "igroup,"
                + "ilevel,"
                + "iviplevel,"
                + "0," //times
                + "0," //onlinetime
                + "iaccounttype AS index_iaccounttype,"
                + "DATE2LONG('%s') AS index_dtstatdate,"
                + "igameid AS index_igameid,"
                + "iworldid AS index_iworldid "
                + "FROM fat_login_user WHERE index_dtStatDate=(DATE2LONG('%s')-1)";
        
        Calendar c = Calendar.getInstance();
        c.setTime(Date.valueOf(date));
        int dayOfWeek = c.get(Calendar.DAY_OF_WEEK);
        int dayOfMonth = c.get(Calendar.DAY_OF_MONTH);
        c.add(Calendar.DATE, 1);
        String iWeekActi = "iweekacti";
        String iMonthActi = "imonthacti";
        
        if(dayOfWeek == 1) {
            iWeekActi = "IF(useractivity(iDayActi,7)=1,shiftact(iweekacti),shiftleft(iweekacti))";
        }
        
        if(c.get(Calendar.DAY_OF_MONTH) == 1){
            iMonthActi = String.format(
                    "IF(useractivity(iDayActi,%s)=1,shiftact(imonthacti),shiftleft(imonthacti))",
                    dayOfMonth);
        }
        // Initialization hive UDF
        sqlContext.sql("use dbprocess");
        sqlContext.sql("ADD JAR hdfs://10-4-28-24:8020//udf.jar");
        
        sqlContext.sql(String.format(hql, date, iWeekActi, iMonthActi, date, date));
    }
}