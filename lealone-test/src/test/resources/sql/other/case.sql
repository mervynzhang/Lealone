-- CASE表达式: 简单CASE、搜索CASE、带ELSE/不带ELSE、CASE用于聚合
-- 期望输出的语法说明见 com.lealone.test.script.SqlScriptTest

-- 搜索CASE
select case when 1 = 1 then 'y' else 'n' end c;
> C
> -
> y
> rows: 1

select case when 1 = 2 then 'y' else 'n' end c;
> C
> -
> n
> rows: 1

-- 没有ELSE且没有分支命中时返回NULL
select case when 1 = 2 then 'y' end c;
> C
> ----
> null
> rows: 1

select case when 1 = 2 then 1 when 2 = 2 then 2 else 3 end c;
> C
> -
> 2
> rows: 1

select case when true then 1 end c;
>> 1

select case when false then 1 end c;
> C
> ----
> null
> rows: 1

-- 简单CASE
select case 2 when 1 then 'a' when 2 then 'b' else 'c' end c;
> C
> -
> b
> rows: 1

select case 3 when 1 then 'a' when 2 then 'b' else 'c' end c;
> C
> -
> c
> rows: 1

select case 4 when 1 then 'a' when 2 then 'b' end c;
> C
> ----
> null
> rows: 1

-- LEALONE: 与H2不同，Lealone允许没有WHEN分支的空CASE(相当于NULL)，H2报语法错误42001
select case end c;
> C
> ----
> null
> rows: 1

-- CASE和表数据一起使用
create table t(id int, v int);
> update count: 0

insert into t values(1, 10), (2, 20), (3, null);
> update count: 3

select id, case when v is null then 'null' when v > 15 then 'big' else 'small' end c from t order by id;
> ID C
> -- -----
> 1  small
> 2  big
> 3  null
> rows (ordered): 3

select id, case id when 1 then 'one' else 'other' end c from t order by id;
> ID C
> -- -----
> 1  one
> 2  other
> 3  other
> rows (ordered): 3

select case when id = 3 then null else 'x' end c from t order by id;
> C
> ----
> x
> x
> null
> rows (ordered): 3

-- CASE里所有分支都不命中时返回NULL
select case when v > 100 then 'a' when v > 100 then 'b' end c from t order by id;
> C
> ----
> null
> null
> null
> rows (ordered): 3

-- CASE用作聚合函数的参数
select sum(case when v > 15 then 1 else 0 end) c from t;
> C
> -
> 1
> rows: 1

select count(case when v is not null then 1 end) c from t;
> C
> -
> 2
> rows: 1

drop table t;
> update count: 0

