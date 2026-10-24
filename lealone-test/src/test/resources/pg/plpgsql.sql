-- PostgreSQL 回归测试 plpgsql.sql 的 Lealone 转换版。
--
-- plpgsql.sql 有 4756 行(935 条语句), 是 PG 函数/过程回归里最大的一块:
-- 前面 118 行是 patchfield 场景的建表, 之后几乎全是 PL/pgSQL 函数和触发器。
--
-- Lealone 现在支持 PL/pgSQL 的一个子集(DECLARE/BEGIN..END/IF/ELSIF/LOOP/WHILE/FOR/RETURN/
-- RAISE/EXCEPTION/PERFORM/SELECT INTO/变量与赋值/内嵌DML), 所以这里逐段转了一批用例过来;
-- 触发器、FOUND 魔法变量、游标、RETURNS SETOF/TABLE、多个 OUT 参数、RECORD/%ROWTYPE、
-- CASE 语句、EXECUTE 动态 SQL、GET DIAGNOSTICS、ASSERT 仍然不支持, 对应用例留在脚本里被 skip,
-- 原因写在 pg/skip-list.txt, 每条都带 PG 行号。
--
-- 转换时的固定改法(脚本里每个用例都注明了):
--   1. Lealone 的脚本一行就是一条语句(以 ';' 结尾), 所以函数体写成一行:
--      AS $$ DECLARE ...; BEGIN ...; END; $$;
--   2. PG 的无名参数/`$1` 改成命名参数, 函数体里按名字引用;
--   3. 被 skip 的用例保持 PG 原样的语句, 期望值以 "-- PG 期望:" 注释给出。

-- ---------------------------------------------------------------------------
-- 触发器函数(整块 skip: 没有 CREATE TRIGGER)
-- ---------------------------------------------------------------------------

-- @case plpgsql.trigger_room
-- @pg plpgsql.sql:130-140
-- 缺失: Lealone 无 CREATE TRIGGER 与触发器函数(RETURNS trigger)
create function tg_room_au() returns trigger as '
begin
    if new.roomno != old.roomno then
        update WSlot set roomno = new.roomno where roomno = old.roomno;
    end if;
    return new;
end;
' language plpgsql;

create trigger tg_room_au after update
    on Room for each row execute procedure tg_room_au();

-- @case plpgsql.trigger_raise
-- @pg plpgsql.sql:162-172
-- 缺失: Lealone 无 CREATE TRIGGER 与触发器函数(RETURNS trigger)
create function tg_wslot_biu() returns trigger as $$
begin
    if count(*) = 0 from Room where roomno = new.roomno then
        raise exception 'Room % does not exist', new.roomno;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger tg_wslot_biu before insert or update
    on WSlot for each row execute procedure tg_wslot_biu();

-- ---------------------------------------------------------------------------
-- 递归
-- ---------------------------------------------------------------------------

-- @case plpgsql.recursion
-- @pg plpgsql.sql:1429-1440
-- 适配: PG 的声明是 recursion_test(int,int) + 函数体用 $1/$2, 这里改成命名参数 a/b
create function recursion_test(a int, b int) returns varchar language plpgsql as
$$ DECLARE rslt varchar; BEGIN IF a <= 0 THEN rslt := CAST(b AS VARCHAR); ELSE rslt := CAST(a AS VARCHAR) || ',' || recursion_test(a - 1, b); END IF; RETURN rslt; END; $$;
> update count: 0

select recursion_test(4,3) r;
> R
> ---------
> 4,3,2,1,3
> rows: 1

-- ---------------------------------------------------------------------------
-- FOUND 魔法变量 (整块 skip)
-- ---------------------------------------------------------------------------

-- @case plpgsql.found
-- @pg plpgsql.sql:1445-1484
-- 适配: 函数体写成一行(脚本引擎按行末 ';' 切语句); 语义和 PG 完全一样:
--       INSERT/UPDATE/DELETE/FOR 之后 FOUND 分别表示"影响了行/查到了行/循环有迭代"
create table found_test_tbl (a int);
> update count: 0

