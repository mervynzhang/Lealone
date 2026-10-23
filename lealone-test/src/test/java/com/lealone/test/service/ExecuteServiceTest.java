/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.service;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.lealone.db.Constants;
import com.lealone.test.TestBase;
import com.lealone.test.orm.AllModelPropertyTest;
import com.lealone.test.orm.SqlScript;
import com.lealone.test.orm.generated.User;
import com.lealone.test.service.generated.AllTypeService;
import com.lealone.test.service.generated.HelloWorldService;
import com.lealone.test.service.generated.UserService;
import com.lealone.test.service.impl.UserServiceImpl;
import com.lealone.test.sql.SqlTestBase;

public class ExecuteServiceTest extends SqlTestBase {

    @Test
    public void run() throws Exception {
        String url = getURL();
        // 设置jdbc url后，创建服务可以不用传url
        System.setProperty(Constants.JDBC_URL_KEY, url);

        // 创建user表
        SqlScript.createUserTable(this);
        SqlScript.createAllModelPropertyTable(this);
        createService(this);
        executeService(url);
    }

    private static void createService(SqlExecutor executor) {
        SqlScript.createUserService(executor);
        SqlScript.createHelloWorldService(executor);
        SqlScript.createAllTypeService(executor);
    }

    private static void executeService(String url) throws Exception {
        HelloWorldService helloWorldService = HelloWorldService._create(url);
        helloWorldService.sayHello();
        String r = helloWorldService.sayGoodbyeTo("zhh");
        System.out.println(r);
        assertEquals("Bye zhh", r);

        System.out.println(helloWorldService.getDate());
        System.out.println(helloWorldService.getTwo("zhh", 18));
        assertNotNull(helloWorldService.getDate());
        assertEquals(Integer.valueOf(28), helloWorldService.getTwo("zhh", 18));

        // 调用create没有传递url时，通过getProperty(Constants.JDBC_URL_KEY)自动获取。
        // 注意嵌入式url下proxy会直接new本地实现(不走SQL)，所以"嵌入式正常"不能说明EXECUTE SERVICE正常
        UserService userService = UserService.create();

        User user = new User().name.set("zhh").phone.set(123);
        userService.add(user);

        user = new User().name.set("zhh2").phone.set(456);
        userService.add(user);

        user = userService.find("zhh");
        assertNotNull(user);
        assertEquals("zhh", user.name.get());
        assertEquals(Integer.valueOf(123), user.phone.get());

        Array list = userService.getList();
        // 数组类型的返回值经TCP传回客户端后不能变成null
        assertNotNull(list);
        System.out.println(list.toString());
        assertEquals(2, ((Object[]) list.getArray()).length);
        String listString = list.toString();
        assertTrue(listString.contains("zhh"));
        assertTrue(listString.contains("zhh2"));
        // 走TCP的EXECUTE SERVICE，ARRAY的内容要和嵌入式一致，列类型不能变成NULL
        assertArraySameAsEmbedded(url, list);

        user.notes.set("call remote service");
        userService.update(user);

        userService.delete("zhh");

        AllTypeService allTypeService = AllTypeService.create(url);
        UUID f1 = UUID.randomUUID();
        System.out.println(f1);
        f1 = allTypeService.testUuid(f1);
        System.out.println(f1);

        AllModelPropertyTest.insertRemote(allTypeService);
    }

    private static void assertArraySameAsEmbedded(String url, Array tcpList) throws Exception {
        try (Connection conn = DriverManager.getConnection(url);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("EXECUTE SERVICE user_service get_list()")) {
            assertTrue(rs.next());
            assertEquals(Types.ARRAY, rs.getMetaData().getColumnType(1));
            Array list = rs.getArray(1);
            assertNotNull(list);
            assertEquals(sortedArrayValues(list), sortedArrayValues(tcpList));
        }

        String embeddedUrl = "jdbc:lealone:embed:" + TestBase.TEST_DIR + ";user=root;password=";
        try (Connection conn = DriverManager.getConnection(embeddedUrl);
                Statement stmt = conn.createStatement()) {
            stmt.execute("drop service if exists user_service");
            stmt.execute("drop table if exists user");
            stmt.execute("create table if not exists user(name char(10) primary key,"
                    + " notes varchar, phone int, id long, phones ARRAY)");
            // 不生成代码，用JavaServiceExecutor，避免改动生成的源码
            stmt.execute("create service if not exists user_service ( get_list() array) implement by '"
                    + UserServiceImpl.class.getName() + "'");
            stmt.execute("insert into user(name, phone) values('zhh', 123)");
            stmt.execute("insert into user(name, phone) values('zhh2', 456)");
            try (ResultSet rs = stmt.executeQuery("EXECUTE SERVICE user_service get_list()")) {
                assertTrue(rs.next());
                Array list = rs.getArray(1);
                assertNotNull(list);
                assertEquals(sortedArrayValues(list), sortedArrayValues(tcpList));
            }
        }
    }

    private static List<String> sortedArrayValues(Array array) throws Exception {
        Object[] values = (Object[]) array.getArray();
        List<String> list = new ArrayList<>(values.length);
        for (Object v : values) {
            list.add(String.valueOf(v));
        }
        Collections.sort(list);
        return list;
    }
}
