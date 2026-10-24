/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.sql.ddl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

// 在独立进程里打开恢复后的数据库并打印查询结果，供备份测试做恢复校验
public class BackupRestoreCheck {

    public static void main(String[] args) throws Exception {
        Class.forName("com.lealone.client.jdbc.JdbcDriver");
        String url = "jdbc:lealone:embed:" + args[0] + ";user=root;password=";
        try (Connection conn = DriverManager.getConnection(url);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(args[1])) {
            ResultSetMetaData meta = rs.getMetaData();
            StringBuilder buff = new StringBuilder();
            while (rs.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    if (i > 1) {
                        buff.append(',');
                    }
                    buff.append(rs.getString(i));
                }
                buff.append(';');
            }
            System.out.println("RESULT=" + buff);
        }
    }
}
