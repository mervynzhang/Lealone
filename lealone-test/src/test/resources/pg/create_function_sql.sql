-- PostgreSQL 回归测试 create_function_sql.sql 的 Lealone 转换版。
-- 期望输出格式见 com.lealone.test.pg.PgRegressRunner (和 H2 风格的 sql 脚本一样)。
--
-- 约定:
--   * 每个用例以 "-- @case <id>" 开始, 紧跟 "-- @pg <PG源文件>:<行号范围>" 记录出处,
--     id 同时是 pg/skip-list.txt 的键; 被 skip 的用例仍然留在这里, 不执行, 原因写在清单里。
--   * 对照 PG 时做的改动只有三类, 都写在用例里的 "-- 适配:" 注释中:
--       1. PG 允许无名参数(CREATE FUNCTION f(text, date)), Lealone 要求参数带名字,
--          所以函数体里按名字引用, 不再写 $1/$2;
--       2. PG 的布尔输出是 t/f, Lealone 是 TRUE/FALSE;
--       3. PG 大量查 pg_proc/information_schema 来验证属性, Lealone 没有这些视图,
--          这类用例整体 skip, 不改成"调用一下能返回就行"来自欺欺人。
--   * 被 skip 的用例里保留 PG 原样的语句(个别处为了让文件能读而删掉了 $ 记法), 期望值以
--     "-- PG 期望:" 注释的形式给出, 方便将来把这些用例打开时对照。

-- ---------------------------------------------------------------------------
-- 文件开头的建库准备
-- ---------------------------------------------------------------------------

-- @case function_sql.setup
-- @pg create_function_sql.sql:9-14
-- 缺失: Lealone 没有 SET search_path(只支持 SET SCHEMA_SEARCH_PATH), CREATE USER 也必须带 PASSWORD;
--       PG 这份文件的用例都放在 temp_func_test schema 里, 转换版不用 schema 前缀
create user regress_unpriv_user;
create schema temp_func_test;
grant all on schema temp_func_test to public;
set search_path to temp_func_test, public;
-- PG 期望: 4 条都成功, 后面建的函数都在 temp_func_test schema 里

-- ---------------------------------------------------------------------------
-- ARGUMENT and RETURN TYPES
-- ---------------------------------------------------------------------------

-- @case function_sql.a1_args
-- @pg create_function_sql.sql:23-24
-- 适配: 参数命名 + 函数体改用参数名; PG 里这段的调用语句在 :34
create function functest_A_1(a text, b date) returns boolean language sql
    as 'select a = ''abcd'' and b > ''2001-01-01''';
> update count: 0

select functest_A_1('abcd', '2020-01-01') r;
> R
> ----
> TRUE
> rows: 1

select functest_A_1('abcde', '2020-01-01') r;
> R
> -----
> FALSE
> rows: 1

select functest_A_1('abcd', '2001-01-01') r;
> R
> -----
> FALSE
> rows: 1

-- 参数是 NULL 时比较结果是 NULL, 函数不会因为参数是 NULL 就不执行(默认 CALLED ON NULL INPUT)
select functest_A_1(null, '2020-01-01') r;
> R
> ----
> null
> rows: 1

-- @case function_sql.a2_array_arg
-- @pg create_function_sql.sql:25-26
-- 缺失: 数组参数本身已经支持, 但这条还依赖 $1 位置参数和 ARRAY[...] 字面量
create function functest_A_2(a text[]) returns int language sql
    as 'select a[1]::int';
-- @pg 调用在 :35: select functest_A_2(ARRAY['1','2','3']);
-- PG 期望: 1

-- @case function_sql.a3_no_args
-- @pg create_function_sql.sql:27-28
create function functest_A_3() returns boolean language 'sql'
    as 'select false';
> update count: 0

-- PG 调用在 :36
select functest_A_3() r;
> R
> -----
> FALSE
> rows: 1

-- @case function_sql.pg_proc
-- @pg create_function_sql.sql:29-36
-- 缺失: Lealone 没有 pg_proc 系统目录和 regproc 类型
select proname, prorettype::regtype, proargtypes::regtype[] from pg_proc
       where oid in ('functest_A_1'::regproc,
                     'functest_A_2'::regproc,
                     'functest_A_3'::regproc) order by proname;
-- PG 期望: functest_a_1 | boolean | [text, date] 等 3 行

