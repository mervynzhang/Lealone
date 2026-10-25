/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.test.slt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * baseline 必须按"失败签名集合"比较，而不是"每文件失败个数"。
 * <p>
 * 只比个数时，同一个文件里"修好一条、又坏掉另一条"会因为个数相抵被判成 OK —— 门禁假绿。
 * 这里直接用构造出来的 FileResult 验证：计数相同但签名不同时必须报 NEW，签名也相同才是 OK。
 */
public class SltBaselineSignatureTest extends TestBase {

    private static SltRunner.Failure failure(String subtype, String sql, String detail) {
        SltRunner.Failure f = new SltRunner.Failure();
        f.file = "t.test";
        f.kind = "query";
        f.subtype = subtype;
        f.line = 1;
        f.sql = sql;
        f.detail = detail;
        f.expected = "1 value";
        f.actual = "0 values";
        return f;
    }

    private static SltRunner.FileResult result(SltRunner.Failure... failures) {
        SltRunner.FileResult r = new SltRunner.FileResult();
        r.file = "t.test";
        List<SltRunner.Failure> list = new ArrayList<>();
        for (SltRunner.Failure f : failures)
            list.add(f);
        r.failures.addAll(list);
        r.qTotal = failures.length;
        return r;
    }

    private static SltBaseline.Baseline baselineOf(SltRunner.FileResult... results) {
        String text = SltBaseline.renderBaseline(java.util.Arrays.asList(results));
        try {
            return SltBaseline.loadBaseline(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // 把一次运行的结果分类，返回那个 FileResult（便于断言 delta/newKeys/staleKeys）
    private static SltRunner.FileResult classify(SltRunner.FileResult current,
            SltBaseline.Baseline baseline) {
        List<SltRunner.FileResult> results = Collections.singletonList(current);
        SltBaseline.classify(results, Collections.emptyList(), baseline);
        return current;
    }

    @Test
    public void testSameSignatureIsOk() {
        SltRunner.Failure a = failure("hash_mismatch", "select a from t", "19 values");
        SltBaseline.Baseline baseline = baselineOf(result(a));
        SltRunner.FileResult current = classify(result(failure("hash_mismatch", "select a from t",
                "detail 变了但签名没变")), baseline);
        assertEquals(0, current.delta);
        assertEquals("OK", current.status);
    }

    // 核心场景: 修好 A、坏掉 B, 失败个数不变 —— 只比计数会假绿, 比签名必须报 NEW
    @Test
    public void testReplaceOneFailureWithAnotherIsDetected() {
        SltRunner.Failure a = failure("hash_mismatch", "select a from t", "");
        SltRunner.Failure b = failure("hash_mismatch", "select b from t", "");
        SltBaseline.Baseline baseline = baselineOf(result(a));

        SltRunner.FileResult current = classify(result(b), baseline);
        assertEquals("失败个数和 baseline 相同(都是1)", 1, current.unexpected);
        assertEquals(1, current.delta);
        assertEquals("NEW(+1)", current.status);
        assertEquals(1, current.newKeys.size());
        assertTrue("新增的签名必须被记下来", current.newKeys.contains(b.key()));
        assertTrue("消失的签名也要记下来(便于清理baseline)", current.staleKeys.contains(a.key()));
        assertTrue("门禁必须报出这个文件", SltBaseline.regressions(
                Collections.singletonList(current), false).size() == 1);
    }

    @Test
    public void testFixedFailureIsReportedAsStale() {
        SltRunner.Failure a = failure("row_count", "select a from t", "");
        SltBaseline.Baseline baseline = baselineOf(result(a));
        SltRunner.FileResult current = classify(result(), baseline);
        assertEquals(0, current.unexpected);
        assertTrue(current.delta < 0);
        assertEquals(1, current.staleKeys.size());
        assertTrue(current.status.startsWith("STALE"));
    }

    // 旧格式(只有个数)仍然兼容: 按个数比较
    @Test
    public void testLegacyCountFormatStillWorks() throws Exception {
        SltBaseline.Baseline legacy = SltBaseline.loadBaseline("t.test\t2\t3\n");
        assertEquals(2, legacy.countOf("t.test"));
        assertTrue(legacy.keysOf("t.test").isEmpty());
        SltRunner.FileResult current = classify(result(
                failure("value", "select 1", ""), failure("value", "select 2", "")), legacy);
        assertEquals(2, current.baseline);
        assertEquals(0, current.delta);
        assertEquals("OK", current.status);
    }

    // render -> load 往返: 个数与签名都要保住
    @Test
    public void testRoundTrip() {
        SltRunner.Failure a = failure("value", "select 1", "");
        SltBaseline.Baseline baseline = baselineOf(result(a, failure("value", "select 2", "")));
        assertEquals(2, baseline.countOf("t.test"));
        assertEquals(2, baseline.keysOf("t.test").size());
        assertTrue(baseline.keysOf("t.test").contains(a.key()));
    }
}
