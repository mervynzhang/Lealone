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
-- 适配: Lealone 过程体里用 ? 引用参数(PG 用参数名 x); PG 的 collate "C" 换成普通的 order by;
--       断言改成"能查到新增的那一行", 不依赖前面用例留下的数据
create procedure ptest1s(x text)
language sql
begin atomic
  insert into cp_test values (1, ?);
end;
> update count: 0

call ptest1s('b');
> update count: 1

select count(*) c from cp_test where a = 1 and b = 'b';
> C
> -
> 1
> rows: 1

-- 适配: 后面的用例依赖 cp_test 的行数, 这里把自己插入的行删掉
delete from cp_test where b = 'b';
> update count: 1

-- @case procedure.utility_body
-- @pg create_procedure.sql:46-50
-- 适配: PG 11 对过程体里的 utility 语句报 0A000, Lealone 支持过程体里执行 DDL,
--       所以这里把断言改成"建过程成功 + CALL 后表真的建出来了"
create procedure ptestx()
language sql
begin atomic
  create table x (a int);
end;
> update count: 0

call ptestx();
> update count: 0

table x;
> A
> -
> rows: 0

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
-- @pg create_procedure.sql:77-102
-- 适配: 过程体里用 ? 按位置引用参数; ptest4c 是单个 INOUT 参数且类型是复合类型的情况
create procedure ptest4a(inout a int, inout b int) as $$ SELECT 1, 2; $$;
> update count: 0

call ptest4a(NULL, NULL);
> A B
> - -
> 1 2
> rows: 1

-- PG 的 ptest4b(INOUT 交叉传递 + 嵌套 CALL) 在 CREATE 阶段就报 0A000 "calling procedures with
-- output arguments is not supported in SQL functions"; Lealone 对齐为 50100 + 同样的信息

-- 单个 INOUT 参数是复合类型的情况(以前会解析混乱)
create type int8_tbl as (q1 bigint, q2 bigint);
> update count: 0

-- 过程体里调用带 OUT/INOUT 参数的过程在 CREATE 阶段就被拒绝(PG: 0A000, Lealone: 50100)
create procedure ptest4b(inout b int, inout a int) as $$ call ptest4a(?, ?) $$;
> exception 50100

create procedure ptest4c(inout comp int8_tbl) as $$ SELECT ROW(1, 2); $$;
> update count: 0

call ptest4c(NULL);
> COMP
> ------
> (1, 2)
> rows: 1

drop procedure ptest4a;
> update count: 0

drop procedure ptest4c;
> update count: 0

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
-- 适配: PG 里用 psql 的 \df 看过程定义, Lealone 的脚本执行器不支持元命令, 这里去掉
create procedure ptest8(x text)
begin atomic
end;
> update count: 0

call ptest8('');
> update count: 0

-- @case procedure.out_params
-- @pg create_procedure.sql:183-197
-- 适配: 过程体里用 ? 按位置引用参数; PG 的 ptest9 往 cp_test 插行, 后面的用例还要用 cp_test,
--       这里换成独立的 cp_test_out(语句与语义不变)
create table cp_test_out (a int, b text);
> update count: 0

create procedure ptest9(out a int) as $$ INSERT INTO cp_test_out VALUES (1, 'a'); SELECT 1; $$;
> update count: 0

call ptest9(NULL);
> A
> -
> 1
> rows: 1

-- OUT 位置的实参只占位不求值, 所以 1/0 不会报除零错误
call ptest9(1/0);
> A
> -
> 1
> rows: 1

-- 适配: PG 这里按 numeric 实参找不到过程(procedure ptest9(numeric) does not exist),
--       Lealone 允许 numeric 隐式转成 int, 按 Lealone 的实际行为断言成功(仍不求值)
call ptest9(1./0.);
> A
> -
> 1
> rows: 1

select * from cp_test_out;
> A B
> - -
> 1 a
> 1 a
> 1 a
> rows: 3

-- @case procedure.out_params_named
-- @pg create_procedure.sql:199-212
-- 缺失: Lealone 不支持命名实参(a => null)和过程的 VARIADIC 参数, 也不支持同名过程按
--       OUT/IN 参数区分重载(报 90150 "Procedure already exists")
create procedure ptest10(out a int, in b int, in c int) as $$ SELECT ? - ? $$;
call ptest10(null, 7, 4);
create procedure ptest11(a out int, variadic b int[]) as $$ SELECT ? + ? $$;
call ptest11(null, 11, 12, 13);
-- PG 期望: ptest10 的位置调用返回 3, 命名调用返回 6; ptest11 返回 36

