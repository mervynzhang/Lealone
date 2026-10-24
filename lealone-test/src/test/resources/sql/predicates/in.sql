-- IN谓词: IN列表、IN子查询、空IN、NOT IN遇到NULL
-- 期望输出的语法说明见 com.lealone.test.script.SqlScriptTest

create table t(id int, name varchar(10));
> update count: 0

insert into t values(1, 'a'), (2, 'b'), (3, null);
> update count: 3

-- IN列表
select id from t where id in (1, 3) order by id;
> ID
> --
> 1
> 3
> rows (ordered): 2

select id from t where id in (1) order by id;
> ID
> --
> 1
> rows (ordered): 1

-- 空IN列表等价于FALSE
select id from t where id in () order by id;
> ID
> --
> rows (ordered): 0

select count(*) c from t where id in ();
> C
> -
> 0
> rows: 1

-- IN子查询
select id from t where id in (select id from t where id > 2) order by id;
> ID
> --
> 3
> rows (ordered): 1

select id from t where id in (select id from t where name is null) order by id;
> ID
> --
> 3
> rows (ordered): 1

select id from t where id in (select 1 from t where 1 = 0) order by id;
> ID
> --
> rows (ordered): 0

-- NOT IN列表
select id from t where id not in (1, 3) order by id;
> ID
> --
> 2
> rows (ordered): 1

-- NOT IN遇到NULL时结果是NULL，一行也不返回
select id from t where id not in (1, null) order by id;
> ID
> --
> rows (ordered): 0

-- IN列表里有NULL不影响其他值的匹配
select id from t where id in (1, null) order by id;
> ID
> --
> 1
> rows (ordered): 1

select id from t where name in ('a', 'z') order by id;
> ID
> --
> 1
> rows (ordered): 1

-- NULL和'x'比较是NULL，所以id=3的行被过滤掉
select id from t where name not in ('a') order by id;
> ID
> --
> 2
> rows (ordered): 1

-- 标量上下文中的IN/NOT IN，NULL参与时结果是NULL
select 1 in (1, 2) c1, 2 in (1, 3) c2, null in (1, 2) c3;
> C1   C2    C3
> ---- ----- ----
> TRUE FALSE null
> rows: 1

select 1 not in (2, null) c1, 1 not in (2, 3) c2;
> C1   C2
> ---- ----
> null TRUE
> rows: 1

-- 只有一行一列时可以直接比较值
select 1 in (1, 2) c;
>> TRUE

select id, name in ('a') c from t order by id;
> ID C
> -- -----
> 1  TRUE
> 2  FALSE
> 3  null
> rows (ordered): 3

-- 行值IN
select id from t where (id, name) in ((1, 'a'), (2, 'x')) order by id;
> ID
> --
> 1
> rows (ordered): 1

select id from t where id in (1, 3) and name is not null order by id;
> ID
> --
> 1
> rows (ordered): 1

drop table t;
> update count: 0

