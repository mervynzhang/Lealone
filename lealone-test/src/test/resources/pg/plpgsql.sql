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
-- 缺失: Lealone 的 PL/pgSQL 没有 FOUND 魔法变量, IF FOUND 会报 42122 Column "FOUND" not found
create table found_test_tbl (a int);
create function test_found() returns boolean as '
  declare
  begin
  insert into found_test_tbl values (1);
  if FOUND then
     insert into found_test_tbl values (2);
  end if;
  update found_test_tbl set a = 100 where a = 1;
  if FOUND then
    insert into found_test_tbl values (3);
  end if;
  delete from found_test_tbl where a = 9999;
  if not FOUND then
    insert into found_test_tbl values (4);
  end if;
  return true;
  end;' language plpgsql;
select test_found();
-- PG 期望: t, found_test_tbl 里是 100/2/3/4

-- ---------------------------------------------------------------------------
-- PL/pgSQL 的 set-returning 函数 (整块 skip)
-- ---------------------------------------------------------------------------

-- @case plpgsql.for_loop_setof
-- @pg plpgsql.sql:1490-1524
-- 缺失: PL/pgSQL × SETOF/TABLE 在 DDL 阶段报 50100 (SETOF/TABLE, multiple OUT or array parameters in PL/pgSQL function)
create function test_ret_set_scalar(a int, b int) returns setof int as '
DECLARE
	i int;
BEGIN
	FOR i IN a .. b LOOP
		RETURN NEXT i + 1;
	END LOOP;
	RETURN;
END;' language plpgsql;
select * from test_ret_set_scalar(1,10);
-- PG 期望: 2..11 共 10 行

-- ---------------------------------------------------------------------------
-- 多态参数 (整块 skip)
-- ---------------------------------------------------------------------------

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
-- 缺陷: 给 OUT 参数赋值后用裸 RETURN; 收尾是 PG 的常规写法, 但 Lealone 报
--       50000 "RETURN; cannot be used in a function returning a value"(有 OUT/INOUT 参数时也算有返回值)
create function functest_out2(i int, out j int) as $$
begin
  j := i + 1;
  return;
end $$ language plpgsql;
select functest_out2(42);
-- PG 期望: 43

-- @case plpgsql.inout_return
-- @pg plpgsql.sql:1697-1703
-- 适配: PG 里 INOUT 函数靠给参数赋值后自然结束, Lealone 要求显式 RETURN 值(见 plpgsql.out_assign)
create or replace function functest_inout(inout i int) as $$ begin i := i + 1; return i; end $$ language plpgsql;
> update count: 0

select functest_inout(42) r;
> R
> --
> 43
> rows: 1

-- @case plpgsql.multi_out
-- @pg plpgsql.sql:1720-1728
-- 缺失: 多个 OUT 参数的 PL/pgSQL 函数在 DDL 阶段报 50100
create function functest_multi(i int, out j int, out k text) as $$
begin
  j := i;
  j := j + 1;
  k := 'foo';
end$$ language plpgsql;
select * from functest_multi(42);
-- PG 期望: (43, foo)

-- ---------------------------------------------------------------------------
-- PERFORM
-- ---------------------------------------------------------------------------

-- @case plpgsql.perform
-- @pg plpgsql.sql:1774-1811
-- 缺失: 这段依赖 FOUND 魔法变量(PERFORM 之后检查 FOUND)
create table perform_test (a int, b int);
create function perform_simple_func(p int) returns boolean as '
BEGIN
	IF p < 20 THEN
		INSERT INTO perform_test VALUES (p, p + 10);
		RETURN TRUE;
	ELSE
		RETURN FALSE;
	END IF;
END;' language plpgsql;
create function perform_test_func() returns void as '
BEGIN
	IF FOUND then
		INSERT INTO perform_test VALUES (100, 100);
	END IF;
	PERFORM perform_simple_func(5);
	IF FOUND then
		INSERT INTO perform_test VALUES (100, 100);
	END IF;
	RETURN;
END;' language plpgsql;
select perform_test_func();
select * from perform_test;
-- PG 期望: perform_test 里是 (5,15) 和 (100,100)

-- ---------------------------------------------------------------------------
-- SELECT INTO
-- ---------------------------------------------------------------------------

-- @case plpgsql.select_into
-- @pg plpgsql.sql:1819-1832
-- 适配: PG 用 FOUND 判断查到没查到, Lealone 没有 FOUND, 改成判断取到的变量是否为 NULL;
--       表的 id 用显式值代替 serial, 让期望值固定
create temp table users(login varchar, id int);
> update count: 0

create function sp_id_user(a_login varchar) returns int as $$ declare x int; begin select into x id from users where login = a_login; if x is not null then return x; end if; return 0; end $$ language plpgsql stable;
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
-- 缺陷: DECLARE x record / 表的%ROWTYPE 能建函数, 但调用时报 42001(内部生成 CAST(NULL AS RECORD) 的 SQL 语法错)
create or replace function stricttest() returns void as $$
declare x record;
begin
  insert into foo values(5,6) returning * into x;
  raise notice 'x.f1 = %, x.f2 = %', x.f1, x.f2;
end$$ language plpgsql;
-- PG 期望: 打印 x.f1 = 5, x.f2 = 6

-- ---------------------------------------------------------------------------
-- 带 OUT 参数 + 异常处理 (整块 skip)
-- ---------------------------------------------------------------------------

-- @case plpgsql.out_params_exception
-- @pg plpgsql.sql:3609-3638
-- 缺失: leaker_2 用了 OUT 参数 + RETURNS RECORD, RECORD 不支持(见 plpgsql.record);
--       leaker_1 里还有 (leaker_2(fail)).error_code 这种复合字段访问
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
