/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.service;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.lealone.agent.CodeAgent;
import com.lealone.common.exceptions.DbException;
import com.lealone.common.logging.Logger;
import com.lealone.common.logging.LoggerFactory;
import com.lealone.common.util.CamelCaseHelper;
import com.lealone.common.util.IOUtils;
import com.lealone.common.util.StringUtils;
import com.lealone.common.util.Utils;
import com.lealone.db.Database;
import com.lealone.db.DbObjectType;
import com.lealone.db.LealoneDatabase;
import com.lealone.db.SysProperties;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.async.Future;
import com.lealone.db.auth.Right;
import com.lealone.db.constraint.ConstraintReferential;
import com.lealone.db.schema.Schema;
import com.lealone.db.schema.SchemaObject;
import com.lealone.db.schema.SchemaObjectBase;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.Table;
import com.lealone.db.table.TableType;
import com.lealone.db.util.SourceCompiler;
import com.lealone.db.value.Value;

public class Service extends SchemaObjectBase {

    private static final Logger logger = LoggerFactory.getLogger(Service.class);

    private String language;
    private String packageName;
    private String implementBy;
    private volatile Class<?> implementClass;
    private final String sql;
    private final String serviceExecutorClassName;
    private final List<ServiceMethod> serviceMethods;
    private String codePath;

    private volatile ServiceExecutor executor;
    private StringBuilder executorCode;

    private boolean workflow;

    public Service(Schema schema, int id, String name, String sql, String serviceExecutorClassName,
            List<ServiceMethod> serviceMethods) {
        super(schema, id, name);
        this.sql = sql;
        this.serviceExecutorClassName = serviceExecutorClassName;
        this.serviceMethods = serviceMethods;
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.SERVICE;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getImplementBy() {
        return implementBy;
    }

    public void setImplementBy(String implementBy) {
        this.implementBy = implementBy;
    }

    public Class<?> getImplementClass() {
        return implementClass;
    }

    public List<ServiceMethod> getServiceMethods() {
        return serviceMethods;
    }

    public void setCodePath(String codePath) {
        this.codePath = codePath;
    }

    @Override
    public String getCreateSQL() {
        return sql;
    }

    public boolean isWorkflow() {
        return workflow;
    }

    public void setWorkflow(boolean workflow) {
        this.workflow = workflow;
    }

    public void setExecutorCode(StringBuilder executorCode) {
        this.executorCode = executorCode;
    }

    public void setExecutor(ServiceExecutor executor) {
        this.executor = executor;
    }

    // 延迟创建executor的实例，因为执行create service语句时，依赖的服务实现类还不存在
    public ServiceExecutor getExecutor() {
        return getExecutor(false);
    }

    public ServiceExecutor getExecutor(boolean disableDynamicCompile) {
        if (executor == null) {
            synchronized (this) {
                if (executor == null) {
                    // 跟spring boot集成时不支持动态编译
                    if (disableDynamicCompile) {
                        executor = new JavaServiceExecutor(this);
                        return executor;
                    }
                    if (executorCode != null) {
                        String code = executorCode.toString();
                        executorCode = null;
                        executor = SourceCompiler.compileAsInstance(serviceExecutorClassName, code);
                    } else {
                        executor = Utils.newInstance(serviceExecutorClassName);
                    }
                }
            }
        }
        return executor;
    }

    private void init() {
        if (implementClass == null) {
            synchronized (this) {
                if (implementClass == null) {
                    Exception exception = null;
                    SourceCompiler compiler = getDatabase().getCompiler();
                    compiler.setClassDir(getClassDir());
                    if (getImplementBy() != null) {
                        try {
                            implementClass = compiler.getClass(getImplementBy());
                            return;
                        } catch (Exception e) {
                            exception = e;
                        }
                    }
                    if (getDatabase().isAgentEnabled()) {
                        CodeAgent agent = getDatabase().getCodeAgent();
                        if (isWorkflow()) {
                            genWorkflowCode(agent);
                        } else {
                            genServiceCode(agent);
                        }
                    } else {
                        if (exception == null)
                            exception = new RuntimeException("Service implement class is null");
                        throw DbException.convert(exception);
                    }
                }
            }
        }
    }

    private void genServiceCode(CodeAgent agent) {
        long t1 = System.currentTimeMillis();
        StringBuilder prompt = new StringBuilder();
        prompt.append("以下是").append(getName()).append("服务接口，");
        prompt.append(agent.getPromptPrefix());
        prompt.append("\n").append(getCreateSQL()).append("\n");
        if (!getDatabase().isPromptMode())
            logger.info("Prompt:\n{}", getCreateSQL());
        genJavaCode(agent, getTables(), prompt);
        logger.info("GenServiceCode time: " + (System.currentTimeMillis() - t1) + " ms");
    }

    private void genWorkflowCode(CodeAgent agent) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(agent.getPromptPrefix()).append("\n");
        boolean first = true;
        for (SchemaObject so : schema.getAll(DbObjectType.SERVICE)) {
            if (!so.getName().equals(getName())) {
                if (!first) {
                    first = false;
                    prompt.append("、");
                }
                prompt.append(((Service) so).getImplementBy());
            }
        }

        prompt.append("是服务接口实现类可以直接创建局部对象，优先使用服务接口，为").append(getName());
        prompt.append("生成一个工作流。").append('\n');
        for (SchemaObject so : schema.getAll(DbObjectType.SERVICE)) {
            if (!so.getName().equals(getName())) {
                prompt.append(so.getCreateSQL());
                prompt.append('\n');
            }
        }
        prompt.append(getCreateSQL());
        prompt.append('\n');
        if (!getDatabase().isPromptMode())
            logger.info("Prompt:\n{}", getCreateSQL());
        genJavaCode(agent, schema.getAllTablesAndViews(), prompt);
    }

