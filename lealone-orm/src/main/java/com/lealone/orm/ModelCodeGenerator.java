/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.orm;

import java.io.File;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.CamelCaseHelper;
import com.lealone.db.Database;
import com.lealone.db.constraint.ConstraintReferential;
import com.lealone.db.index.IndexColumn;
import com.lealone.db.schema.Schema;
import com.lealone.db.service.Service;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.Column.ListColumn;
import com.lealone.db.table.Column.MapColumn;
import com.lealone.db.table.Column.SetColumn;
import com.lealone.db.table.Table;
import com.lealone.db.table.TableCodeGeneratorBase;
import com.lealone.db.table.TableSetting;
import com.lealone.db.util.SourceCompiler;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.orm.json.JsonFormat;
import com.lealone.sql.ddl.CreateService;

public class ModelCodeGenerator extends TableCodeGeneratorBase {

    public ModelCodeGenerator() {
        super("default_table_code_generator");
    }

    @Override
    public String genCode(ServerSession session, Table table, Table owner, int level) {
        String packageName = table.getPackageName();
        String tableName = table.getName();
        String className = CreateService.toClassName(tableName);
        Database db = session.getDatabase();
        Schema schema = table.getSchema();
        boolean databaseToUpper = db.getSettings().databaseToUpper;

        for (ConstraintReferential ref : table.getReferentialConstraints()) {
            Table refTable = ref.getRefTable();
            if (refTable != table && level <= 1) { // 避免递归
                genCode(session, refTable, owner, level + 1);
            }
        }

        // 收集需要导入的类
        TreeSet<String> importSet = new TreeSet<>();
        importSet.add(Model.class.getName());
        importSet.add(ModelTable.class.getName());
        importSet.add(ModelProperty.class.getName());
        importSet.add(JsonFormat.class.getName());

        for (ConstraintReferential ref : table.getReferentialConstraints()) {
            Table refTable = ref.getRefTable();
            owner = ref.getTable();
            if (refTable == table) {
                String pn = owner.getPackageName();
                if (!packageName.equals(pn)) {
                    importSet.add(pn + "." + CreateService.toClassName(owner.getName()));
                }
                importSet.add(List.class.getName());
            } else {
                String pn = refTable.getPackageName();
                if (!packageName.equals(pn)) {
                    importSet.add(pn + "." + CreateService.toClassName(refTable.getName()));
                }
            }
        }

        StringBuilder fields = new StringBuilder();
        StringBuilder fieldNames = new StringBuilder();
        StringBuilder initFields = new StringBuilder();

        for (Column c : table.getColumns()) {
            int type = c.getType();
            String modelPropertyClassName = getModelPropertyClassName(type, importSet);
            String columnName = CamelCaseHelper.toCamelFromUnderscore(c.getName());

            fields.append("    public final ").append(modelPropertyClassName).append('<')
                    .append(className);
            if (c instanceof ListColumn) {
                fields.append(", ");
                ListColumn lc = (ListColumn) c;
                fields.append(getTypeName(lc.element, importSet));
            } else if (c instanceof SetColumn) {
                fields.append(", ");
                SetColumn sc = (SetColumn) c;
                fields.append(getTypeName(sc.element, importSet));
            } else if (c instanceof MapColumn) {
                fields.append(", ");
                MapColumn mc = (MapColumn) c;
                fields.append(getTypeName(mc.key, importSet));
                fields.append(", ");
                fields.append(getTypeName(mc.value, importSet));
            }
            fields.append("> ").append(columnName).append(";\n");

            // 例如: id = new PLong<>("id", this);
            initFields.append("        ").append(columnName).append(" = new ")
                    .append(modelPropertyClassName).append("<>(\"")
                    .append(databaseToUpper ? c.getName().toUpperCase() : c.getName())
                    .append("\", this");
            if (c instanceof MapColumn) {
                MapColumn mc = (MapColumn) c;
                initFields.append(", ").append(getTypeName(mc.key, importSet)).append(".class");
            }
            initFields.append(");\n");
            if (fieldNames.length() > 0) {
                fieldNames.append(", ");
            }
            fieldNames.append(columnName);
        }

        /////////////////////////// 以下是表关联的相关代码 ///////////////////////////

        // associate method(set, get, add) buff
        StringBuilder amBuff = new StringBuilder();
        StringBuilder adderBuff = new StringBuilder();
        StringBuilder adderInitBuff = new StringBuilder();
        StringBuilder setterBuff = new StringBuilder();
        StringBuilder setterInitBuff = new StringBuilder();

        for (ConstraintReferential ref : table.getReferentialConstraints()) {
            Table refTable = ref.getRefTable();
            owner = ref.getTable();
            String refTableClassName = CreateService.toClassName(refTable.getName());
            if (refTable == table) {
                String ownerClassName = CreateService.toClassName(owner.getName());
                // add方法，增加单个model实例
                amBuff.append("    public ").append(className).append(" add").append(ownerClassName)
                        .append("(").append(ownerClassName).append(" m) {\n");
                amBuff.append("        m.set").append(refTableClassName).append("(this);\n");
                amBuff.append("        super.addModel(m);\n");
                amBuff.append("        return this;\n");
                amBuff.append("    }\n");
                amBuff.append("\n");

                // add方法，增加多个model实例
                amBuff.append("    public ").append(className).append(" add").append(ownerClassName)
                        .append("(").append(ownerClassName).append("... mArray) {\n");
                amBuff.append("        for (").append(ownerClassName).append(" m : mArray)\n");
                amBuff.append("            add").append(ownerClassName).append("(m);\n");
                amBuff.append("        return this;\n");
                amBuff.append("    }\n");
                amBuff.append("\n");

                // get list方法
                amBuff.append("    public List<").append(ownerClassName).append("> get")
                        .append(ownerClassName).append("List() {\n");
                amBuff.append("        return super.getModelList(").append(ownerClassName)
                        .append(".class);\n");
                amBuff.append("    }\n");
                amBuff.append("\n");

                // Adder类
                IndexColumn[] refColumns = ref.getRefColumns();
                IndexColumn[] columns = ref.getColumns();
                adderBuff.append("    protected class ").append(ownerClassName)
                        .append("Adder implements AssociateAdder<").append(ownerClassName)
                        .append("> {\n");
                adderBuff.append("        @Override\n");
                adderBuff.append("        public ").append(ownerClassName).append(" getDao() {\n");
                adderBuff.append("            return ").append(ownerClassName).append(".dao;\n");
                adderBuff.append("        }\n");
                adderBuff.append("\n");
                adderBuff.append("        @Override\n");
                adderBuff.append("        public void add(").append(ownerClassName).append(" m) {\n");
                adderBuff.append("            if (");
                for (int i = 0; i < columns.length; i++) {
                    if (i != 0) {
                        adderBuff.append(" && ");
                    }
                    String columnName = CamelCaseHelper
                            .toCamelFromUnderscore(columns[i].column.getName());
                    String refColumnName = CamelCaseHelper
                            .toCamelFromUnderscore(refColumns[i].column.getName());
                    adderBuff.append("areEqual(").append(refColumnName).append(", m.").append(columnName)
                            .append(")");
                }
                adderBuff.append(") {\n");
                adderBuff.append("                add").append(ownerClassName).append("(m);\n");
                adderBuff.append("            }\n");
                adderBuff.append("        }\n");
                adderBuff.append("    }\n");
                adderBuff.append("\n");

                // new Adder()
                if (adderInitBuff.length() > 0)
                    adderInitBuff.append(", ");
                adderInitBuff.append("new ").append(ownerClassName).append("Adder()");
            } else {
                String refTableVar = CamelCaseHelper.toCamelFromUnderscore(refTable.getName());

                // 引用表字段
                fields.append("    private ").append(refTableClassName).append(" ").append(refTableVar)
                        .append(";\n");

                // get方法
                amBuff.append("    public ").append(refTableClassName).append(" get")
                        .append(refTableClassName).append("() {\n");
                amBuff.append("        return ").append(refTableVar).append(";\n");
                amBuff.append("    }\n");
                amBuff.append("\n");

                // set方法
                amBuff.append("    public ").append(className).append(" set").append(refTableClassName)
                        .append("(").append(refTableClassName).append(" ").append(refTableVar)
                        .append(") {\n");
                amBuff.append("        this.").append(refTableVar).append(" = ").append(refTableVar)
                        .append(";\n");

                IndexColumn[] refColumns = ref.getRefColumns();
                IndexColumn[] columns = ref.getColumns();
                for (int i = 0; i < columns.length; i++) {
                    String columnName = CamelCaseHelper
                            .toCamelFromUnderscore(columns[i].column.getName());
                    String refColumnName = CamelCaseHelper
                            .toCamelFromUnderscore(refColumns[i].column.getName());
                    amBuff.append("        this.").append(columnName).append(".set(").append(refTableVar)
                            .append(".").append(refColumnName).append(".get());\n");
                }
                amBuff.append("        return this;\n");
                amBuff.append("    }\n");
                amBuff.append("\n");

                // Setter类
                setterBuff.append("    protected class ").append(refTableClassName)
                        .append("Setter implements AssociateSetter<").append(refTableClassName)
                        .append("> {\n");
                setterBuff.append("        @Override\n");
                setterBuff.append("        public ").append(refTableClassName).append(" getDao() {\n");
                setterBuff.append("            return ").append(refTableClassName).append(".dao;\n");
                setterBuff.append("        }\n");
                setterBuff.append("\n");
                setterBuff.append("        @Override\n");
                setterBuff.append("        public boolean set(").append(refTableClassName)
                        .append(" m) {\n");
                setterBuff.append("            if (");
                for (int i = 0; i < columns.length; i++) {
                    if (i != 0) {
                        setterBuff.append(" && ");
                    }
                    String columnName = CamelCaseHelper
                            .toCamelFromUnderscore(columns[i].column.getName());
                    String refColumnName = CamelCaseHelper
                            .toCamelFromUnderscore(refColumns[i].column.getName());
                    setterBuff.append("areEqual(").append(columnName).append(", m.")
                            .append(refColumnName).append(")");
                }
                setterBuff.append(") {\n");
                setterBuff.append("                set").append(refTableClassName).append("(m);\n");
                setterBuff.append("                return true;\n");
                setterBuff.append("            }\n");
                setterBuff.append("            return false;\n");
                setterBuff.append("        }\n");
                setterBuff.append("    }\n");
                setterBuff.append("\n");

                // new Setter()
                if (setterInitBuff.length() > 0)
                    setterInitBuff.append(", ");
                setterInitBuff.append("new ").append(refTableClassName).append("Setter()");
            }
        }

        /////////////////////////// 以下是生成model类的相关代码 ///////////////////////////

        StringBuilder buff = new StringBuilder();
        buff.append("package ").append(packageName).append(";\n\n");
        for (String p : importSet) {
            buff.append("import ").append(p).append(";\n");
        }
        buff.append("\n");
        buff.append("/**\n");
        buff.append(" * Model for table '").append(tableName).append("'.\n");
        buff.append(" *\n");
        buff.append(" * THIS IS A GENERATED OBJECT, DO NOT MODIFY THIS CLASS.\n");
        buff.append(" */\n");
        // 例如: public class Customer extends Model<Customer> {
        buff.append("public class ").append(className).append(" extends Model<").append(className)
                .append("> {\n");
        buff.append("\n");

        // static create 方法
        // buff.append(" public static ").append(className).append(" create(String url) {\n");
        // buff.append(" ModelTable t = new ModelTable(url, ").append(tableFullName).append(");\n");
        // buff.append(" return new ").append(className).append("(t, REGULAR_MODEL);\n");
        // buff.append(" }\n");
        // buff.append("\n");

        // static dao字段
        String daoName = table.getParameter(TableSetting.DAO_NAME.name());
        if (daoName == null)
            daoName = "dao";
        buff.append("    public static final ").append(className).append(" ").append(daoName)
                .append(" = new ").append(className).append("(null, ROOT_DAO);\n");
        buff.append("\n");

        // 字段
        buff.append(fields);
        buff.append("\n");

        // 默认构造函数
        buff.append("    public ").append(className).append("() {\n");
        buff.append("        this(null, REGULAR_MODEL);\n");
        buff.append("    }\n");
        buff.append("\n");

        String tableFullName = "\"" + db.getName() + "\", \"" + schema.getName() + "\", \"" + tableName
                + "\"";
        if (databaseToUpper) {
            tableFullName = tableFullName.toUpperCase();
        }
        String jsonFormatName = table.getParameter(TableSetting.JSON_FORMAT.name());
        // 内部构造函数
        buff.append("    private ").append(className).append("(ModelTable t, short modelType) {\n");
        buff.append("        super(t == null ? new ModelTable(").append(tableFullName)
                .append(") : t, modelType);\n");
        buff.append(initFields);
        if (jsonFormatName != null)
            buff.append("        super.setJsonFormat(\"").append(jsonFormatName).append("\");\n");
        buff.append("        super.setModelProperties(new ModelProperty[] { ").append(fieldNames)
                .append(" });\n");
        if (setterInitBuff.length() > 0) {
            buff.append("        super.initSetters(").append(setterInitBuff).append(");\n");
        }
        if (adderInitBuff.length() > 0) {
            buff.append("        super.initAdders(").append(adderInitBuff).append(");\n");
        }
        buff.append("    }\n");
        buff.append("\n");

        // newInstance方法
        buff.append("    @Override\n");
        buff.append("    protected ").append(className)
                .append(" newInstance(ModelTable t, short modelType) {\n");
        buff.append("        return new ").append(className).append("(t, modelType);\n");
        buff.append("    }\n");
        buff.append("\n");

        // associate method
        buff.append(amBuff);

        // Setter类
        if (setterBuff.length() > 0) {
            buff.append(setterBuff);
        }

        // Adder类
        if (adderBuff.length() > 0) {
            buff.append(adderBuff);
        }

        // static decode方法
        buff.append("    public static ").append(className).append(" decode(Object obj) {\n");
        buff.append("        return decode(obj, null);\n");
        buff.append("    }\n\n");
        buff.append("    public static ").append(className)
                .append(" decode(Object obj, JsonFormat format) {\n");
        buff.append("        return new ").append(className).append("().decode0(obj, format);\n");
        buff.append("    }\n");
        buff.append("}\n");

        writeFile(table.getCodePath(), packageName, className, buff);
        if (CreateService.isAgentEnabled(session)) {
            String code = buff.toString();
            String fullName = packageName + "." + className;
            SourceCompiler compiler = table.getDatabase().getCompiler();
            compiler.setSource(fullName, code);
            if (level == 1) {
                File classDir = Service.getClassDir();
                compiler.setClassDir(classDir);
                compiler.compile(fullName);
            }
            table.setCode(code);
        }
        return buff.toString();
    }

