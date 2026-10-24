-- PostgreSQL 回归测试 plpgsql.sql 的 Lealone 转换版。
--
-- plpgsql.sql 有 4756 行(935 条语句), 是 PG 函数/过程回归里最大的一块, 全部是 PL/pgSQL
-- 块语言 + 触发器。Lealone 目前没有 PL/pgSQL(LANGUAGE plpgsql 直接报 FEATURE_NOT_SUPPORTED),
-- 所以这个文件的用例一条都跑不了。为了不把"整份文件跳过"变成一句空话, 这里按 PG 原文
-- 逐段保留了 6 个有代表性的用例(触发器函数、RAISE、IF 递归、FOR..LOOP + RETURN NEXT、
-- 嵌套 EXCEPTION、OUT 参数 + RECORD), 它们的 id 在 pg/skip-list.txt 里, 原因统一记为
-- "无 PL/pgSQL", 每条都带 PG 行号, 将来实现 PL/pgSQL 时按顺序打开即可。
--
-- 这些用例不执行(在 skip 清单里), 里面的语句保持 PG 原样, 期望值以 "-- PG 期望:" 注释给出,
-- 所以这里没有 '>' 期望输出。表结构定义(create table Room 等)在 PG 文件开头 118 行之前,
-- 这里没有抄, 打开用例时需要一起补上。

-- @case plpgsql.trigger_room
-- @pg plpgsql.sql:130-140
-- 缺失: Lealone 无 PL/pgSQL 与 CREATE TRIGGER
-- PG 期望: 建触发器函数和触发器成功; 更新 Room.roomno 时 WSlot.roomno 跟着变
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
-- 缺失: Lealone 无 PL/pgSQL 与 CREATE TRIGGER
-- PG 期望: 往不存在的房间插 WSlot 时报 'Room % does not exist'
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

-- @case plpgsql.recursion
-- @pg plpgsql.sql:1429-1440
-- 缺失: Lealone 无 PL/pgSQL 块语言(BEGIN..END/IF/LOOP/EXCEPTION)
-- PG 期望: SELECT recursion_test(4,3) 返回 '4,3,2,3,1,3'
CREATE FUNCTION recursion_test(int,int) RETURNS text AS '
DECLARE rslt text;
BEGIN
    IF $1 <= 0 THEN
        rslt = CAST($2 AS TEXT);
    ELSE
        rslt = CAST($1 AS TEXT) || '','' || recursion_test($1 - 1, $2);
    END IF;
    RETURN rslt;
END;' LANGUAGE plpgsql;

SELECT recursion_test(4,3);

-- @case plpgsql.for_loop_setof
-- @pg plpgsql.sql:1490-1524
-- 缺失: Lealone 无 PL/pgSQL 块语言(BEGIN..END/IF/LOOP/EXCEPTION)
-- PG 期望: 三个 setof 函数各自返回表 found_test_tbl 的行 / 2..11
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

create function test_ret_set_scalar(int,int) returns setof int as '
DECLARE
	i int;
BEGIN
	FOR i IN $1 .. $2 LOOP
		RETURN NEXT i + 1;
	END LOOP;
	RETURN;
END;' language plpgsql;

select * from test_ret_set_scalar(1,10);

-- @case plpgsql.exception_reraise
-- @pg plpgsql.sql:2111-2131
-- 缺失: Lealone 无 PL/pgSQL 块语言(BEGIN..END/IF/LOOP/EXCEPTION)
-- PG 期望: 内层 EXCEPTION 捕获到异常并重新抛出, 外层不再捕获(只打印 NOTICE)
CREATE FUNCTION reraise_test() RETURNS void AS $$
BEGIN
   BEGIN
       RAISE syntax_error;
   EXCEPTION
       WHEN syntax_error THEN
           BEGIN
               raise notice 'exception % thrown in inner block, reraising', sqlerrm;
               RAISE;
           EXCEPTION
               WHEN OTHERS THEN
                   raise notice 'RIGHT - exception % caught in inner block', sqlerrm;
           END;
   END;
EXCEPTION
   WHEN OTHERS THEN
       raise notice 'WRONG - exception % caught in outer block', sqlerrm;
END;
$$ LANGUAGE plpgsql;

SELECT reraise_test();

-- @case plpgsql.out_params_exception
-- @pg plpgsql.sql:3609-3638
-- 缺失: Lealone 无 PL/pgSQL 块语言(BEGIN..END/IF/LOOP/EXCEPTION)
-- PG 期望: leaker_1(false)=1, leaker_1(true)=0; leaker_2 带 OUT 参数返回 RECORD
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

DROP FUNCTION leaker_1(bool);
DROP FUNCTION leaker_2(bool);