create function test_found() returns boolean as $$ declare begin insert into found_test_tbl values (1); if FOUND then insert into found_test_tbl values (2); end if; update found_test_tbl set a = 100 where a = 1; if FOUND then insert into found_test_tbl values (3); end if; delete from found_test_tbl where a = 9999; if not FOUND then insert into found_test_tbl values (4); end if; for i in 1 .. 10 loop end loop; if FOUND then insert into found_test_tbl values (5); end if; for i in 2 .. 1 loop end loop; if not FOUND then insert into found_test_tbl values (6); end if; return true; end; $$ language plpgsql;
> update count: 0

select test_found() r;
> R
> ----
> TRUE
> rows: 1

-- 和 PG 一样的 6 行: 2 来自 INSERT 后的 FOUND, 100 是 UPDATE 改出来的, 3/5 是 UPDATE/FOR 后 FOUND,
-- 4/6 是 DELETE 没删到行、FOR 没有迭代时 NOT FOUND
select * from found_test_tbl;
> A
> ---
> 2
> 100
> 3
> 4
> 5
> 6
> rows: 6

-- @case plpgsql.for_loop_setof
-- @pg plpgsql.sql:1514-1524
-- 适配: PG 有 (int,int) 参数 + $1/$2, 这里改成命名参数 a/b
create function test_ret_set_scalar(a int, b int) returns setof int as $$ DECLARE i int; BEGIN FOR i IN a .. b LOOP RETURN NEXT i + 1; END LOOP; RETURN; END; $$ language plpgsql;
> update count: 0

select * from test_ret_set_scalar(1,10);
> TEST_RET_SET_SCALAR
> -------------------
> 2
> 3
> 4
> 5
> 6
> 7
> 8
> 9
> 10
> 11
> rows: 10

-- @case plpgsql.setof_composite_record
-- @pg plpgsql.sql:1487-1512
-- 缺失: RETURNS SETOF <表行类型> 不支持(和 SQL 函数一样, 只支持标量 setof/RETURNS TABLE),
--       DECLARE rec RECORD / row 表%ROWTYPE 本身已经可用, 卡在返回类型上
create function test_table_func_rec() returns setof found_test_tbl as '
DECLARE
	rec RECORD;
BEGIN
	FOR rec IN select * from found_test_tbl LOOP
		RETURN NEXT rec;
	END LOOP;
	RETURN;
END;' language plpgsql;
select * from test_table_func_rec();
create function test_table_func_row() returns setof found_test_tbl as '
DECLARE
	row found_test_tbl%ROWTYPE;
BEGIN
	FOR row IN select * from found_test_tbl LOOP
		RETURN NEXT row;
	END LOOP;
	RETURN;
END;' language plpgsql;
select * from test_table_func_row();
-- PG 期望: 两张表里的行原样返回

-- @case plpgsql.polymorphic
-- @pg plpgsql.sql:1565-1568
-- 缺失: Lealone 无 anyelement/anyarray 多态参数类型
create function f1(x anyelement) returns anyelement as $$
begin
  return x + 1;
end
$$ language plpgsql;
select f1(42) as int;
-- PG 期望: 43

-- ---------------------------------------------------------------------------
-- OUT / INOUT 参数
-- ---------------------------------------------------------------------------

-- @case plpgsql.out_return
-- @pg plpgsql.sql:1683-1686
-- 适配: 单 OUT 参数 + RETURN 表达式; PG 这里还写了 RETURNS int, Lealone 允许省略 RETURNS
create function functest_out(i int, out j int) returns int as $$ begin return i + 1; end $$ language plpgsql;
> update count: 0

select functest_out(42) r;
> R
> --
> 43
> rows: 1

-- @case plpgsql.out_assign
-- @pg plpgsql.sql:1688-1695
-- 适配: 函数体写成一行; 有 OUT 参数时裸 RETURN; 和自然结束都按 PG 语义返回 OUT 参数的值
create function functest_out2(i int, out j int) as $$ begin j := i + 1; return; end $$ language plpgsql;
> update count: 0

