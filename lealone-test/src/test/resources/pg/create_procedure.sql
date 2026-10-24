-- PostgreSQL 回归测试 create_procedure.sql 的 Lealone 转换版。
-- 期望输出格式见 com.lealone.test.pg.PgRegressRunner。
--
-- 约定同 create_function_sql.sql。这份文件的适配点主要是:
--   1. PG 的过程写成 CREATE PROCEDURE p(x text) LANGUAGE SQL AS $$ INSERT ... VALUES (1, x) $$,
--      Lealone 的过程体是"用分号分隔的语句串, 参数用 ? 按位置引用", 所以过程体改成 VALUES (1, ?);
--   2. PG 的过程体里可以写 CALL 另一个过程、可以用 $1/参数名, Lealone 的过程体里统一用 ?;
--   3. PG 的 CALL 用 execute() 就能拿到结果集, Lealone 的过程体里有查询语句时也一样
--      (只有更新语句的过程仍按更新语句执行)。

-- ---------------------------------------------------------------------------
-- 基本的过程定义和调用
-- ---------------------------------------------------------------------------

-- @case procedure.call_nonexistent
-- @pg create_procedure.sql:1-1
-- 适配: PG 这两行是 CALL nonexistent() 和 CALL random(), 都报 42883;
--       Lealone 的 CALL 不区分函数和过程, CALL random() 会真的执行 random(见 procedure.call_function)
call nonexistent();
> exception 90022

-- @case procedure.call_random
-- @pg create_procedure.sql:2-2
-- 缺失: Lealone 的 CALL 不区分函数和过程, CALL random() 会直接执行 random() 并返回一个随机数;
--       PG 报 42809("random" is not a procedure)
call random();
-- PG 期望: 报 42809

-- @case procedure.create_function_and_procedure
-- @pg create_procedure.sql:4-12
-- 适配: 过程体用 ? 引用参数; PG 这段没有调用语句, 这里补上调用以验证写入
create function cp_testfunc1(a int) returns int language sql
    as 'select a';
> update count: 0

select cp_testfunc1(9) r;
> R
> -
> 9
> rows: 1

create table cp_test (a int, b text);
> update count: 0

create procedure ptest1(x text)
    as 'insert into cp_test values (1, ?)';
> update count: 0

-- PG 的调用在 :24
call ptest1('a');
> update count: 1

-- PG 的调用在 :25, 参数是常量折叠后的表达式
call ptest1('xy' || 'zzy');
> update count: 1

select a, b from cp_test order by b;
> A B
> - -----
> 1 a
> 1 xyzzy
> rows: 2

-- @case procedure.psql_meta
-- @pg create_procedure.sql:14-21
-- 缺失: \df/\dfn/\dfp 是 psql 元命令, Lealone 的客户端没有; 也没有 pg_get_functiondef()
\df ptest1
select pg_get_functiondef('ptest1'::regproc);
\dfn public.*test*1
\dfp public.*test*1
-- PG 期望: 打印出 ptest1 的过程定义

-- @case procedure.select_procedure
-- @pg create_procedure.sql:23
-- 适配: PG 报 42809("ptest1" is a procedure), Lealone 报 90022(不能当函数用), 都是拒绝
select ptest1('x');
> exception 90022

-- @case procedure.atomic_body
-- @pg create_procedure.sql:32-43
-- 缺失: Lealone 的过程体是分号分隔的 SQL 串, 不支持 SQL 标准体的 BEGIN ATOMIC ... END
create procedure ptest1s(x text)
language sql
begin atomic
  insert into cp_test values (1, x);
end;
call ptest1s('b');
select * from cp_test order by b collate "C";
-- PG 期望: CALL 成功, cp_test 多一行 (1,b)

-- @case procedure.utility_body
-- @pg create_procedure.sql:46-50
-- 缺失: Lealone 的过程体里不能执行大部分 DDL(文档里标注为暂不支持)
create procedure ptestx()
language sql
begin atomic
  create table x (a int);
end;
-- PG 期望: 报 0A000, 过程体里不能执行 utility 语句

-- @case procedure.body_select
-- @pg create_procedure.sql:53-59
-- 适配: CALL 的过程体是 SELECT 时按查询语句执行, Statement.execute() 也能拿到结果集
-- 适配: Lealone 的 CREATE PROCEDURE 没有 LANGUAGE 子句
create procedure ptest2()
as $$ select 5 $$;
> update count: 0