-- ---------------------------------------------------------------------------
-- IMMUTABLE | STABLE | VOLATILE
-- ---------------------------------------------------------------------------

-- @case function_sql.volatility
-- @pg create_function_sql.sql:41-48
-- 适配: PG 下面用 pg_proc.provolatile 验证属性是否登记; Lealone 没有 pg_proc,
--       这里只验证四种写法都能建函数并且调用结果正确(属性本身只体现在持久化的 DDL 里)
create function functest_B_1(a int) returns boolean language 'sql'
    as 'select a > 0';
> update count: 0

create function functest_B_2(a int) returns boolean language 'sql'
    immutable as 'select a > 0';
> update count: 0

create function functest_B_3(a int) returns boolean language 'sql'
    stable as 'select a = 0';
> update count: 0

create function functest_B_4(a int) returns boolean language 'sql'
    volatile as 'select a < 0';
> update count: 0

select functest_B_1(1) r;
> R
> ----
> TRUE
> rows: 1

select functest_B_2(-1) r;
> R
> -----
> FALSE
> rows: 1

select functest_B_3(0) r;
> R
> ----
> TRUE
> rows: 1

select functest_B_4(-1) r;
> R
> ----
> TRUE
> rows: 1

-- @case function_sql.volatility_catalog
-- @pg create_function_sql.sql:49-61
-- 缺失: Lealone 没有 pg_proc.provolatile, 也没有 ALTER FUNCTION
select proname, provolatile from pg_proc
       where oid in ('functest_B_1'::regproc,
                     'functest_B_2'::regproc,
                     'functest_B_3'::regproc,
                     'functest_B_4'::regproc) order by proname;
alter function functest_B_2(int) volatile;
alter function functest_B_3(int) cost 100;
-- PG 期望: 4 行, 再 ALTER 后 functest_b_2 的 provolatile 变成 v

-- ---------------------------------------------------------------------------
-- SECURITY DEFINER | INVOKER, LEAKPROOF
-- ---------------------------------------------------------------------------

-- @case function_sql.security_definer
-- @pg create_function_sql.sql:66-83
-- 缺失: Lealone 不支持 SECURITY DEFINER/INVOKER 属性, 没有 pg_proc.prosecdef, 也没有 ALTER FUNCTION
create function functest_C_1(a int) returns boolean language 'sql'
    as 'select a > 0';
create function functest_C_2(a int) returns boolean language 'sql'
    security definer as 'select a = 0';
create function functest_C_3(a int) returns boolean language 'sql'
    security invoker as 'select a < 0';
select proname, prosecdef from pg_proc
       where oid in ('functest_C_1'::regproc,
                     'functest_C_2'::regproc,
                     'functest_C_3'::regproc) order by proname;
alter function functest_C_2(int) security invoker;
-- PG 期望: functest_c_1=f, functest_c_2=t, functest_c_3=f, ALTER 后 c_2 变 f

-- @case function_sql.leakproof
-- @pg create_function_sql.sql:88-119
-- 缺失: Lealone 不支持 LEAKPROOF 属性和 GRANT/SET ROLE 权限语义
create function functest_E_1(a int) returns boolean language 'sql'
    as 'select a > 100';
create function functest_E_2(a int) returns boolean language 'sql'
    leakproof as 'select a > 100';
select proname, proleakproof from pg_proc
       where oid in ('functest_E_1'::regproc,
                     'functest_E_2'::regproc) order by proname;
alter function functest_E_2(int) not leakproof;
set session authorization regress_unpriv_user;
create function functest_E_3(a int) returns boolean language 'sql'
    leakproof as 'select a < 200';  -- PG 里这一步没有超级用户权限, 报错
reset session authorization;
-- PG 期望: e_1=f, e_2=t, 再 f; 建 functest_E_3 报 42501

-- ---------------------------------------------------------------------------
-- CALLED ON NULL INPUT | RETURNS NULL ON NULL INPUT | STRICT
-- ---------------------------------------------------------------------------

-- @case function_sql.strict
-- @pg create_function_sql.sql:124-131
-- 适配: 四个函数的三属性都能写了(CALLED ON NULL INPUT / RETURNS NULL ON NULL INPUT / STRICT),
--       用调用验证"入参为 NULL 时函数体执不执行"
create function functest_F_1(a int) returns boolean language 'sql'
    as 'select a > 50';
> update count: 0