select functest_out2(42) r;
> R
> --
> 43
> rows: 1

select * from functest_out2(42);
> FUNCTEST_OUT2
> -------------
> 43
> rows: 1

-- @case plpgsql.inout_return
-- @pg plpgsql.sql:1697-1703
-- 适配: 函数体写成一行; PG 里 INOUT 函数给参数赋值后自然结束即可, 这里保持 PG 原样
create or replace function functest_inout(inout i int) as $$ begin i := i + 1; end $$ language plpgsql;
> update count: 0

select functest_inout(42) r;
> R
> --
> 43
> rows: 1

select * from functest_inout(42);
> FUNCTEST_INOUT
> --------------
> 43
> rows: 1

-- @case plpgsql.multi_out
-- @pg plpgsql.sql:1720-1728
-- 适配: 函数体写成一行; record 在值上下文里用ValueArray表示, getString 是 "(43, foo)"
create function functest_multi(i int, out j int, out k text) as $$ begin j := i; j := j + 1; k := 'foo'; end$$ language plpgsql;
> update count: 0

select functest_multi(42);
> FUNCTEST_MULTI(42)
> ------------------
> (43, foo)
> rows: 1

select * from functest_multi(42);
> J  K
> -- ---
> 43 foo
> rows: 1

-- @case plpgsql.perform
-- @pg plpgsql.sql:1771-1811
-- 适配: 函数体写成一行; PERFORM 之后 FOUND 表示被调用的函数是否返回了行
create table perform_test (a int, b int);
> update count: 0

create function perform_simple_func(p int) returns boolean as $$ BEGIN IF p < 20 THEN INSERT INTO perform_test VALUES (p, p + 10); RETURN TRUE; ELSE RETURN FALSE; END IF; END; $$ language plpgsql;
> update count: 0

create function perform_test_func() returns void as $$ BEGIN IF FOUND THEN INSERT INTO perform_test VALUES (100, 100); END IF; PERFORM perform_simple_func(5); IF FOUND THEN INSERT INTO perform_test VALUES (100, 100); END IF; PERFORM perform_simple_func(50); IF FOUND THEN INSERT INTO perform_test VALUES (100, 100); END IF; RETURN; END; $$ language plpgsql;
> update count: 0

select perform_test_func() r;
> R
> ----
> null
> rows: 1

-- 只有第一次 PERFORM 返回了行(TRUE), 第二次返回 FALSE, 所以 100,100 只插入两次
select * from perform_test;
> A   B
> --- ---
> 5   15
> 100 100
> 100 100
> rows: 3

-- @case plpgsql.select_into
-- @pg plpgsql.sql:1819-1832
-- 适配: 函数体写成一行; 表的 id 用显式值代替 serial(期望值固定), 其余和 PG 一样用 FOUND 判断
create temp table users(login varchar, id int);
> update count: 0

create function sp_id_user(a_login varchar) returns int as $$ declare x int; begin select into x id from users where login = a_login; if FOUND then return x; end if; return 0; end$$ language plpgsql stable;
> update count: 0

insert into users values('user1', 1);
> update count: 1

select sp_id_user('user1') r1, sp_id_user('userx') r2;
> R1 R2
> -- --
> 1  0
> rows: 1

-- @case plpgsql.select_into_insert
-- @pg plpgsql.sql:1834-1853
-- 适配: 同 plpgsql.select_into, id 用显式值; PG 里 user2 拿到的是 serial 生成的 2, 这里固定成 100
create function sp_add_user(a_login varchar) returns int as $$ declare my_id_user int; begin my_id_user = sp_id_user(a_login); if my_id_user > 0 then return -1; end if; insert into users (login, id) values (a_login, 100); my_id_user = sp_id_user(a_login); if my_id_user = 0 then return -2; end if; return my_id_user; end $$ language plpgsql;
> update count: 0