    private static String getModelPropertyClassName(int type, TreeSet<String> importSet) {
        String name;
        switch (type) {
        case Value.BYTES:
            name = "Bytes";
            break;
        case Value.UUID:
            name = "Uuid";
            break;
        case Value.NULL:
            throw DbException.getInternalError("type = null");
        default:
            name = DataType.getTypeClassName(type);
            int pos = name.lastIndexOf('.');
            name = name.substring(pos + 1);
        }
        name = "P" + name;
        importSet.add("com.lealone.orm.property." + name);
        return name;
    }

    public static void writeFile(String codePath, String packageName, String className,
            StringBuilder code) {
        Service.writeFile(codePath, packageName, className, code);
    }

    public static String getTypeName(Column c, TreeSet<String> importSet) {
        String cType;
        if (c instanceof ListColumn) {
            importSet.add("java.util.List");
            ListColumn lc = (ListColumn) c;
            cType = "List<" + getTypeName(lc.element, importSet) + ">";
            return cType;
        } else if (c instanceof SetColumn) {
            importSet.add("java.util.Set");
            SetColumn sc = (SetColumn) c;
            cType = "Set<" + getTypeName(sc.element, importSet) + ">";
            return cType;
        } else if (c instanceof MapColumn) {
            importSet.add("java.util.Map");
            MapColumn mc = (MapColumn) c;
            cType = "Map<" + getTypeName(mc.key, importSet) + ", " + getTypeName(mc.value, importSet)
                    + ">";
            return cType;
        }
        if (c.getTable() != null) {
            cType = c.getTable().getName();
            cType = toClassName(cType);
            String packageName = c.getTable().getPackageName();
            if (packageName != null)
                cType = packageName + "." + cType;
        } else {
            switch (c.getType()) {
            case Value.BYTES:
                cType = "byte[]";
                break;
            case Value.UUID:
                cType = UUID.class.getName();
                break;
            default:
                cType = DataType.getTypeClassName(c.getType());
            }
        }
        int lastDotPos = cType.lastIndexOf('.');
        if (lastDotPos > 0) {
            if (cType.startsWith("java.lang.")) {
                cType = cType.substring(10);
            } else {
                importSet.add(cType);
                cType = cType.substring(lastDotPos + 1);
            }
        }
        // 把java.lang.Void转成void，这样就不用加return语句
        if (cType.equalsIgnoreCase("void")) {
            cType = "void";
        }
        return cType;
    }

    public static String toClassName(String n) {
        return CreateService.toClassName(n);
    }
}