call ptest2();
> 5
> -
> 5
> rows: 1

-- @case procedure.nested_call
-- @pg create_procedure.sql:63-74
-- 适配: PG 过程体里用参数名和 $1 引用参数, Lealone 过程体里统一用 ?;
--       过程体的每条语句各自独立绑定参数
create procedure ptest3(y text)
as $$ call ptest1(?); call ptest1(?) $$;
> update count: 0

call ptest3('b');
> update count: 2

select * from cp_test;
> A B
> - -----
> 1 a
> 1 b
> 1 b
> 1 xyzzy
> rows: 4

-- @case procedure.inout_params
-- @pg create_procedure.sql:79-102
-- 缺失: Lealone 的 CREATE PROCEDURE 只接受 IN 参数(写 INOUT/OUT 会报 42102 Table "X" not found);
--       同样的能力在 CREATE FUNCTION 里是支持的(见 plpgsql.inout_return / plpgsql.multi_out)
create procedure ptest4a(inout a int, inout b int)
language sql
as $$
select 1, 2;
$$;
call ptest4a(null, null);
create procedure ptest4b(inout b int, inout a int)
language sql
as $$
call ptest4a(a, b);
$$;
create procedure ptest4c(inout comp int8_tbl)
language sql
as $$
select row(1, 2);
$$;
call ptest4c(null);
drop procedure ptest4a, ptest4c;
-- PG 期望: CALL ptest4a(null,null) 返回 (1,2); ptest4b 里嵌套调用报 0A000

-- @case procedure.named_default
-- @pg create_procedure.sql:107-121
-- 缺失: Lealone 不支持 DEFAULT 参数和命名实参(b => 'Hello')
create or replace procedure ptest5(a int, b text, c int default 100)
language sql
as $$
insert into cp_test values(a, b);
insert into cp_test values(c, b);
$$;
truncate cp_test;
call ptest5(10, 'Hello', 20);
call ptest5(10, 'Hello');
call ptest5(10, b => 'Hello');
call ptest5(b => 'Hello', a => 10);
select * from cp_test;
-- PG 期望: 4 次调用都成功, cp_test 里 4 行

-- @case procedure.polymorphic
-- @pg create_procedure.sql:126-159
-- 缺失: Lealone 无 anyelement/anyarray 多态类型
create procedure ptest6(a int, b anyelement)
language sql
as $$
select null::int;
$$;
call ptest6(1, 2);
create procedure ptest6a(inout a anyelement, out b anyelement)
language sql
as $$
select $1, $1;
$$;
call ptest6a(1, null);
-- PG 期望: ptest6(1,2) 返回一行 NULL; ptest6a 返回 (1,1)

-- @case procedure.collation
-- @pg create_procedure.sql:164-170
-- 适配: PG 的过程体是 LANGUAGE SQL + 参数名, Lealone 过程体用 ? 按位置引用
create procedure ptest7(a text, b text) as 'select ? = ?';
> update count: 0

call ptest7(least('a', 'b'), 'a');
> ?1 = ?2
> -------
> TRUE
> rows: 1

-- @case procedure.empty_body
-- @pg create_procedure.sql:174-180
-- 缺失: Lealone 的过程体不能为空, 也不支持 BEGIN ATOMIC
create procedure ptest8(x text)
begin atomic
end;
\df ptest8
call ptest8('');
-- PG 期望: 建空过程成功, CALL 什么都不做

-- @case procedure.out_params
-- @pg create_procedure.sql:185-212
-- 缺失: CREATE PROCEDURE 只接受 IN 参数(42102), 也不支持命名实参/DEFAULT(VARIADIC/OUT 在 CREATE FUNCTION 里已支持)
create procedure ptest9(out a int)
language sql
as $$
insert into cp_test values (1, 'a');
select 1;
$$;
call ptest9(null);
call ptest9(1/0);
call ptest9(1./0.);
create procedure ptest10(out a int, in b int, in c int)
language sql as $$ select b - c $$;
call ptest10(null, 7, 4);
call ptest10(a => null, b => 8, c => 2);
create procedure ptest11(a out int, variadic b int[]) language sql
  as $$ select b[1] + b[2] $$;