-- @case procedure.ambiguous_drop
-- @pg create_procedure.sql:216-232
-- 适配: 过程体用 ? 按位置引用参数; 两个重载的入参个数不同(OUT版本2个入参, IN版本3个入参),
--       DROP不带签名或者两个重载都能匹配时报903152, 用IN/OUT模式可以精确指定
create procedure ptest10(out a int, in b int, in c int)
language sql as 'select ? - ?';
> update count: 0

create procedure ptest10(in a int, in b int, in c int)
language sql as 'select ? + ? - ?';
> update count: 0

drop procedure ptest10;
> exception 90152

drop procedure ptest10(int, int, int);
> exception 90152

begin;
> update count: 0

drop procedure ptest10(out int, int, int);
> update count: 0

drop procedure ptest10(int, int, int);
> update count: 0

rollback;
> update count: 0

begin;
> update count: 0

drop procedure ptest10(in int, int, int);
> update count: 0

drop procedure ptest10(int, int, int);
> update count: 0

rollback;
> update count: 0

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
-- 适配: PG 的过程只接受 SECURITY/SET 这一小撮属性, WINDOW/STRICT 会报错; Lealone 的过程和
--       函数共用同一套属性子句(ALTER PROCEDURE 本轮才支持), 所以前两条建过程和 ALTER PROCEDURE
--       按 Lealone 的实际行为断言成功;
--       最后的 DROP PROCEDURE ptest1 去掉了, 因为后面的清理用例还要用 ptest1
create procedure ptestx_attr() language sql window as $$ insert into cp_test values (1, 'a') $$;
> update count: 0

-- 同名同签名的过程只能有一个, 再建一次报 90150
create procedure ptestx_attr() language sql strict as $$ insert into cp_test values (1, 'a') $$;
> exception 90150

-- Lealone 的过程不支持 VARIADIC 参数: VARIADIC写在参数名位置上会被当成类型名, 报42102
create procedure ptestx_attr(a variadic int[], b out int) language sql
  as $$ select a[1] $$;
> exception 42102

alter procedure ptest1(text) strict;
> update count: 0

-- PG 期望: 42809 ("ptest1" is not a function), Lealone 报 90077(函数不存在)
alter function ptest1(text) volatile;
> exception 90077

-- PG 期望: 42809 ("cp_testfunc1" is not a procedure), Lealone 报 90151(过程不存在)
alter procedure cp_testfunc1(int) volatile;
> exception 90151

-- @case procedure.drop_wrong_kind
-- @pg create_procedure.sql:276-278
-- 适配: PG 报 42883(类型不对), Lealone 用 90077(函数不存在)/90151(过程不存在), 都是拒绝
drop function ptest1;
> exception 90077

drop procedure cp_testfunc1;
> exception 90151

drop procedure nonexistent;
> exception 90151

-- @case procedure.grant_execute
-- @pg create_procedure.sql:258-267
-- 适配: Lealone 没有 SET ROLE 和 PUBLIC 角色, 过程权限也不参与调用检查, 所以这里只验证
--       GRANT/REVOKE EXECUTE ON PROCEDURE|FUNCTION 的解析、过程重载的签名匹配和授权落库
create user cp_grant_user password 'x';
> update count: 0

grant execute on procedure ptest1(text) to cp_grant_user;
> update count: 0

revoke execute on procedure ptest1(text) from cp_grant_user;
> update count: 0

grant execute on function cp_testfunc1(int) to cp_grant_user;
> update count: 0

revoke execute on function cp_testfunc1(int) from cp_grant_user;
> update count: 0

-- 有重载时必须写参数类型
grant execute on procedure ptest10 to cp_grant_user;
> exception 90152

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

-- ---------------------------------------------------------------------------
-- PL/pgSQL过程体 + OUT/INOUT参数
-- ---------------------------------------------------------------------------

-- @case procedure.plpgsql_body
-- @pg create_procedure.sql:79-102
-- 适配: PG 的 INOUT 用例用 language sql + 参数名引用, Lealone 的过程体用 ? 引用参数;
--       这里用 PG 原样的 language plpgsql 写法验证 OUT/INOUT, 过程体写成一行避免被脚本按分号切断
create procedure ptest4d(in x int, out y int, inout z text) language plpgsql
as $$ begin y := x * 2; z := z || '!'; end $$;
> update count: 0

call ptest4d(3, 'a');
> Y Z
> - --
> 6 a!
> rows: 1

-- OUT参数写NULL占位是PostgreSQL的标准写法
call ptest4d(4, null, 'b');
> Y Z
> - --
> 8 b!
> rows: 1

-- plpgsql过程体里可以写循环和变量声明
create procedure ptest4e(in n int, out s int) language plpgsql
as $$ declare i int := 0; begin s := 0; while i < n loop i := i + 1; s := s + i; end loop; end $$;
> update count: 0

call ptest4e(4);
> S
> --
> 10
> rows: 1
