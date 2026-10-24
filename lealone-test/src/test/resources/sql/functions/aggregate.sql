-- 聚合函数: COUNT/SUM/MIN/MAX/AVG，NULL的处理，GROUP BY
-- 期望输出的语法说明见 com.lealone.test.script.SqlScriptTest

create table t(id int, v int, d double);
> update count: 0

insert into t values(1, 10, 1.5), (2, 20, 2.5), (3, null, null);
> update count: 3

-- COUNT(*)统计所有行，COUNT(v)忽略NULL
select count(*) c1, count(v) c2, sum(v) c3, min(v) c4, max(v) c5 from t;
> C1 C2 C3 C4 C5
> -- -- -- -- --
> 3  2  30 10 20
> rows: 1

select count(distinct v) c from t;
> C
> -
> 2
> rows: 1

select min(id) c1, max(id) c2 from t;
> C1 C2
> -- --
> 1  3
> rows: 1

-- LEALONE: 与H2不同，avg(int)返回整数并截断小数部分，这里得到15，H2得到15.0
select avg(v) c from t;
> C
> --
> 15
> rows: 1

create table u(v int);
> update count: 0

insert into u values(1), (2);
> update count: 2

-- LEALONE: 与H2不同，avg(int)对(1,2)得到1(截断)，H2得到1.5
select avg(v) c from u;
> C
> -
> 1
> rows: 1

insert into u values(4);
> update count: 1

-- LEALONE: 与H2不同，avg(int)对(1,2,4)得到2(截断)，H2得到2.3333333333333335
select avg(v) c from u;
> C
> -
> 2
> rows: 1

select avg(distinct v) c from u;
> C
> -
> 2
> rows: 1

select sum(v) c1, count(*) c2 from u;
> C1 C2
> -- --
> 7  3
> rows: 1

-- 空结果集: COUNT返回0，SUM/MIN/MAX/AVG返回NULL
select count(*) c from t where id > 100;
> C
> -
> 0
> rows: 1

select sum(v) c from t where id > 100;
> C
> ----
> null
> rows: 1

select min(v) c1, max(v) c2, avg(v) c3 from t where id > 100;
> C1   C2   C3
> ---- ---- ----
> null null null
> rows: 1

create table e(v int);
> update count: 0

select count(*) c1, sum(v) c2, min(v) c3, max(v) c4, avg(v) c5 from e;
> C1 C2   C3   C4   C5
> -- ---- ---- ---- ----
> 0  null null null null
> rows: 1

-- GROUP BY
select v, count(*) c from u group by v order by v;
> V C
> - -
> 1 1
> 2 1
> 4 1
> rows (ordered): 3

select v, count(*) c, sum(v) s from u group by v order by v;
> V C S
> - - -
> 1 1 1
> 2 1 2
> 4 1 4
> rows (ordered): 3

select count(*) c from t group by v order by count(*);
> C
> -
> 1
> 1
> 1
> rows (ordered): 3

drop table e;
> update count: 0

drop table u;
> update count: 0

drop table t;
> update count: 0