select sp_add_user('user1') r1, sp_add_user('user2') r2, sp_add_user('user2') r3;
> R1 R2  R3
> -- --- --
> -1 100 -1
> rows: 1

-- @case plpgsql.cursor
-- @pg plpgsql.sql:1859-1900
-- 缺失: PL/pgSQL 游标(OPEN/FETCH/CLOSE)和 refcursor 类型都不支持, DDL 阶段报 50100
create function refcursor_test1(refcursor) returns refcursor as $$
begin
   open $1 for select name from pg_class;
   return $1;
end;
$$ language plpgsql;
select refcursor_test1('test1');
-- PG 期望: refcursor 值 test1

-- ---------------------------------------------------------------------------
-- RAISE
-- ---------------------------------------------------------------------------

-- @case plpgsql.raise_notice
-- @pg plpgsql.sql:2085-2106
-- 适配: 参数改成命名参数; raise_test1/2 在 PG 里因为占位符个数不对而报错, Lealone 也报错(50000)
create function raise_test1(p int) returns int as $$ begin raise notice 'This message has too many parameters!', p; return p; end $$ language plpgsql;
> update count: 0

select raise_test1(1) r;
> exception 50000

create function raise_test2(p int) returns int as $$ begin raise notice 'This message has too few parameters: %, %, %', p, p; return p; end $$ language plpgsql;
> update count: 0

select raise_test2(1) r;
> exception 50000

-- %% 是转义的字面量 %, 没有占位符时正常执行
create function raise_test3(p int) returns int as $$ begin raise notice 'This message has no parameters (despite having %% signs in it)!'; return p; end $$ language plpgsql;
> update count: 0

select raise_test3(1) r;
> R
> -
> 1
> rows: 1

-- @case plpgsql.exception_reraise
-- @pg plpgsql.sql:2111-2131
-- 适配: PG 用 RAISE syntax_error 抛命名条件, Lealone 只支持 RAISE EXCEPTION/SQLSTATE, 这里改成 RAISE EXCEPTION;
--       嵌套 EXCEPTION 里再 RAISE 重新抛出的语义保持一致
create function reraise_test() returns varchar as $$ begin begin raise exception 'syntax error'; exception when others then begin raise notice 'exception thrown in inner block, reraising'; raise; exception when others then raise notice 'RIGHT - exception caught in inner block'; end; end; return 'done'; end $$ language plpgsql;
> update count: 0

-- 重新抛出的异常被内层的 WHEN OTHERS 捕获, 函数正常返回
select reraise_test() r;
> R
> ----
> done
> rows: 1

-- @case plpgsql.record
-- @pg plpgsql.sql:2313-2321
-- 缺失: 这段用的是 INSERT ... RETURNING * INTO x record, Lealone 引擎没有 DML RETURNING,
--       record 变量本身的 SELECT INTO / x.f1 读写已经可以用
create or replace function stricttest() returns void as $$
declare x record;
begin
  insert into foo values(5,6) returning * into x;
  raise notice 'x.f1 = %, x.f2 = %', x.f1, x.f2;
end$$ language plpgsql;
-- PG 期望: 打印 x.f1 = 5, x.f2 = 6

-- @case plpgsql.out_params_exception
-- @pg plpgsql.sql:3609-3638
-- 缺失: leaker_2 的返回类型是 RETURNS RECORD(复合返回类型不支持), leaker_1 里还有
--       (leaker_2(fail)).error_code 这种复合返回值取字段的写法
CREATE FUNCTION leaker_1(fail BOOL) RETURNS INTEGER AS $$
DECLARE
  v_var INTEGER;
BEGIN
  BEGIN
    v_var := (leaker_2(fail)).error_code;
  EXCEPTION
    WHEN others THEN RETURN 0;
  END;
  RETURN 1;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION leaker_2(fail BOOL, OUT error_code INTEGER, OUT new_id INTEGER)
  RETURNS RECORD AS $$
