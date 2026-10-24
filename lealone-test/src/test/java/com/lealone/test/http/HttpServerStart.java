/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.http;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.lealone.common.exceptions.ConfigException;
import com.lealone.main.Lealone;
import com.lealone.server.http.HttpRouter;
import com.lealone.server.http.HttpServerEngine;
import com.lealone.server.servlet.RequestDispatcher;
import com.lealone.server.servlet.ServletException;
import com.lealone.server.servlet.ServletInputStream;
import com.lealone.server.servlet.ServletOutputStream;
import com.lealone.server.servlet.http.HttpServlet;
import com.lealone.server.servlet.http.HttpServletRequest;
import com.lealone.server.servlet.http.HttpServletResponse;
import com.lealone.sql.config.Config;
import com.lealone.sql.config.Config.PluggableEngineDef;
import com.lealone.sql.config.ConfigListener;

@SuppressWarnings("unused")
public class HttpServerStart extends HttpRouter implements ConfigListener {

    // http://localhost:8080/index.html
    public static void main(String[] args) {
        System.setProperty("lealone.config.listener", HttpServerStart.class.getName());
        Lealone.main(args);
    }

    @Override
    public void init(Map<String, String> config) {
        super.init(config);
        httpServer.addServlet("testServlet", new TestServlet());
        httpServer.addServletMappingDecoded("/test", "testServlet");
        httpServer.addServlet("testDispatchServlet", new TestDispatchServlet());
        httpServer.addServletMappingDecoded("/testDispatch", "testDispatchServlet");

        // httpServer.addServlet("ByteCounter", new ByteCounter());
        // httpServer.addServletMappingDecoded("/ByteCounter", "ByteCounter");
        // httpServer.addServlet("NumberWriter", new NumberWriter());
        // httpServer.addServletMappingDecoded("/NumberWriter", "NumberWriter");
        // httpServer.addServlet("Async0", new Async0());
        // httpServer.addServletMappingDecoded("/Async0", "Async0");
    }

    @Override
    public void applyConfig(Config config) throws ConfigException {
        for (PluggableEngineDef e : config.protocol_server_engines) {
            if (HttpServerEngine.NAME.equalsIgnoreCase(e.name)) {
                e.enabled = true;
                e.parameters.put("router", getClass().getName());
                e.parameters.put("http2_enabled", "true");
            }
        }
    }

    static AtomicInteger count = new AtomicInteger();

    public static class TestDispatchServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            doGet(req, resp);
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // resp.sendRedirect("/test");
            RequestDispatcher dispatcher = req.getRequestDispatcher("/test");
            dispatcher.forward(req, resp);
        }
    }

    // http://localhost:8080/test
    public static class TestServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            doGet(req, resp);
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            if (!req.isAsyncStarted())
                req.startAsync();
            Runnable r = () -> {
                try {
                    run(req, resp);
                    req.getAsyncContext().complete();
                } catch (ServletException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            };
            new Thread(r).start();
            // req.getAsyncContext().start(r);
            // run(req, resp);
            // asyncQuery(req, resp);
        }

        private void run(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain;charset=UTF-8");
            String name = req.getParameter("name");
            System.out.println("name: " + name);
            ServletInputStream input = req.getInputStream();
            input.read();
            // java.io.PrintWriter out = resp.getWriter();
            // out.println("Hello Servlet");
            // out.flush();

            // StringBuffer buff = new StringBuffer();
            // for (int i = 0; i < 9000; i++) {
            // buff.append(i);
            // }
            // out.println(buff.toString());

            // resp.setTrailerFields(() -> {
            // Map<String, String> map = Map.of("k1", "v1", "k2", "v2");
            // return map;
            // });

            ServletOutputStream output = resp.getOutputStream();
            // WriteListener wl = new WriteListener() {
            // @Override
            // public void onWritePossible() throws IOException {
            // }
            //
            // @Override
            // public void onError(Throwable throwable) {
            // }
            // };
            // output.setWriteListener(wl);

            // int size = 1024 * 3;
            // byte[] bytes = new byte[size];
            // for (int i = 0; i < size; i++) {
            // bytes[i] = (byte) i;
            // }
            output.write(bytes);
            // output.write(bytes);
            // output.flush();

            // System.out.println("count: " + count.incrementAndGet());
        }
    }

    private static int size = 128;// 1024 * 9;
    private static byte[] bytes = new byte[size];
    static {
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) i;
        }
    }
}