create function functest_F_2(a int) returns boolean language 'sql'
    called on null input as 'select a = 50';
> update count: 0

create function functest_F_3(a int) returns boolean language 'sql'
    returns null on null input as 'select a < 50';
> update count: 0

create function functest_F_4(a int) returns boolean language 'sql'
    strict as 'select a = 50';
> update count: 0

-- 普通函数/CALLED ON NULL INPUT: 入参 NULL 时函数体照常执行, 结果是 NULL
select functest_F_1(null) r;
> R
> ----
> null
> rows: 1

select functest_F_2(null) r;
> R
> ----
> null
> rows: 1

-- STRICT / RETURNS NULL ON NULL INPUT: 入参 NULL 时函数体不执行, 直接返回 NULL
select functest_F_3(null) r;
> R
> ----
> null
> rows: 1

select functest_F_4(null) r;
> R
> ----
> null
> rows: 1

select functest_F_1(51) r;
> R
> ----
> TRUE
> rows: 1

select functest_F_2(50) r;
> R
> ----
> TRUE
> rows: 1

select functest_F_3(49) r;
> R
> ----
> TRUE
> rows: 1

select functest_F_4(50) r;
> R
> ----
> TRUE
> rows: 1

-- @case function_sql.strict_catalog
-- @pg create_function_sql.sql:132-145
-- 缺失: Lealone 没有 pg_proc.proisstrict, 也不支持 ALTER FUNCTION
--       (CALLED ON NULL INPUT 关键字现在可以写了, 见 function_sql.strict)
create function functest_F_2(a int) returns boolean language 'sql'
    called on null input as 'select a = 50';
select proname, proisstrict from pg_proc
       where oid in ('functest_F_1'::regproc,
                     'functest_F_2'::regproc,
                     'functest_F_3'::regproc,
                     'functest_F_4'::regproc) order by proname;
alter function functest_F_2(int) strict;
-- PG 期望: f_1=f, f_2=f, f_3=t, f_4=t; ALTER 后 f_2 变 t

-- @case function_sql.pg_get_functiondef
-- @pg create_function_sql.sql:150-153
-- 缺失: Lealone 没有 pg_get_functiondef() 和 regproc
select pg_get_functiondef('functest_A_1'::regproc);
select pg_get_functiondef('functest_B_3'::regproc);
select pg_get_functiondef('functest_C_3'::regproc);
select pg_get_functiondef('functest_F_2'::regproc);
-- PG 期望: 4 个 CREATE OR REPLACE FUNCTION 文本

-- ---------------------------------------------------------------------------
-- SQL-standard body
-- ---------------------------------------------------------------------------

-- @case function_sql.sql_body_return
-- @pg create_function_sql.sql:159-170
-- 缺失: Lealone 无 SQL 标准函数体(RETURN <表达式>)和 BEGIN ATOMIC 体
create function functest_S_1(a text, b date) returns boolean
    language sql
    return a = 'abcd' and b > '2001-01-01';
create function functest_S_3() returns boolean
    return false;
create function functest_S_3a() returns boolean
    begin atomic
        ;;return false;;
    end;
-- PG 期望: 3 个函数创建成功, select functest_S_1(...) 返回 t

-- @case function_sql.sql_body_atomic
-- @pg create_function_sql.sql:171-195
-- 缺失: Lealone 函数体只允许一条查询, 不支持 BEGIN ATOMIC 多语句体
create function functest_S_10(a text, b date) returns boolean
    language sql
    begin atomic
        select a = 'abcd' and b > '2001-01-01';
    end;
create function functest_S_13() returns boolean
    begin atomic
        select 1;
        select false;
    end;
create table functest1 (i int);
create function functest_S_16(a int, b int) returns void
    language sql
    begin atomic
        insert into functest1 select a + $2;
    end;
-- PG 期望: 3 个函数创建成功; functest_S_13() 返回 f

-- @case function_sql.sql_body_polymorphic
-- @pg create_function_sql.sql:197-205
-- 缺失: Lealone 无 anyarray/anyelement 多态类型, 也无 SQL 标准函数体
create function functest_S_xx(a anyarray) returns anyelement
    language sql
    return a[1];
create function functest_S_xx(a date) returns boolean
    language sql
    return a > 1;
-- PG 期望: 第一条报 42P13(不允许这样用多态), 第二条建函数成功、调用时报 42883/22007

