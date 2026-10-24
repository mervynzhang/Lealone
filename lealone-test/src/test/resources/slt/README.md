# sqllogictest regression gate for Lealone

An optional, curated run of the [sqllogictest](https://www.sqlite.org/sqllogictest/) suite
(checkout: [gregrahn/sqllogictest](https://github.com/gregrahn/sqllogictest), 622 `.test` files)
against the embedded Lealone engine.

Measured on HEAD `30c02350b` (Java 21, single thread, one fresh embedded database per file):

| run | files | queries | pass | failures | of which allowlisted | wall time |
|---|---|---|---|---|---|---|
| curated gate (`files.txt`) | 39 | 316,388 | 304,640 (96.29 %) | 11,748 | 11,746 | ~11 s |
| full set (`select1-5`, `evidence/*`, `random/expr/*`) | 137 | 1,207,979 | 1,129,620 (93.51 %) | 78,359 | 78,263 | ~80 s |

98 % of the raw failures are one dialect gap (the `ALL` quantifier, see the allowlist); the gate
is built so that this noise never hides a result that changes.

## Why this suite

* It is a large, machine-generated corpus of *pure SQL semantics* with hashed results produced by
  SQLite: expression evaluation, aggregate semantics, three-valued logic, `NULL` handling,
  sorting, index scans, multi-table joins - exactly the parts of the engine that unit tests cover
  thinly.
* It is **statement level and self-contained**: every file creates its own tables, there are no
  fixtures and no servers, so a fresh embedded database per file is enough.
* It is externally maintained, so it keeps finding things nobody thought of. The first evaluation
  of one slice found 6 real defects (eager `CASE`/`COALESCE` evaluation, `SUM(NULL)`,
  `DISTINCT`-aggregate double counting, duplicate rows from `IN` on an index, an NPE on
  `SUM(DISTINCT) NOT BETWEEN`, and a 24-table join that never returned).
* While this gate was being wired up it immediately caught a *new* engine regression: commit
  `68e7a8744` ("IN list scanned once per value") made the index cursor un-re-scannable, so 1,683
  of 2,832 `select4.test` queries returned too few rows for `UNION`/join plans (1 failure before
  the commit). It was reverted and replaced by a row-key dedupe in `IndexCursor.next()`
  (`30c02350b`), after which `select4` passes 2,832/2,832. That is what this gate is for.

The suite is **not committed** here: it is 1.1 GB and lives in its own repository. The test skips
(never fails) when the checkout is missing.

## Running it

Prerequisites: Java 21 and the engine installed in the local Maven repository **from the same
commit you want to test** (a stale `~/.m2` silently tests old code, and `mvn install` skips
modules it thinks are up to date):

```bash
export JAVA_HOME=/usr/lib/jvm/sapmachine-21
git clone --depth 1 https://github.com/gregrahn/sqllogictest /tmp/sqllogictest   # once
mvn -o install -Dmaven.test.skip=true -pl lealone-main -am                       # engine at HEAD
```

Curated subset - the gate (39 files, ~11 s, only `SltTest` runs):

```bash
mvn -o test -pl lealone-test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test
```

The `slt` profile is the only thing that enables the test (`slt.run=true`) and limits surefire to
`SltTest`; the default `mvn test` is unaffected. `-Dtest=SltTest` is redundant but harmless.

Full 137-file set (~80 s). Use the runner's `main` directly, because the curated `files.txt` is
the gate input:

```bash
export JAVA_HOME=/usr/lib/jvm/sapmachine-21   # or use the JDK 21 path explicitly
CP=lealone-test/target/test-classes:$(mvn -o -q dependency:build-classpath -pl lealone-test \
     -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=test)
(cd /tmp/sqllogictest/test && { ls select{1..5}.test; ls evidence/*.test; ls random/expr/*.test; }) \
     > /tmp/slt-full-files.txt
java -Xmx3g -Dlealone.base.dir=target/slt-db-full -cp "$CP" \
     com.lealone.test.slt.SltRunner --dir=/tmp/sqllogictest/test \
     --files=/tmp/slt-full-files.txt --out=target/slt-report-full \
     --allowlist=lealone-test/src/test/resources/slt/allowlist.txt --timeout=60
```

Per-file JSON plus `summary.tsv` / `failures.tsv` / `delta.txt` are written to `--out`
(`target/slt-report` for the Maven profile). The runner exits non-zero when a file produces more
non-allowlisted query failures than `baseline.txt` allows, or when a file times out, is missing or
cannot be set up.

Useful system properties (the profile sets the first four):

| property | default | meaning |
|---|---|---|
| `slt.run` | `false` | gate; without it the test is skipped |
| `slt.dir` | `/tmp/sqllogictest/test` | the `test/` directory of the checkout |
| `slt.timeout` | `120` | per-file timeout in seconds |
| `slt.out` | `target/slt-report` | report directory |
| `slt.files`, `slt.baseline`, `slt.allowlist` | committed resources | override for experiments |
| `slt.updateBaseline` | `false` | rewrite `baseline.txt` from the current run |
| `slt.baseline.out` | `src/test/resources/slt/baseline.txt` | where `slt.updateBaseline` writes |

## What the gate compares

Two committed resources drive the decision:

* **`allowlist.txt`** - failure signatures that are known dialect noise and never gate. Format
  `id <TAB> java-regex <TAB> reason`, matched against `subtype<TAB>sql<TAB>detail` (plus a trailing
  `ALL` marker when the unclipped SQL contains the token, because the reported SQL is clipped).
  The active rules are the three shapes of the `ALL` quantifier (`SELECT ALL`, `SUM(ALL x)`,
  `MAX(ALL 99)`): 78,263 of the 78,359 failures in the full set. The runner prints per-rule hit
  counts on every run, so the silenced volume can never grow unnoticed. Rules for SQLite/MySQL
  only DDL are listed for documentation; `statement` records are executed but never gated.
* **`baseline.txt`** - per file, the number of *non-allowlisted query failures* the current build
  still produces (`file <TAB> expected <TAB> observed-total-for-context`). It is `0` for 25 of the
  27 curated files and `1` for two of them (see "Known deviations").

The comparison is asymmetric on purpose: **more** failures than the baseline fails CI with the
offending SQL, expected and actual values; **fewer** is reported as `STALE(...)` with a note to
lower the number, but does not fail. A fix is never punished, a regression is always loud.
Timeouts, missing files and setup errors always fail.

### Updating the baseline

```bash
mvn -o test -pl lealone-test -Pslt -Dtest=SltTest -Dslt.dir=/tmp/sqllogictest/test \
    -Dslt.updateBaseline=true
```

This rewrites `src/test/resources/slt/baseline.txt` and does not assert. Review the diff: a number
that grew means a new failure was accepted and needs a reason (new known bug or a new allowlist
entry) before it is committed. Never make a red gate green by regenerating the baseline blindly -
`target/slt-report/failures.tsv` has the SQL, the expected and the actual values.

## Contents of the curated list

* `select1..4` - pre-generated queries (1,000 / 1,000 / 3,320 / 2,832) against 5-column, 1,000-row
  tables with five indexes each, executed both before and after the file's own `CREATE INDEX`
  statements. They cover index scans, ranges, single- and multi-table `IN` lists, `ORDER BY`,
  `DISTINCT`, `UNION`/`EXCEPT`/`INTERSECT` and up to 9-way joins - the richest plan coverage in
  the suite, and the files that caught the `IndexCursor` regression.
* `evidence/in2`, `evidence/slt_lang_createview`, `evidence/slt_lang_dropview` - the evidence
  files that use portable DDL; they cover `IN` against subqueries and view create/drop.
* `random/expr/slt_good_*` - 20 of the 120 expression files (10,000 constant-folding / aggregate
  expressions each, hashed results), spread over the whole range. This is the part that catches
  evaluator regressions.

Deliberately not in the curated list (documented so nobody re-adds them blindly):

* **`select5.test`** - 732 queries over 24-table joins; it takes tens of seconds and used to hang
  (it is in the full-set run, where a hang shows up as `TIMEOUT`). It passes on `30c02350b` but is
  too slow for a per-change gate.
* **`evidence/in1`, `slt_lang_createtrigger`, `slt_lang_reindex`, `slt_lang_replace`,
  `slt_lang_update`, `slt_lang_droptrigger`** - SQLite/MySQL only DDL (`CREATE TRIGGER x UPDATE
  ON t ...`, `REINDEX`, `REPLACE INTO`) or `TEXT` used as an indexed column (Lealone maps `TEXT`
  to `CLOB`, and indexes on CLOB are unsupported). 36 query failures in the full set, all noise.
* The 100 remaining `random/expr` files: they add no coverage class, only time.

## Known deviations still reported (all open, all understood)

Curated baseline: `random/expr/slt_good_91` = 1 (32-bit `INTEGER` overflow,
`Numeric value out of range: "-28805204448"`, where SQLite uses 64-bit integers) and
`random/expr/slt_good_96` = 1 (`89 / -0` raises `Division by zero` where SQLite yields `NULL`).
Both are single, deterministic queries; they are tracked in the baseline instead of the allowlist
on purpose, so that a *new* failure of the same class (e.g. a regression of the eager `CASE`
evaluation) still fails the gate.

Full-set residuals (96 non-allowlisted query failures):

| count | class |
|---|---|
| 36 | `evidence/*` SQLite-only DDL / `TEXT`-as-`CLOB` index cascades |
| 32 | `CASE`/`COALESCE` branch evaluated eagerly -> `Division by zero` instead of `NULL` |
| 12 | 32-bit `INTEGER` arithmetic overflow |
| 9 | `REAL`/`CAST`/`NULLIF` result promotion differences (float rounding) |
| 6 | `SUM(DISTINCT)` / `AVG(DISTINCT)` inside `CASE` counted twice |
| 1 | NPE in `Expression.getCost()` on `SUM(DISTINCT) ... NOT BETWEEN` |

## Notes for whoever extends this

* The runner is embedded only: one fresh database per file, no TCP server and no ports. The
  profile sets `lealone.base.dir=target/slt-db`, which is wiped at the start of a run.
* Each file runs in a daemon thread with a per-file timeout, so a hanging query costs one timeout
  instead of the whole build. The query itself cannot be cancelled - the worker thread and its
  database leak until the JVM exits, which is acceptable because a timeout fails the run.
* Results are compared the way the suite stores them: literal values and
  `N values hashing to md5`, with the documented rendering (`NULL`, `(empty)`, control characters
  as `@`, `%.3f` for `REAL`, 64-bit `I`). Hash mode is validated (0 hash mismatches on 6,721
  hashed expectations in the full set).
* The runner identifies itself as `postgresql`/`lealone` for `skipif`/`onlyif`; records for other
  engines are skipped (683,175 of them in the full set), `halt` is honoured, labels are
  cross-checked.
* The engine must be installed from the commit under test; see the prerequisites above. CI should
  therefore build the engine first (`mvn -o install -Dmaven.test.skip=true -pl lealone-main -am`)
  or use `-am` on the test invocation.