BEGIN
  IF fail THEN
    RAISE EXCEPTION 'fail ...';
  END IF;
  error_code := 1;
  new_id := 1;
  RETURN;
END;
$$ LANGUAGE plpgsql;
SELECT * FROM leaker_1(false);
SELECT * FROM leaker_1(true);
-- PG 期望: 1 和 0

-- ---------------------------------------------------------------------------
-- 块标签与限定名(第二轮 PL/pgSQL 补齐后可用)
-- ---------------------------------------------------------------------------

-- @case plpgsql.qualified_names
-- @pg plpgsql.sql:2841-2858
-- 适配: 函数体写成一行; 同名变量在不同块里, 用 块标签.变量 取外层/内层的值(PG 的输出是
--       param1 = 2 / outerblock.param1 = 1 / innerblock.param1 = 2, 用 NOTICE 打印, 这里只断言返回值)
create function pl_qual_names(param1 int) returns void as $$ <<outerblock>> DECLARE param1 int := 1; BEGIN <<innerblock>> DECLARE param1 int := 2; BEGIN RAISE NOTICE 'param1 = %', param1; RAISE NOTICE 'outerblock.param1 = %', outerblock.param1; RAISE NOTICE 'innerblock.param1 = %', innerblock.param1; END; END; $$ language plpgsql;
> update count: 0

select pl_qual_names(42) r;
> R
> ----
> null
> rows: 1

drop function pl_qual_names(int);
> update count: 0

-- ---------------------------------------------------------------------------
-- 表函数: RETURN QUERY / RETURN NEXT + RETURNS TABLE
-- ---------------------------------------------------------------------------

-- @case plpgsql.table_function
-- @pg plpgsql.sql:3540-3546
-- 适配: PG 用 generate_series(1,5) g(i), Lealone 没有 generate_series, 换成等价的
--       SYSTEM_RANGE(1,5)(列名 X); $1 改成命名参数 lim
create function tftest(lim int) returns table(a int, b int) as $$ begin return query select lim, lim + x from system_range(1,5); end; $$ language plpgsql immutable strict;
> update count: 0

select * from tftest(10);
> A  B
> -- --
> 10 11
> 10 12
> 10 13
> 10 14
> 10 15
> rows: 5

-- @case plpgsql.table_function_next
-- @pg plpgsql.sql:3548-3557
-- RETURNS TABLE 的列在函数体里就是变量, 赋值后 RETURN NEXT 一行一行吐出来
create or replace function tftest(a1 int) returns table(a int, b int) as $$ begin a := a1; b := a1 + 1; return next; a := a1 * 10; b := a1 * 10 + 1; return next; end; $$ language plpgsql immutable strict;
> update count: 0

select * from tftest(10);
> A   B
> --- ---
> 10  11
> 100 101
> rows: 2

drop function tftest(int);
> update count: 0

-- ---------------------------------------------------------------------------
-- RETURN QUERY + GET DIAGNOSTICS ROW_COUNT
-- ---------------------------------------------------------------------------

-- @case plpgsql.return_query_row_count
-- @pg plpgsql.sql:3561-3570
-- 适配: 只保留 PG 这段里不依赖 EXECUTE 动态 SQL 的前半段(VALUES 形式 + 空结果);
--       NOTICE 里的 FOUND/ROW_COUNT 就是 PG 期望的 "TRUE 2" 和 "FALSE 0"
create function rttest() returns setof int as $$ DECLARE rc int; BEGIN RETURN QUERY VALUES(10),(20); GET DIAGNOSTICS rc = ROW_COUNT; RAISE NOTICE '% %', FOUND, rc; RETURN QUERY SELECT * FROM (VALUES(10),(20)) f(a) WHERE false; GET DIAGNOSTICS rc = ROW_COUNT; RAISE NOTICE '% %', FOUND, rc; END; $$ language plpgsql;
> update count: 0

select * from rttest();
> RTTEST
> ------
> 10
> 20
> rows: 2

