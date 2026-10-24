-- 基本查询: WHERE/ORDER BY/DISTINCT/LIMIT/OFFSET/BETWEEN/IS NULL/EXISTS/UNION/JOIN/GROUP BY
-- 期望输出的语法说明见 com.lealone.test.script.SqlScriptTest

create table t(id int, name varchar(10), v int);
> update count: 0

insert into t values(1, 'a', 10), (2, 'b', 20), (3, 'c', 20), (4, null, 40);
> update count: 4

insert into t(id, name) values(5, 'e');
> update count: 1

-- ORDER BY，NULL排在有值之后(降序)或之前(升序)
select id, v from t order by v desc, id;
> ID V
> -- ----
> 4  40
> 2  20
> 3  20
> 1  10
> 5  null
> rows (ordered): 5

select distinct v from t order by v;
> V
> ----
> null
> 10
> 20
> 40
> rows (ordered): 4

select id from t where v is null order by id;
> ID
> --
> 5
> rows (ordered): 1

select id from t where v is not null order by id;
> ID
> --
> 1
> 2
> 3
> 4
> rows (ordered): 4

-- BETWEEN
select id from t where v between 10 and 20 order by id;
> ID
> --
> 1
> 2
> 3
> rows (ordered): 3

select id from t where v not between 10 and 20 order by id;
> ID
> --
> 4
> rows (ordered): 1

select id from t where name between 'a' and 'b' order by id;
> ID
> --
> 1
> 2
> rows (ordered): 2

-- LIMIT/OFFSET
select id from t order by id limit 2;
> ID
> --
> 1
> 2
> rows (ordered): 2

select id from t order by id limit 1 offset 2;
> ID
> --
> 3
> rows (ordered): 1

-- EXISTS
select id from t where exists (select 1 from t t2 where t2.id = t.id + 1) order by id;
> ID
> --
> 1
> 2
> 3
> 4
> rows (ordered): 4

-- GROUP BY/HAVING
select v, count(*) c, sum(id) s from t group by v having count(*) > 1 order by v;
> V  C S
> -- - -
> 20 2 5
> rows (ordered): 1

-- UNION/UNION ALL
select id from t where v = 20 union all select id from t where id = 1 order by id;
> ID
> --
> 1
> 2
> 3
> rows (ordered): 3

select id from t where v = 20 union select id from t where id = 1 order by id;
> ID
> --
> 1
> 2
> 3
> rows (ordered): 3

-- UPDATE对NULL值的处理
update t set v = v + 1 where v is null;
> update count: 1

select id, v from t order by id;
> ID V
> -- ----
> 1  10
> 2  20
> 3  20
> 4  40
> 5  null
> rows (ordered): 5

delete from t where id > 3;
> update count: 2

select count(*) c from t;
> C
> -
> 3
> rows: 1

-- JOIN
create table s(id int, label varchar(10));
> update count: 0

insert into s values(1, 'one'), (2, 'two');
> update count: 2

select t.id, s.label from t join s on t.id = s.id order by t.id;
> ID LABEL
> -- -----
> 1  one
> 2  two
> rows (ordered): 2

select t.id, s.label from t left join s on t.id = s.id order by t.id;
> ID LABEL
> -- -----
> 1  one
> 2  two
> 3  null
> rows (ordered): 3

select id from t where id in (select id from s) order by id;
> ID
> --
> 1
> 2
> rows (ordered): 2

-- COALESCE/NULLIF
select coalesce(null, null, 'x') c1, nullif(1, 1) c2, nullif(1, 2) c3;
> C1 C2   C3
> -- ---- --
> x  null 1
> rows: 1

drop table s;
> update count: 0

drop table t;
> update count: 0

