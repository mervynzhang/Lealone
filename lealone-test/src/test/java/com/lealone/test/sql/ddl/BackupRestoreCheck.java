/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

// 在独立进程里打开恢复后的数据库并打印表行数，供BackupTest做恢复校验
public class BackupRestoreCheck {

    public static void main(String[] args) throws Exception {
        Class.forName("com.lealone.client.jdbc.JdbcDriver");
        String url = "jdbc:lealone:embed:" + args[0] + ";user=root;password=";
        try (Connection conn = DriverManager.getConnection(url);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("select count(*) from " + args[1])) {
            rs.next();
            System.out.println("ROW_COUNT=" + rs.getInt(1));
        }
    }
}
