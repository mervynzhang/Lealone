-- PostgreSQL 回归测试 rangefuncs.sql 的 Lealone 转换版。
--
-- rangefuncs.sql(827 行)测的是 set-returning 函数在 FROM 子句里的各种用法。
-- Lealone 现在支持 RETURNS SETOF / RETURNS TABLE 和表函数调用(含列别名、VIEW、JOIN),
-- 所以这个文件第一次可以转一部分过来; 依赖 unnest()、generate_series()、
-- SETOF <表行类型>、INSERT ... RETURNING 的用例仍然 skip, 原因写在 skip-list.txt。
--
-- 转换时的固定改法:
--   1. PG 的无名参数/`$1` 改成命名参数;
--   2. 依赖 unnest()/generate_series() 的 `WITH ORDINALITY` 语句整体 skip;
--   3. 被 skip 的用例保持 PG 原样语句, 期望值以 "-- PG 期望:" 注释给出。

-- ---------------------------------------------------------------------------
-- SETOF <表行类型>(整块 skip)
-- ---------------------------------------------------------------------------

-- @case rangefuncs.setof_composite
-- @pg rangefuncs.sql:1-8
-- 缺失: Lealone 无 SETOF <复合类型/表行类型>, 也不支持 $1 位置参数;
--       这样的函数能建, 但调用时报 21002
create table rngfunc2(rngfuncid int, f2 int);
create function rngfunct(int) returns setof rngfunc2 as 'SELECT * FROM rngfunc2 WHERE rngfuncid = $1 ORDER BY f2;' language sql;
select * from rngfunct(1);
-- PG 期望: rngfunc2 里 rngfuncid=1 的行

-- @case rangefuncs.with_ordinality
-- @pg rangefuncs.sql:9-12
-- 适配: PG 的 rngfunct(int) 返回 SETOF rngfunc2(表行类型)且用 $1, 这里改成 RETURNS TABLE + 命名参数
create table ord_rngfunc2(rngfuncid int, f2 int);
> update count: 0

insert into ord_rngfunc2 values(1, 11), (2, 22), (1, 111);
> update count: 3

create function ord_rngfunct(p int) returns table(rngfuncid int, f2 int) as
    'select rngfuncid, f2 from ord_rngfunc2 where rngfuncid = p order by f2' language sql;
> update count: 0

select * from ord_rngfunct(1) with ordinality as z(a,b,ord);
> A B   ORD
> - --- ---
> 1 11  1
> 1 111 2
> rows: 2

select * from ord_rngfunct(1) with ordinality as z(a,b,ord) where b > 100;
> A B   ORD
> - --- ---
> 1 111 2
> rows: 1

select a,b,ord from ord_rngfunct(1) with ordinality as z(a,b,ord);
> A B   ORD
> - --- ---
> 1 11  1
> 1 111 2
> rows: 2

-- @case rangefuncs.with_ordinality_unnest
-- @pg rangefuncs.sql:13-17
-- 缺失: 没有 unnest()/generate_series()(WITH ORDINALITY 本身已支持, 见上一个用例)
select a,ord from unnest(array['a','b']) with ordinality as z(a,ord);
select * from unnest(array['a','b']) with ordinality as z(a,ord);
select a,ord from unnest(array[1.0::float8]) with ordinality as z(a,ord);
select * from unnest(array[1.0::float8]) with ordinality as z(a,ord);
select row_to_json(s.*) from generate_series(11,14) with ordinality s;
-- PG 期望: 逐行返回 (值, 行号)

-- ---------------------------------------------------------------------------
-- 标量/多行函数出现在 FROM 里(这次新解锁的部分)
-- ---------------------------------------------------------------------------

-- @case rangefuncs.setof_from
-- @pg rangefuncs.sql:85-89
-- 适配: PG 的 getrngfunc2(int) 用 $1, 这里改成命名参数 p
create table rngfunc (rngfuncid int, rngfuncsubid int, rngfuncname text, primary key(rngfuncid,rngfuncsubid));
> update count: 0

insert into rngfunc values(1,1,'Joe');
> update count: 1

insert into rngfunc values(1,2,'Ed');
> update count: 1

insert into rngfunc values(2,1,'Mary');
> update count: 1