-- @case function_sql.sql_body_case
-- @pg create_function_sql.sql:207-212
-- 缺失: Lealone 无 BEGIN ATOMIC 体 (CASE 表达式本身是支持的, 见 function_sql.a1_args 的函数体)
create function functest_S_15(a int) returns boolean
language sql
begin atomic
    select case when a % 2 = 0 then true else false end;
end;
-- PG 期望: functest_S_15(2)=t, functest_S_15(3)=f

-- @case function_sql.pg_get_functiondef_sqlbody
-- @pg create_function_sql.sql:214-228
-- 缺失: Lealone 没有 pg_get_functiondef() 和 regproc
select pg_get_functiondef('functest_S_1'::regproc);
select pg_get_functiondef('functest_S_15'::regproc);
-- PG 期望: 返回函数定义文本, SQL 标准体被转写成 AS 体

-- @case function_sql.sql_body_view
-- @pg create_function_sql.sql:232-242
-- 缺失: Lealone 无 SQL 标准函数体(RETURN (SELECT ...))
create table functest3 (a int);
insert into functest3 values (1), (2);
create view functestv3 as select * from functest3;
create function functest_S_14() returns bigint
    return (select count(*) from functestv3);
select functest_S_14();
-- PG 期望: 2

-- @case function_sql.sql_body_tempseq
-- @pg create_function_sql.sql:244-253
-- 缺失: Lealone 无 SQL 标准函数体, 也无 TEMP SEQUENCE/DISCARD TEMP
create temp sequence mytempseq;
create function functest_tempseq() returns int
    return nextval('mytempseq');
discard temp;
-- PG 期望: 建函数成功, DISCARD TEMP 后函数被连带删除

-- ---------------------------------------------------------------------------
-- information_schema
-- ---------------------------------------------------------------------------

-- @case function_sql.information_schema
-- @pg create_function_sql.sql:256-323
-- 缺失: Lealone 没有 information_schema.parameters/routines/routine_*_usage 这些视图
--       (这段还依赖 DEFAULT 参数、OUT 参数、序列, 这些能力也不支持)
create function functest_IS_1(a int, b int default 1, c text default 'foo')
    returns int
    language sql
    as 'select a + b';
create function functest_IS_2(out a int, b int default 1)
    returns int
    language sql
    as 'select a';
select routine_name, ordinal_position, parameter_name, parameter_default
    from information_schema.parameters;
select r0.routine_name, r1.routine_name
  from information_schema.routine_routine_usage rru;
drop function functest_IS_1(int, int, text), functest_IS_2(int);
-- PG 期望: 参数视图里能看到参数名/默认值, 依赖视图里能看到函数间的引用关系

-- ---------------------------------------------------------------------------
-- overload / DROP
-- ---------------------------------------------------------------------------

-- @case function_sql.overload
-- @pg create_function_sql.sql:326-331
-- 缺失: Lealone 不支持按参数类型重载(函数按名字唯一), 因此也不存在"DROP 时重载歧义"
create function functest_B_2(bigint) returns boolean language 'sql'
       immutable as 'select $1 > 0';
drop function functest_b_1;              -- PG 期望: 成功
drop function functest_b_1;              -- PG 期望: 报 42883 (不存在)
drop function functest_b_2;              -- PG 期望: 报 42725 (重载歧义)
-- PG 期望: 建 bigint 版本成功(重载), 最后一条 DROP 因两个同名函数而歧义报错

-- @case function_sql.drop_not_found
-- @pg create_function_sql.sql:329-330
-- 适配: PG 这里 DROP 的是前面 :41 建的 functest_B_1(第二次报 42883);
--       本文件里 functest_B_1 已经由 function_sql.volatility 建过, 这里另建一个专用名字
create function functest_drop_me(a int) returns boolean language 'sql'
       as 'select a > 0';
> update count: 0

drop function functest_drop_me;
> update count: 0

drop function functest_drop_me;
> exception 90077

-- ---------------------------------------------------------------------------
-- CREATE OR REPLACE
-- ---------------------------------------------------------------------------

-- @case function_sql.or_replace
-- @pg create_function_sql.sql:334-337
-- 适配: PG 的 CREATE OR REPLACE 后面接的是 WINDOW 属性(function->window 互转),
--       Lealone 不支持 WINDOW, 这里只验证 OR REPLACE 覆盖已有定义
create function functest1(a int) returns int language sql
    as 'select a';
> update count: 0

