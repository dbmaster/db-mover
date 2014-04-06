/*
 *  File Version:  $Id: report_move_database.groovy 145 2013-05-22 18:10:44Z schristin $
 */

import java.io.PrintStream;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.HashSet;
import com.branegy.service.connection.api.ConnectionService;
import com.branegy.dbmaster.connection.ConnectionProvider;
import com.branegy.inventory.api.InventoryService;

import io.dbmaster.tools.db_script_user.ScriptUser

// TODO - Free text catalog is not seen in sp_helpfile
// TODO - Handle errors when connection is not available, when one of databases is not available
// TODO - Handle FILESTREAM file type  (http://msdn.microsoft.com/en-us/library/ms186782.aspx)
// TODO - ALTER DATABASE PickProSD SET SINGLE_USER WITH ROLLBACK IMMEDIATE
// TODO - SET PASSWORD FOR BACKUP
// TODO - script login system roles (e.g. in system databases)
// TODO - script ENABLE_BROKER: ALTER DATABASE SavexRprt SET ENABLE_BROKER
//               select name, is_broker_enabled from sys.databases where name like 'Save%'




def helper = new MoveDatabase();

connectionSrv = dbm.getService(ConnectionService.class);
inventoryService = dbm.getService(InventoryService.class);

def dbKey = { it.getServerName()+"-"+it.getDatabaseName() }

def source_server =   p_source_database.split("\\.")[0]
def source_database = p_source_database.split("\\.")[1]

connectionInfo = connectionSrv.findByName(source_server)
connector = ConnectionProvider.getConnector(connectionInfo)

def source_conn = connector.getJdbcConnection(null)
dbm.closeResourceOnExit(source_conn)

connectionInfo = connectionSrv.findByName(p_target_server)
connector = ConnectionProvider.getConnector(connectionInfo)

def target_conn = connector.getJdbcConnection(null);
dbm.closeResourceOnExit(target_conn)


def dbName= source_database

logger.info("Handling database ${dbName}")

source_conn.setCatalog(dbName);

println "<pre>"
println "-------------  BACKUP ---------------------------------------------------------"
Statement st = source_conn.createStatement()
ResultSet rs = st.executeQuery("select @@SERVERNAME as ServerName");

def real_source_server_name
if (rs.next()) {
  real_source_server_name = rs.getString("ServerName")
} else {
  logger.error("Cannot get source server name")
  throw new RuntimeException("Cannot get source server name")
}



println ":connect ${real_source_server_name}"

if (p_options.contains("Make Source DB read-only")) {
   println "ALTER DATABASE [${dbName}] SET READ_ONLY"
}

def backupFileName = "${p_backup_path}\\${dbName}_full_${new Date().format("yyyy_MM_dd")}.bak"
def restoreFileName = "${p_restore_path==null ? p_backup_path : p_restore_path}\\${dbName}_full_${new Date().format("yyyy_MM_dd")}.bak"


if (p_source_query!=null) {
    println "\n\n-------------  PRE-PROCESSING-QUERY ---------------------------------------"
    println """USE [${dbName}]
              |GO
              |${p_source_query}
              |GO""".stripMargin();
}
println "\n\n-------------  DATABASE BACKUP ---------------------------------------------------------"

println """USE master
           |GO
           |BACKUP DATABASE [${dbName}]
           |  TO DISK = N'${backupFileName}'
           |  WITH COPY_ONLY, NOFORMAT, INIT, SKIP, NOREWIND, NOUNLOAD,  STATS = 2, NAME = N'${dbName}'
           |GO""".stripMargin();


           
println "\n\n-------------  RESTORE ---------------------------------------------------------"

Statement target_st = target_conn.createStatement()
rs = target_st.executeQuery("select @@SERVERNAME as ServerName");

def real_target_server_name
if (rs.next()) {
  real_target_server_name = rs.getString("ServerName")
} else {
  logger.error("Cannot get target server name")
  throw new RuntimeException("Cannot get target server name")
}

if (p_target_data_dir == null) {
    logger.info("Loading target data folder from target server")
    rs = target_st.executeQuery("exec master.dbo.xp_instance_regread N'HKEY_LOCAL_MACHINE', N'Software\\Microsoft\\MSSQLServer\\MSSQLServer', N'DefaultData'")
    if (rs.next()) {
        p_target_data_dir = rs.getString("Data")
        logger.info("Using default data folder ${p_target_data_dir}")
    } else {
        logger.error("Cannot get default data folder for target server")
        throw new RuntimeException("Cannot get default data folder for target server")
    }
    rs.close()
}