-- @case rangefuncs.setof_from_call
-- @pg rangefuncs.sql:101-103
create function getrngfunc2(p int) returns setof int as
    'SELECT rngfuncid FROM rngfunc WHERE rngfuncid = p' language sql;
> update count: 0

-- setof 函数当表用: 列名就是函数名(PG 也是这个名字)
select * from getrngfunc2(1) as t1;
> GETRNGFUNC2
> -----------
> 1
> 1
> rows: 2

-- 没有匹配行时是空集, 不是一行 NULL
select count(*) c from getrngfunc2(9);
> C
> -
> 0
> rows: 1

-- @case rangefuncs.setof_text_view
-- @pg rangefuncs.sql:113-118
create function getrngfunc3(p int) returns setof text as
    'SELECT rngfuncname FROM rngfunc WHERE rngfuncid = p' language sql;
> update count: 0

select * from getrngfunc3(1) as t1;
> GETRNGFUNC3
> -----------
> Joe
> Ed
> rows: 2

-- setof 函数可以出现在视图定义里
create view vw_getrngfunc as select * from getrngfunc3(1);
> update count: 0

select * from vw_getrngfunc;
> GETRNGFUNC3
> -----------
> Joe
> Ed
> rows: 2

drop view vw_getrngfunc;
> update count: 0

-- @case rangefuncs.table_function
-- @pg rangefuncs.sql:435-438
-- 适配: PG 的 RETURNS TABLE(a int) 函数体是 generate_series(1,5), Lealone 没有 generate_series,
--       这里换成等价的 SYSTEM_RANGE; 列名/行数语义不变
create function rngfunc5() returns table(a int) as
    $$ SELECT x FROM SYSTEM_RANGE(1,5) x $$ language sql;
> update count: 0

select * from rngfunc5();
> A
> -
> 1
> 2
> 3
> 4
> 5
> rows: 5

drop function rngfunc5();
> update count: 0

-- @case rangefuncs.table_function_typmod
-- @pg rangefuncs.sql:449-454
-- 适配: GENERATE_SERIES 换成单行 VALUES; 验证 RETURNS TABLE 的列类型(varchar(5))生效
create or replace function rngfunc() returns table(a varchar(5)) as
    $$ SELECT 'hello'::varchar(5) $$ language sql stable;
> update count: 0

select * from rngfunc() group by 1;
> A
> -----
> hello
> rows: 1

drop function rngfunc();
> update count: 0

-- @case rangefuncs.table_function_join
-- @pg rangefuncs.sql:66-80
-- 适配: PG 里是 rngfunct(rngfunc2.rngfuncid) 这种隐式 LATERAL; Lealone 的文档写明 LATERAL 不支持,
--       这里改用常量参数, 只验证表函数能参与 JOIN/别名/列别名列表
create function rngtbl2(p int) returns table(id int, nm text) as
    'SELECT rngfuncid, rngfuncname FROM rngfunc WHERE rngfuncid = p' language sql;
> update count: 0

select * from rngtbl2(1) as t(x, y);
> X Y
> - ---
> 1 Joe
> 1 Ed
> rows: 2

-- rngfunc 里 id=1 有两行, rngtbl2(1) 也返回两行, 所以 join 是 4 行
select count(*) c from rngfunc r join rngtbl2(1) t on r.rngfuncid = t.id;
> C
> -
> 4
> rows: 1

-- ---------------------------------------------------------------------------
-- 函数体里带 DML (整块 skip)
-- ---------------------------------------------------------------------------

-- @case rangefuncs.insert_returning
-- @pg rangefuncs.sql:456-476
-- 适配: Lealone 函数参数要有名字(用 x 代替 $1); temp表的serial sequence在函数体的
--       嵌套session里找不到, 所以用普通表; Lealone 的SQL函数体已支持带RETURNING的DML
create table tt(f1 serial, data text);
> update count: 0

create function insert_tt(x text) returns int as $$ insert into tt(data) values(x) returning f1 $$ language sql;
> update count: 0

select insert_tt('foo');
> INSERT_TT('foo')
> ----------------
> 1
> rows: 1

select * from tt;
> F1 DATA
> -- ----
> 1  foo
> rows: 1