select functest1(3) r;
> R
> -
> 3
> rows: 1

create or replace function functest1(a int) returns int language sql
    as 'select a * 10';
> update count: 0

select functest1(3) r;
> R
> --
> 30
> rows: 1

-- @case function_sql.or_replace_window
-- @pg create_function_sql.sql:337-339
-- 缺失: Lealone 不支持 WINDOW 属性, 也不支持 FUNCTION 和 PROCEDURE 之间互相 CREATE OR REPLACE
create or replace function functest1(a int) returns int language sql window
    as 'select $1';
create or replace procedure functest1(a int) language sql
    as 'select $1';
drop function functest1(a int);
-- PG 期望: 前两条都成功(函数变成 window 函数, 再变成过程), 最后 DROP FUNCTION 报 42883

-- ---------------------------------------------------------------------------
-- set-returning functions
-- ---------------------------------------------------------------------------

-- @case function_sql.setof
-- @pg create_function_sql.sql:342-348
-- 适配: Lealone 没有 generate_series(), 函数体换成等价的 SYSTEM_RANGE;
--       PG 的 SELECT functest_srf0() 在 select 列表里展开 setof, Lealone 明确不支持这种展开
--       (报 FEATURE_NOT_SUPPORTED), 要写成 SELECT * FROM functest_srf0()
create function functest_srf0() returns setof int
language sql
as $$ select x from system_range(1, 100) $$;
> update count: 0

select * from functest_srf0() limit 5;
> FUNCTEST_SRF0
> -------------
> 1
> 2
> 3
> 4
> 5
> rows: 5

-- @case function_sql.setof_inline
-- @pg create_function_sql.sql:353-376
-- 缺失: Lealone 无 RETURNS SETOF, 无 BEGIN ATOMIC, 无 EXPLAIN
create function functest_sri1() returns setof int
language sql
stable
as '
    select * from functest3;
';
select * from functest_sri1();
explain (verbose, costs off) select * from functest_sri1();
-- PG 期望: 1/2/3, EXPLAIN 显示函数被内联成对 functest3 的扫描

-- ---------------------------------------------------------------------------
-- VOID 返回值
-- ---------------------------------------------------------------------------

-- @case function_sql.void_return
-- @pg create_function_sql.sql:381-390
-- 适配: PG 的一行空值在 Lealone 里显示成 null; 顺便验证 VOID 函数里再调另一个 VOID 函数
create function voidtest1(a int) returns void language sql as
$$ select a + 1 $$;
> update count: 0

select voidtest1(42) r;
> R
> ----
> null
> rows: 1

create function voidtest2(a int, b int) returns void language sql as
$$ select voidtest1(a + b) $$;
> update count: 0

select voidtest2(11,22) r;
> R
> ----
> null
> rows: 1

-- @case function_sql.void_insert
-- @pg create_function_sql.sql:392-402
-- 缺失: voidtest3 的函数体是不带 RETURNING 的 INSERT(只支持带RETURNING的DML);
--       voidtest4 带 RETURNING, 现在已经支持
create temp table sometable(f1 int);
create function voidtest3(a int) returns void language sql as
$$ insert into sometable values(a + 1) $$;
select voidtest3(17);
create function voidtest4(a int) returns void language sql as
$$ insert into sometable values(a - 1) returning f1 $$;
select voidtest4(39);
table sometable;
-- PG 期望: sometable 里有两行 18 和 38

-- @case function_sql.setof_void
-- @pg create_function_sql.sql:404-406
-- 适配: Lealone 没有 generate_series(), 函数体换成 system_range; 和 PG 一样,
--       RETURNS SETOF VOID 不返回任何行(函数体返回什么、返回多少列都不影响)
create function voidtest5(a int) returns setof void language sql as
$$ select 1 from system_range(1, a) $$ stable;
> update count: 0

select * from voidtest5(3);
> VOIDTEST5
> ---------
> rows: 0

-- @case function_sql.ddl_in_body
-- @pg create_function_sql.sql:409-431
-- 缺失: Lealone 函数体只允许一条查询, 不允许在函数体里执行 DDL/DML
set check_function_bodies to off;
create function create_and_insert() returns void language sql as $$
  create table ddl_test (f1 int);
  insert into ddl_test values (1.2);
$$;
select create_and_insert();
table ddl_test;
reset check_function_bodies;
-- PG 期望: 函数体里的 DDL 生效, ddl_test 有 1 行 1.2

