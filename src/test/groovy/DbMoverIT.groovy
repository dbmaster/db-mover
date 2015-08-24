import io.dbmaster.testng.BaseToolTestNGCase;
import io.dbmaster.testng.OverridePropertyNames;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test
import org.testng.annotations.Parameters;

import com.branegy.tools.api.ExportType;

public class DbMoverIT extends BaseToolTestNGCase {
    
    @Test
    @Parameters(["db-mover.p_source_database","db-mover.p_backup_path",
        "db-mover.p_target_server","db-mover.p_target_database"])
    public void testModelExport(String p_source_database,String p_backup_path,
         String p_target_server, String p_target_database) {
        def parameters = [ "p_source_database"  :  p_source_database,
                           "p_backup_path" : p_backup_path,
                           "p_target_server" : p_target_server,
                           "p_target_database" : p_target_database
                         ]
        String result = tools.toolExecutor("db-mover", parameters).execute()
        assertTrue(result.contains("BACKUP"), "Unexpected search results ${result}");
    }
}