call ptest11(null, 11, 12, 13);
-- PG 期望: ptest9(null) 返回 (1); ptest10(null,7,4) 返回 (3); ptest11 返回 (25)

-- @case procedure.ambiguous_drop
-- @pg create_procedure.sql:216-232
-- 缺失: 同 procedure.overload, Lealone 的过程按名字唯一, 没有"按参数列表区分重载"导致的 DROP 歧义
create procedure ptest10(in a int, in b int, in c int)
language sql as $$ select a + b - c $$;
drop procedure ptest10;                 -- PG 期望: 失败(有重载)
drop procedure ptest10(int, int, int);  -- PG 期望: 失败(仍有重载)
begin;
drop procedure ptest10(out int, int, int);
drop procedure ptest10(int, int, int);  -- PG 期望: 成功
rollback;
-- PG 期望: 只有按参数类型精确匹配的 DROP 才能成功

-- ---------------------------------------------------------------------------
-- 各种错误用例
-- ---------------------------------------------------------------------------

-- @case procedure.call_function
-- @pg create_procedure.sql:236-237
-- 缺失: Lealone 的 CALL 不区分函数和过程, CALL random()/CALL sum(1) 会直接执行函数并返回结果;
--       PG 报 42809("random" is not a procedure)
call version();
call sum(1);
-- PG 期望: 两条都报 42809

-- @case procedure.alter_and_bad_def
-- @pg create_procedure.sql:239-249
-- 缺失: Lealone 不支持 ALTER PROCEDURE/ALTER FUNCTION, 不支持 WINDOW/STRICT/VARIADIC/DEFAULT 参数
create procedure ptestx() language sql window as $$ insert into cp_test values (1, 'a') $$;
create procedure ptestx() language sql strict as $$ insert into cp_test values (1, 'a') $$;
create procedure ptestx(a variadic int[], b out int) language sql
  as $$ select a[1] $$;
alter procedure ptest1(text) strict;
alter function ptest1(text) volatile;      -- PG 期望: 失败(不是函数)
alter procedure cp_testfunc1(int) volatile; -- PG 期望: 失败(不是过程)
drop procedure ptest1;
-- PG 期望: 前面几条建过程/ALTER 都报错, 最后 DROP PROCEDURE ptest1 成功

-- @case procedure.drop_wrong_kind
-- @pg create_procedure.sql:276-278
-- 适配: PG 报 42883(类型不对), Lealone 用 90077(函数不存在)/90151(过程不存在), 都是拒绝
drop function ptest1;
> exception 90077

drop procedure cp_testfunc1;
> exception 90151

drop procedure nonexistent;
> exception 90151

-- @case procedure.privileges
-- @pg create_procedure.sql:258-267
-- 缺失: Lealone 没有 REVOKE EXECUTE ON PROCEDURE / SET ROLE 这套过程权限语义
create user regress_cp_user1;
grant insert on cp_test to regress_cp_user1;
revoke execute on procedure ptest1(text) from public;
set role regress_cp_user1;
call ptest1('a');  -- PG 期望: 权限不足
reset role;
-- PG 期望: 第一条 CALL 报 42501, 授权后再 CALL 成功

-- @case procedure.routine_syntax
-- @pg create_procedure.sql:272-278
-- 缺失: Lealone 不支持 ALTER ROUTINE ... RENAME 和 DROP ROUTINE
alter routine cp_testfunc1(int) rename to cp_testfunc1a;
alter routine cp_testfunc1a rename to cp_testfunc1;
drop routine cp_testfunc1(int);
-- PG 期望: 重命名往返成功, DROP ROUTINE 成功

-- ---------------------------------------------------------------------------
-- 清理
-- ---------------------------------------------------------------------------

-- @case procedure.drop_and_call
-- @pg create_procedure.sql:283-289
-- 适配: PG 的清理段直接 DROP, 这里先验证"删了以后再 CALL 报错, 再 DROP IF EXISTS 不报错"
call ptest1('b');
> update count: 1

select a, b from cp_test order by b;
> A B
> - -----
> 1 a
> 1 b
> 1 b
> 1 b
> 1 xyzzy
> rows: 5

drop procedure ptest1;
> update count: 0

call ptest1('z');
> exception 90022

drop procedure if exists ptest1;
> update count: 0

truncate table cp_test;
> update count: 0

select count(*) c from cp_test;
> C
> -
> 0
> rows: 1