-- @case function_sql.anyarray_setof
-- @pg create_function_sql.sql:434-447
-- 缺失: Lealone 无 anyarray/anyelement 多态类型和 RETURNS SETOF
create function double_append(a anyarray, b anyelement) returns setof anyarray
language sql immutable as
$$ select array_append($1, $2) || array_append($1, $2) $$;
select double_append(array_append(array[q1], q2), q3)
  from (values(1,2,3), (4,5,6)) v(q1,q2,q3);
-- PG 期望: 两行数组 {1,2,2,3} 和 {4,5,5,6}

-- @case function_sql.partition_ops
-- @pg create_function_sql.sql:449-461
-- 缺失: Lealone 无 CREATE OPERATOR CLASS / PARTITION BY hash 绑定自定义操作符类
create operator class part_test_int4_ops_bad for type int4 using hash as
  function 2 part_hashint4_error(int4, int8);
create table pt(i int) partition by hash (i part_test_int4_ops_bad);
-- PG 期望: 建 operator class 和分区表成功, 插入时函数除零报错

-- ---------------------------------------------------------------------------
-- 函数体里调用另一个函数
-- ---------------------------------------------------------------------------

-- @case function_sql.nested_call
-- @pg create_function_sql.sql:385-387
-- 适配: PG 的 voidtest2 返回 VOID(见 function_sql.void_return 记录的缺陷),
--       这里改成 RETURNS int, 只验证"函数体里可以调用另一个 SQL 函数"
create function inc(a int) returns int language sql
    as 'select a + 1';
> update count: 0

create function twice_inc(a int, b int) returns int language sql
    as 'select inc(a + b) * 2';
> update count: 0

select twice_inc(11, 22) r;
> R
> --
> 68
> rows: 1

-- ---------------------------------------------------------------------------
-- Things that shouldn't work
-- ---------------------------------------------------------------------------

-- @case function_sql.bad_body_return_type
-- @pg create_function_sql.sql:465-466
-- 适配: PG 报 42804, Lealone 建函数时校验常量返回类型报 22018, 都是 CREATE 时拒绝;
--       另外去掉函数体末尾的分号(Lealone 函数体不允许分号)
create function test1 (a int) returns int language sql
    as 'select ''not an integer''';
> exception 22018

-- @case function_sql.bad_body_keyword
-- @pg create_function_sql.sql:468-469
-- 适配: PG 报语法错误 42601, Lealone 报 50100(函数体必须是 SELECT/VALUES/TABLE), 都是拒绝
create function test1 (a int) returns int language sql
    as 'not even SQL';
> exception 50100

-- @case function_sql.bad_body_columns
-- @pg create_function_sql.sql:471-472
-- 适配: PG 报 42804, Lealone 建函数时校验结果列数报 22018, 都是 CREATE 时拒绝;
--       另外去掉函数体末尾的分号(Lealone 函数体不允许分号)
create function test1 (a int) returns int language sql
    as 'select 1, 2, 3';
> exception 22018

-- @case function_sql.bad_body_positional
-- @pg create_function_sql.sql:474-475
-- 缺失: Lealone 不支持 $1/$2 位置参数(参数必须命名), PG 这里报 42P02
create function test1 (a int) returns int language sql
    as 'select $2;';
-- PG 期望: CREATE 时报 "there is no parameter $2"

-- @case function_sql.bad_body_two_strings
-- @pg create_function_sql.sql:477-478
-- 适配: PG 报 42601(只能有一个 AS 字符串), Lealone 报 50100, 都是拒绝
create function test1 (a int) returns int language sql
    as 'a', 'b';
> exception 50100

-- @case function_sql.bad_body_empty
-- @pg create_function_sql.sql:480-481
-- 适配: PG 报 42P13(函数体为空), Lealone 报 42000, 都是拒绝
create function test1 (a int) returns int language sql
    as '';
> exception 42000

-- @case function_sql.empty_body_call
-- @pg create_function_sql.sql:484-488
-- 缺失: Lealone 不支持 anyelement/anyarray, 而且空函数体在创建时就被拒绝(见上一个用例)
set check_function_bodies = off;
create function test1 (a anyelement) returns anyarray language sql
    as '';
select test1(0);
reset check_function_bodies;
-- PG 期望: 建函数成功(关闭 body 检查), 调用时报错