if (p_target_log_dir == null) {
    logger.info("Loading target log folder from target server")
    rs = target_st.executeQuery("exec master.dbo.xp_instance_regread N'HKEY_LOCAL_MACHINE', N'Software\\Microsoft\\MSSQLServer\\MSSQLServer', N'DefaultLog'")
    if (rs.next()) {
        p_target_log_dir = rs.getString("Data")
        logger.info("Using default log folder ${p_target_log_dir}")
    } else {
        logger.error("Cannot get default log folder for target server")
        throw new RuntimeException("Cannot get default log folder for target server")
    }
    rs.close()
}


println ":connect ${real_target_server_name}"

print   """RESTORE DATABASE [${p_target_database}]
           |   FROM DISK = N'${backupFileName}'
           |   WITH FILE = 1, NOUNLOAD, REPLACE, STATS = 2 """.stripMargin();



rs = st.executeQuery("select name,type_desc,physical_name from sys.master_files where database_id=db_id('${dbName}')");

while (rs.next()) {
    String name = rs.getString("name");
    String usage = rs.getString("type_desc");
    String fileName =  rs.getString("physical_name");


    if (usage.matches("ROWS|FULLTEXT")) {
        print ",\n   MOVE N'"+name+"' \n     TO N'"+p_target_data_dir+"\\"+helper.getNameOnly(fileName)+"'";
    } else if (usage.equals("LOG")) {
        print ",\n   MOVE N'"+name+"' \n     TO N'"+p_target_log_dir+"\\"+helper.getNameOnly(fileName)+"'";
    } else {
        throw new RuntimeException("Unknown usage "+ usage);
    }
}

println "\nGO\n"

if (p_options.contains("Delete Backup File")) {
    println "\n-------------REMOVE BACKUP FILE ---------------------------------------------------"
    println "!! del ${backupFileName}"
}

if (p_options.contains("Make Source DB read-only")) {
    println "ALTER DATABASE ["+p_target_database+"] SET READ_WRITE\nGO\n";
}

if (p_target_query!=null) {
    println "\n\n-------------  POST-PROCESSING-QUERY ---------------------------------------"
    println """USE [${p_target_database}]
              |GO
              |${p_target_query}
              |GO""".stripMargin();
}

println "\n\n-------------  POST RESTORE OPTIMIZATIONS AND CHECKS ---------------------------------"

println """USE [${p_target_database}]
           |GO
           |DBCC UPDATEUSAGE([${p_target_database}])
           |GO
           |[${p_target_database}]..sp_updatestats 'RESAMPLE'
           |GO
           |DBCC CHECKDB (${p_target_database}) WITH ALL_ERRORMSGS, DATA_PURITY
           |GO
           |sp_change_users_login 'report'
           |GO""".stripMargin();

rs.close();

if (p_options.contains("Auto Fix Logins")) {
    println ""
    rs = st.executeQuery("select name as [user] from sysusers u where u.islogin=1");

    while (rs.next()) {
        println "sp_change_users_login 'auto_fix', '${rs.getString("user")}'"
    }
    rs.close();
    println ""
}

if (p_options.contains("Script Logins")) {

    rs = st.executeQuery(
            "select u.name as [user] , l.loginname as [login] from sysusers u "+
            "inner join master..syslogins l on u.sid=l.sid where u.issqlrole=0");

    while (rs.next()) {
        String login = rs.getString("login");
        helper.scriptLogin(source_conn, login);
    }

    rs.close();
    println "\n-------------LOGINS-----------------------------------------------------------\n"
    println helper.all_logins
}


st.close()
source_conn.close()
target_conn.close()

println "</pre>"


public class MoveDatabase {
    def logins = new HashSet<String>(10);

    def all_logins = "";

    void scriptLogin(Connection connection, String login) {

        if (logins.contains(login)) {
            return;
        } else {
            logins.add(login);
        }
        all_logins+=ScriptUser.scriptUser(connection, login)
    }

    def String getNameOnly(String fileName) {
        int lastIndexOf = fileName.lastIndexOf("\\");
        return lastIndexOf>0 ? fileName.substring(lastIndexOf+1) : fileName;
    }
}