    private void genJavaCode(CodeAgent agent, Iterable<Table> tables, StringBuilder prompt) {
        SourceCompiler compiler = getDatabase().getCompiler();
        Class<?> modelClass = null;
        HashSet<Class<?>> propertyClasses = new HashSet<>();
        for (Table t : tables) {
            if (t.getTableType() != TableType.STANDARD_TABLE)
                continue;
            String className = toClassName(t.getName());
            String fullName = t.getPackageName() + "." + className;
            prompt.append("以下是").append(className);
            prompt.append("类的源代码,执行查询或批量更新或批量删除时用静态dao字段操作，不要创建新实例:\n");
            String code = t.getCode();
            if (code == null) {
                String src = t.getCodePath();
                if (src == null) {
                    src = getCodePath("src");
                }
                code = readSrcFile(src, t.getPackageName(), className);
                t.setCode(code);
            }
            prompt.append(code);
            try {
                Class<?> tableClass = compiler.getClass(fullName);
                if (modelClass == null) {
                    modelClass = tableClass.getSuperclass();
                }
                for (Field f : tableClass.getDeclaredFields()) {
                    int modifiers = f.getModifiers();
                    if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)) {
                        propertyClasses.add(f.getType());
                    }
                }
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        }
        if (modelClass != null) {
            prompt.append("\n");
            prompt.append("以下是Model类可用的方法, 返回类型或参数类型是T时表示用Model类的子类替换:\n");
            for (Method m : modelClass.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers())) {
                    toString(m, prompt);
                }
            }
        }
        for (Class<?> pc : propertyClasses) {
            prompt.append("\n");
            prompt.append("以下是").append(pc.getCanonicalName());
            prompt.append("类可用的方法, 返回类型或参数类型是T时表示用Model类的子类替换:\n");
            Class<?> currentClass = pc;
            while (currentClass != null && currentClass != Object.class) {
                for (Method m : currentClass.getDeclaredMethods()) {
                    int modifiers = m.getModifiers();
                    if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)) {
                        toString(m, prompt);
                    }
                }
                currentClass = currentClass.getSuperclass();
            }
        }
        prompt.append("\n如果返回的结果用json表示，直接调用com.lealone.orm.json.Json.encode(Object)方法");
        if (getDatabase().isPromptMode()) {
            logger.info("Prompt:\n{}", prompt);
            return;
        }
        AtomicReference<String> previousResponseId = new AtomicReference<>();
        String javaCode = agent.send(prompt.toString(), previousResponseId);
        for (int i = 0; i < 3; i++) {
            logger.info("Java code:\n{}", javaCode);
            compiler.setSource(getImplementBy(), javaCode);
            File classDir = getClassDir();
            try {
                URL[] urls = { classDir.toURI().toURL() };
                compiler.setUrls(urls);
                implementClass = compiler.compile(getImplementBy());
                writeFile(javaCode); // 编译成功再写
                return;
            } catch (Exception e) {
                if (i >= 3)
                    throw DbException.convert(e);
                String message = DbException.getRootCause(e).getMessage();
                logger.info("Code error:\n{}", javaCode);
                javaCode = agent.send(message, previousResponseId);
            }
        }
    }

    private static void toString(Method m, StringBuilder prompt) {
        prompt.append(getTypeName(m.getReturnType())).append(" ");
        prompt.append(m.getName()).append("(");
        int i = 0;
        for (Class<?> p : m.getParameterTypes()) {
            if (i != 0)
                prompt.append(",");
            i++;
            prompt.append(getTypeName(p));
        }
        prompt.append(")\n");
    }

    private static String getTypeName(Class<?> c) {
        String typeName = c.getName();
        if (typeName.equals("com.lealone.orm.Model"))
            typeName = "T";
        else if (typeName.startsWith("java.lang."))
            typeName = typeName.substring(10);
        return typeName;
    }

    private void setDefaultPackageName() {
        if (packageName == null)
            packageName = "service";
    }

    private String getSimpleName() {
        int pos = implementBy.indexOf('.');
        if (pos > 0)
            return implementBy.substring(pos + 1);
        else
            return implementBy;
    }

    private String getCodePath(String dir) {
        if (codePath == null)
            return new File(SysProperties.getBaseDir(), dir).getAbsolutePath();
        else
            return codePath;
    }

    private void writeFile(String javaCode) {
        setDefaultPackageName();
        writeFile(getCodePath("src"), packageName, getSimpleName(), new StringBuilder(javaCode));
    }

    private Iterable<Table> getTables() {
        ArrayList<Table> tables = new ArrayList<>();
        for (ServiceMethod m : serviceMethods) {
            for (Column c : m.getParameters()) {
                getTable(c, tables);
            }
            getTable(m.getReturnType(), tables);
        }
        String name = getName();
        int pos = name.lastIndexOf('_');
        if (pos >= 0)
            name = name.substring(0, pos);
        Table t = getSchema().findTableOrView(null, name);
        if (t != null)
            tables.add(t);
        ArrayList<Table> refTables = new ArrayList<>();
        for (Table table : tables) {
            ArrayList<ConstraintReferential> refConstraints = table.getReferentialConstraints();
            if (refConstraints != null) {
                for (ConstraintReferential refConstraint : refConstraints) {
                    if (refConstraint.getRefTable() != null)
                        refTables.add(refConstraint.getRefTable());
                }
            }
        }
        HashSet<Table> tableSet = new HashSet<>();
        tableSet.addAll(tables);
        tableSet.addAll(refTables);
        for (Table table : getSchema().getAllTablesAndViews()) {
            ArrayList<ConstraintReferential> refConstraints = table.getReferentialConstraints();
            if (refConstraints != null) {
                for (ConstraintReferential refConstraint : refConstraints) {
                    Table refTable = refConstraint.getRefTable();
                    if (refTable != null && tableSet.contains(refTable))
                        tableSet.add(table);
                }
            }
        }
        return tableSet;
    }

    private void getTable(Column c, ArrayList<Table> tables) {
        Table t = c.getTable();
        if (t != null)
            tables.add(t);
    }

    public static String toClassName(String n) {
        n = CamelCaseHelper.toCamelFromUnderscore(n);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static File getClassDir() {
        return new File(SysProperties.getBaseDir(), "classes").getAbsoluteFile();

    }

    private static String getPath(String codePath, String packageName) {
        String path = codePath;
        if (!path.endsWith(File.separator))
            path = path + File.separator;
        path = path.replace('/', File.separatorChar);
        path = path + packageName.replace('.', File.separatorChar) + File.separatorChar;
        return path;
    }

    // public static byte[] readClassFile(String codePath, String packageName, String className) {
    // String path = getPath(codePath, packageName);
    // try {
    // File classFile = new File(path, className + ".class");
    // if (!classFile.exists()) {
    // return null;
    // }
    // return IOUtils.toByteArray(new FileInputStream(classFile));
    // } catch (IOException e) {
    // throw DbException.convertIOException(e, "Failed to read file, path = " + path);
    // }
    // }

    public static void writeClassFile(String codePath, String packageName, String className,
            byte[] bytes) {
        String path = getPath(codePath, packageName);
        try {
            if (!new File(path).exists()) {
                new File(path).mkdirs();
            }
            BufferedOutputStream file = new BufferedOutputStream(
                    new FileOutputStream(path + className + ".class"));
            file.write(bytes);
            file.close();
        } catch (IOException e) {
            throw DbException.convertIOException(e, "Failed to write file, path = " + path);
        }
    }

    public static String readSrcFile(String codePath, String packageName, String className) {
        String path = getPath(codePath, packageName);
        try {
            File classFile = new File(path, className + ".java");
            if (!classFile.exists()) {
                return null;
            }
            return new String(IOUtils.toByteArray(new FileInputStream(classFile)), "UTF-8");
        } catch (IOException e) {
            throw DbException.convertIOException(e, "Failed to read file, path = " + path);
        }
    }

    public static void writeFile(String codePath, String packageName, String className,
            StringBuilder code) {
        String path = getPath(codePath, packageName);
        try {
            if (!new File(path).exists()) {
                new File(path).mkdirs();
            }
            Charset utf8 = Charset.forName("UTF-8");
            byte[] bytes = code.toString().getBytes(utf8);
            File target = new File(path + className + ".java");
            // 内容没变就不重写: 代码生成会在每次测试跑时执行, 无谓重写除了改mtime,
            // 还会让"跑一遍套件就把工作区弄脏"(跟踪的生成文件被整篇替换)以及只读检出直接失败
            if (target.exists() && target.length() == bytes.length
                    && sameContent(target, bytes)) {
                return;
            }
            BufferedOutputStream file = new BufferedOutputStream(new FileOutputStream(target));
            file.write(bytes);
            file.close();
        } catch (IOException e) {
            throw DbException.convertIOException(e, "Failed to write file, path = " + path);
        }
    }

    private static boolean sameContent(File file, byte[] bytes) throws IOException {
        if (file.length() != bytes.length)
            return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buff = new byte[8192];
            int pos = 0;
            int read;
            while ((read = in.read(buff)) > 0) {
                for (int i = 0; i < read; i++)
                    if (buff[i] != bytes[pos + i])
                        return false;
                pos += read;
            }
            return pos == bytes.length;
        }
    }

    public static Service getService(ServerSession session, Database db, String schemaName,
            String serviceName) {
        // 调用服务前数据库可能没有初始化
        if (!db.isInitialized())
            db.init();
        Schema schema = db.getSchema(session, schemaName);
        Service service = schema.getService(session, serviceName);
        service.init();
        return service;
    }

    private static void checkRight(ServerSession session, Service service) {
        session.getUser().checkRight(service, Right.EXECUTE);
    }

    // 老版本代码生成器生成的服务执行器只实现了同步方法，如果直接调用异步方法，
    // ServiceExecutor里异步方法的默认实现会静默返回ValueNull，导致结果变成NULL
    private static final int ASYNC_VALUE_METHOD = 1;
    private static final int ASYNC_MAP_METHOD = 2;
    private static final int ASYNC_JSON_METHOD = 4;
    private static final int SYNC_VALUE_METHOD = 8;
    private static final int SYNC_MAP_METHOD = 16;
    private static final int SYNC_JSON_METHOD = 32;
    private static final Map<Class<?>, Integer> EXECUTOR_METHOD_CACHE = new ConcurrentHashMap<>();

    private static boolean isMethodOverridden(Class<?> executorClass, String methodName,
            Class<?> argsType) {
        try {
            Method method = executorClass.getMethod(methodName, String.class, argsType);
            return method.getDeclaringClass() != ServiceExecutor.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static int getExecutorMethodFlags(ServiceExecutor executor) {
        Class<?> executorClass = executor.getClass();
        Integer flags = EXECUTOR_METHOD_CACHE.get(executorClass);
        if (flags == null) {
            int f = 0;
            if (isMethodOverridden(executorClass, "executeServiceAsync", Value[].class))
                f |= ASYNC_VALUE_METHOD;
            if (isMethodOverridden(executorClass, "executeServiceAsync", Map.class))
                f |= ASYNC_MAP_METHOD;
            if (isMethodOverridden(executorClass, "executeServiceAsync", String.class))
                f |= ASYNC_JSON_METHOD;
            if (isMethodOverridden(executorClass, "executeService", Value[].class))
                f |= SYNC_VALUE_METHOD;
            if (isMethodOverridden(executorClass, "executeService", Map.class))
                f |= SYNC_MAP_METHOD;
            if (isMethodOverridden(executorClass, "executeService", String.class))
                f |= SYNC_JSON_METHOD;
            flags = f;
            EXECUTOR_METHOD_CACHE.put(executorClass, flags);
        }
        return flags;
    }

    private static Future<?> executeServiceAsync(ServiceExecutor executor, String methodName,
            Value[] methodArgs) {
        int flags = getExecutorMethodFlags(executor);
        if ((flags & ASYNC_VALUE_METHOD) != 0)
            return executor.executeServiceAsync(methodName, methodArgs);
        if ((flags & SYNC_VALUE_METHOD) != 0)
            return Future.succeededFuture(executor.executeService(methodName, methodArgs));
        throw noServiceMethod(executor, methodName);
    }

    private static Future<?> executeServiceAsync(ServiceExecutor executor, String methodName,
            Map<String, Object> methodArgs) {
        int flags = getExecutorMethodFlags(executor);
        if ((flags & ASYNC_MAP_METHOD) != 0)
            return executor.executeServiceAsync(methodName, methodArgs);
        if ((flags & SYNC_MAP_METHOD) != 0)
            return Future.succeededFuture(executor.executeService(methodName, methodArgs));
        throw noServiceMethod(executor, methodName);
    }

    private static Future<?> executeServiceAsync(ServiceExecutor executor, String methodName,
            String json) {
        int flags = getExecutorMethodFlags(executor);
        if ((flags & ASYNC_JSON_METHOD) != 0)
            return executor.executeServiceAsync(methodName, json);
        if ((flags & SYNC_JSON_METHOD) != 0)
            return Future.succeededFuture(executor.executeService(methodName, json));
        throw noServiceMethod(executor, methodName);
    }

    private static RuntimeException noServiceMethod(ServiceExecutor executor, String methodName) {
        return DbException.get(ErrorCode.METHOD_NOT_FOUND_1,
                "service method not implemented: " + executor.getClass().getName() + "." + methodName);
    }

    // 服务方法的返回类型，找不到时返回Value.UNKNOWN
    public static int getServiceMethodReturnType(ServerSession session, String serviceName,
            String methodName) {
        Service service = getService(session, session.getDatabase(), session.getCurrentSchemaName(),
                serviceName);
        List<ServiceMethod> serviceMethods = service.getServiceMethods();
        if (serviceMethods != null) {
            for (ServiceMethod m : serviceMethods) {
                if (m.getMethodName().equalsIgnoreCase(methodName)) {
                    Column returnType = m.getReturnType();
                    return returnType == null ? Value.UNKNOWN : returnType.getType();
                }
            }
        }
        return Value.UNKNOWN;
    }

    // 通过jdbc调用
    public static Object execute(ServerSession session, String serviceName, String methodName,
            Value[] methodArgs) {
        return executeAsync(session, serviceName, methodName, methodArgs).get();
    }

    public static Future<?> executeAsync(ServerSession session, String serviceName, String methodName,
            Value[] methodArgs) {
        Service service = getService(session, session.getDatabase(), session.getCurrentSchemaName(),
                serviceName);
        checkRight(session, service);
        return executeServiceAsync(service.getExecutor(), methodName, methodArgs);
    }

    // 通过http调用
    public static Object execute(ServerSession session, String dbName, String schemaName,
            String serviceName, String methodName, Map<String, Object> methodArgs,
            boolean disableDynamicCompile) {
        return executeAsync(session, dbName, schemaName, serviceName, methodName, methodArgs,
                disableDynamicCompile).get();
    }

    public static Future<?> executeAsync(ServerSession session, String dbName, String schemaName,
            String serviceName, String methodName, Map<String, Object> methodArgs,
            boolean disableDynamicCompile) {
        Database db = LealoneDatabase.getInstance().getDatabase(dbName);
        if (db.getSettings().databaseToUpper) {
            serviceName = serviceName.toUpperCase();
            methodName = methodName.toUpperCase();
        }
        Service service = getService(session, db, schemaName, serviceName);
        checkRight(session, service);
        return executeServiceAsync(service.getExecutor(disableDynamicCompile), methodName,
                methodArgs);
    }

    // 通过json调用
    public static Object execute(ServerSession session, String serviceName, String json) {
        return executeAsync(session, serviceName, json).get();
    }

    public static Future<?> executeAsync(ServerSession session, String serviceName, String json) {
        String[] a = StringUtils.arraySplit(serviceName, '.');
        if (a.length == 4) {
            Database db = LealoneDatabase.getInstance().getDatabase(a[0]);
            String methodName = a[3];
            if (db.getSettings().databaseToUpper) {
                methodName = methodName.toUpperCase();
            }
            Service service = getService(session, db, a[1], a[2]);
            checkRight(session, service);
            return executeServiceAsync(service.getExecutor(), methodName, json);
        } else {
            throw new RuntimeException("service " + serviceName + " not found");
        }
    }
}
