/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.elasticsearch.Build;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.compute.lucene.TimeSeriesSourceOperator;
import org.elasticsearch.compute.operator.DriverProfile;
import org.elasticsearch.compute.operator.TimeSeriesAggregationOperator;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.index.mapper.DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class TimeSeriesIT extends AbstractEsqlIntegTestCase {

    @Override
    public EsqlQueryResponse run(EsqlQueryRequest request) {
        assumeTrue("time series available in snapshot builds only", Build.current().isSnapshot());
        return super.run(request);
    }

    public void testEmpty() {
        Settings settings = Settings.builder().put("mode", "time_series").putList("routing_path", List.of("host")).build();
        client().admin()
            .indices()
            .prepareCreate("empty_index")
            .setSettings(settings)
            .setMapping(
                "@timestamp",
                "type=date",
                "host",
                "type=keyword,time_series_dimension=true",
                "cpu",
                "type=long,time_series_metric=gauge"
            )
            .get();
        run("TS empty_index | LIMIT 1").close();
    }

    record Doc(String host, String cluster, long timestamp, int requestCount, double cpu, ByteSizeValue memory) {}

    final List<Doc> docs = new ArrayList<>();

    record RequestCounter(long timestamp, long count) {

    }

    static Double computeRate(List<RequestCounter> values) {
        List<RequestCounter> sorted = values.stream().sorted(Comparator.comparingLong(RequestCounter::timestamp)).toList();
        if (sorted.size() < 2) {
            return null;
        }
        long resets = 0;
        for (int i = 0; i < sorted.size() - 1; i++) {
            if (sorted.get(i).count > sorted.get(i + 1).count) {
                resets += sorted.get(i).count;
            }
        }
        RequestCounter last = sorted.get(sorted.size() - 1);
        RequestCounter first = sorted.get(0);
        double dv = resets + last.count - first.count;
        double dt = last.timestamp - first.timestamp;
        return dv * 1000 / dt;
    }

    @Before
    public void populateIndex() {
        Settings settings = Settings.builder().put("mode", "time_series").putList("routing_path", List.of("host", "cluster")).build();
        client().admin()
            .indices()
            .prepareCreate("hosts")
            .setSettings(settings)
            .setMapping(
                "@timestamp",
                "type=date",
                "host",
                "type=keyword,time_series_dimension=true",
                "cluster",
                "type=keyword,time_series_dimension=true",
                "cpu",
                "type=double,time_series_metric=gauge",
                "memory",
                "type=long,time_series_metric=gauge",
                "request_count",
                "type=integer,time_series_metric=counter"
            )
            .get();
        Map<String, String> hostToClusters = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            hostToClusters.put("p" + i, randomFrom("qa", "prod"));
        }
        long timestamp = DEFAULT_DATE_TIME_FORMATTER.parseMillis("2024-04-15T00:00:00Z");
        int numDocs = between(20, 100);
        docs.clear();
        Map<String, Integer> requestCounts = new HashMap<>();
        for (int i = 0; i < numDocs; i++) {
            List<String> hosts = randomSubsetOf(between(1, hostToClusters.size()), hostToClusters.keySet());
            timestamp += between(1, 10) * 1000L;
            for (String host : hosts) {
                var requestCount = requestCounts.compute(host, (k, curr) -> {
                    if (curr == null || randomInt(100) <= 20) {
                        return randomIntBetween(0, 10);
                    } else {
                        return curr + randomIntBetween(1, 10);
                    }
                });
                int cpu = randomIntBetween(0, 100);
                ByteSizeValue memory = ByteSizeValue.ofBytes(randomIntBetween(1024, 1024 * 1024));
                docs.add(new Doc(host, hostToClusters.get(host), timestamp, requestCount, cpu, memory));
            }
        }
        Randomness.shuffle(docs);
        for (Doc doc : docs) {
            client().prepareIndex("hosts")
                .setSource(
                    "@timestamp",
                    doc.timestamp,
                    "host",
                    doc.host,
                    "cluster",
                    doc.cluster,
                    "cpu",
                    doc.cpu,
                    "memory",
                    doc.memory.getBytes(),
                    "request_count",
                    doc.requestCount
                )
                .get();
        }
        client().admin().indices().prepareRefresh("hosts").get();
    }

    public void testSimpleMetrics() {
        List<String> sortedGroups = docs.stream().map(d -> d.host).distinct().sorted().toList();
        client().admin().indices().prepareRefresh("hosts").get();
        try (EsqlQueryResponse resp = run("TS hosts | STATS load=avg(cpu) BY host | SORT host")) {
            List<List<Object>> rows = EsqlTestUtils.getValuesList(resp);
            assertThat(rows, hasSize(sortedGroups.size()));
            for (int i = 0; i < rows.size(); i++) {
                List<Object> r = rows.get(i);
                String pod = (String) r.get(1);
                assertThat(pod, equalTo(sortedGroups.get(i)));
                List<Double> values = docs.stream().filter(d -> d.host.equals(pod)).map(d -> d.cpu).toList();
                double avg = values.stream().mapToDouble(n -> n).sum() / values.size();
                assertThat((double) r.get(0), equalTo(avg));
            }
        }
        try (EsqlQueryResponse resp = run("TS hosts | SORT @timestamp DESC, host | KEEP @timestamp, host, cpu | LIMIT 5")) {
            List<List<Object>> rows = EsqlTestUtils.getValuesList(resp);
            List<Doc> topDocs = docs.stream()
                .sorted(Comparator.comparingLong(Doc::timestamp).reversed().thenComparing(Doc::host))
                .limit(5)
                .toList();
            assertThat(rows, hasSize(topDocs.size()));
            for (int i = 0; i < rows.size(); i++) {
                List<Object> r = rows.get(i);
                long timestamp = DEFAULT_DATE_TIME_FORMATTER.parseMillis((String) r.get(0));
                String pod = (String) r.get(1);
                double cpu = (Double) r.get(2);
                assertThat(topDocs.get(i).timestamp, equalTo(timestamp));
                assertThat(topDocs.get(i).host, equalTo(pod));
                assertThat(topDocs.get(i).cpu, equalTo(cpu));
            }
        }
    }

    public void testRateWithoutGrouping() {
        record RateKey(String cluster, String host) {

        }
        Map<RateKey, List<RequestCounter>> groups = new HashMap<>();
        for (Doc doc : docs) {
            RateKey key = new RateKey(doc.cluster, doc.host);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new RequestCounter(doc.timestamp, doc.requestCount));
        }
        List<Double> rates = new ArrayList<>();
        for (List<RequestCounter> group : groups.values()) {
            Double v = computeRate(group);
            if (v != null) {
                rates.add(v);
            }
        }
        try (var resp = run("TS hosts | STATS sum(rate(request_count))")) {
            assertThat(resp.columns(), equalTo(List.of(new ColumnInfoImpl("sum(rate(request_count))", "double", null))));
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values.get(0), hasSize(1));
            assertThat((double) values.get(0).get(0), closeTo(rates.stream().mapToDouble(d -> d).sum(), 0.1));
        }
        try (var resp = run("TS hosts | STATS max(rate(request_count)), min(rate(request_count))")) {
            assertThat(
                resp.columns(),
                equalTo(
                    List.of(
                        new ColumnInfoImpl("max(rate(request_count))", "double", null),
                        new ColumnInfoImpl("min(rate(request_count))", "double", null)
                    )
                )
            );
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values.get(0), hasSize(2));
            assertThat((double) values.get(0).get(0), closeTo(rates.stream().mapToDouble(d -> d).max().orElse(0.0), 0.1));
            assertThat((double) values.get(0).get(1), closeTo(rates.stream().mapToDouble(d -> d).min().orElse(0.0), 0.1));
        }
        try (var resp = run("TS hosts | STATS max(rate(request_count)), avg(rate(request_count))")) {
            assertThat(
                resp.columns(),
                equalTo(
                    List.of(
                        new ColumnInfoImpl("max(rate(request_count))", "double", null),
                        new ColumnInfoImpl("avg(rate(request_count))", "double", null)
                    )
                )
            );
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values.get(0), hasSize(2));
            assertThat((double) values.get(0).get(0), closeTo(rates.stream().mapToDouble(d -> d).max().orElse(0.0), 0.1));
            final double avg = rates.isEmpty() ? 0.0 : rates.stream().mapToDouble(d -> d).sum() / rates.size();
            assertThat((double) values.get(0).get(1), closeTo(avg, 0.1));
        }
        try (var resp = run("TS hosts | STATS max(rate(request_count)), min(rate(request_count)), min(cpu), max(cpu)")) {
            assertThat(
                resp.columns(),
                equalTo(
                    List.of(
                        new ColumnInfoImpl("max(rate(request_count))", "double", null),
                        new ColumnInfoImpl("min(rate(request_count))", "double", null),
                        new ColumnInfoImpl("min(cpu)", "double", null),
                        new ColumnInfoImpl("max(cpu)", "double", null)
                    )
                )
            );
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values.get(0), hasSize(4));
            assertThat((double) values.get(0).get(0), closeTo(rates.stream().mapToDouble(d -> d).max().orElse(0.0), 0.1));
            assertThat((double) values.get(0).get(1), closeTo(rates.stream().mapToDouble(d -> d).min().orElse(0.0), 0.1));
            double minCpu = docs.stream().mapToDouble(d -> d.cpu).min().orElse(Long.MAX_VALUE);
            double maxCpu = docs.stream().mapToDouble(d -> d.cpu).max().orElse(Long.MIN_VALUE);
            assertThat((double) values.get(0).get(2), closeTo(minCpu, 0.1));
            assertThat((double) values.get(0).get(3), closeTo(maxCpu, 0.1));
        }
    }

    public void testRateGroupedByCluster() {
        record RateKey(String cluster, String host) {

        }
        Map<RateKey, List<RequestCounter>> groups = new HashMap<>();
        for (Doc doc : docs) {
            RateKey key = new RateKey(doc.cluster, doc.host);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new RequestCounter(doc.timestamp, doc.requestCount));
        }
        Map<String, List<Double>> bucketToRates = new HashMap<>();
        for (Map.Entry<RateKey, List<RequestCounter>> e : groups.entrySet()) {
            List<Double> values = bucketToRates.computeIfAbsent(e.getKey().cluster, k -> new ArrayList<>());
            Double rate = computeRate(e.getValue());
            values.add(Objects.requireNonNullElse(rate, 0.0));
        }
        List<String> sortedKeys = bucketToRates.keySet().stream().sorted().toList();
        try (var resp = run("TS hosts | STATS sum(rate(request_count)) BY cluster | SORT cluster")) {
            assertThat(
                resp.columns(),
                equalTo(
                    List.of(new ColumnInfoImpl("sum(rate(request_count))", "double", null), new ColumnInfoImpl("cluster", "keyword", null))
                )
            );
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(bucketToRates.size()));
            for (int i = 0; i < bucketToRates.size(); i++) {
                List<Object> row = values.get(i);
                assertThat(row, hasSize(2));
                String key = sortedKeys.get(i);
                assertThat(row.get(1), equalTo(key));
                assertThat((double) row.get(0), closeTo(bucketToRates.get(key).stream().mapToDouble(d -> d).sum(), 0.1));
            }
        }
        try (var resp = run("TS hosts | STATS avg(rate(request_count)) BY cluster | SORT cluster")) {
            assertThat(
                resp.columns(),
                equalTo(
                    List.of(new ColumnInfoImpl("avg(rate(request_count))", "double", null), new ColumnInfoImpl("cluster", "keyword", null))
                )
            );
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(bucketToRates.size()));
            for (int i = 0; i < bucketToRates.size(); i++) {
                List<Object> row = values.get(i);
                assertThat(row, hasSize(2));
                String key = sortedKeys.get(i);
                assertThat(row.get(1), equalTo(key));
                List<Double> rates = bucketToRates.get(key);
                if (rates.isEmpty()) {
                    assertThat(row.get(0), equalTo(0.0));
                } else {
                    double avg = rates.stream().mapToDouble(d -> d).sum() / rates.size();
                    assertThat((double) row.get(0), closeTo(avg, 0.1));
                }
            }
        }
    }

    public void testApplyRateBeforeFinalGrouping() {
        record RateKey(String cluster, String host) {

        }
        Map<RateKey, List<RequestCounter>> groups = new HashMap<>();
        for (Doc doc : docs) {
            RateKey key = new RateKey(doc.cluster, doc.host);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new RequestCounter(doc.timestamp, doc.requestCount));
        }
        List<Double> rates = new ArrayList<>();
        for (List<RequestCounter> group : groups.values()) {
            Double v = computeRate(group);
            if (v != null) {
                rates.add(v);
            }
        }
        try (var resp = run("TS hosts | STATS sum(abs(rate(request_count)))")) {
            assertThat(resp.columns(), equalTo(List.of(new ColumnInfoImpl("sum(abs(rate(request_count)))", "double", null))));
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values.get(0), hasSize(1));
            assertThat((double) values.get(0).get(0), closeTo(rates.stream().mapToDouble(d -> d).sum(), 0.1));
        }
        try (var resp = run("TS hosts | STATS sum(10.0 * rate(request_count))")) {
            assertThat(resp.columns(), equalTo(List.of(new ColumnInfoImpl("sum(10.0 * rate(request_count))", "double", null))));
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values.get(0), hasSize(1));
            assertThat((double) values.get(0).get(0), closeTo(rates.stream().mapToDouble(d -> d * 10.0).sum(), 0.1));
        }
        try (var resp = run("TS hosts | STATS sum(20 * rate(request_count) + 10 * floor(rate(request_count)))")) {
            assertThat(
                resp.columns(),
                equalTo(List.of(new ColumnInfoImpl("sum(20 * rate(request_count) + 10 * floor(rate(request_count)))", "double", null)))
            );
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values.get(0), hasSize(1));
            assertThat((double) values.get(0).get(0), closeTo(rates.stream().mapToDouble(d -> 20. * d + 10.0 * Math.floor(d)).sum(), 0.1));
        }
    }

    public void testIndexMode() {
        createIndex("hosts-old");
        int numDocs = between(1, 10);
        for (int i = 0; i < numDocs; i++) {
            index("hosts-old", Integer.toString(i), Map.of("v", i));
        }
        refresh("hosts-old");
        List<ColumnInfoImpl> columns = List.of(
            new ColumnInfoImpl("_index", DataType.KEYWORD, null),
            new ColumnInfoImpl("_index_mode", DataType.KEYWORD, null)
        );
        for (String q : List.of("""
            FROM hosts* METADATA _index_mode, _index
            | WHERE _index_mode == "time_series"
            | STATS BY _index, _index_mode
            """, "TS hosts* METADATA _index_mode, _index | STATS BY _index, _index_mode")) {
            try (EsqlQueryResponse resp = run(q)) {
                assertThat(resp.columns(), equalTo(columns));
                List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
                assertThat(values, hasSize(1));
                assertThat(values, equalTo(List.of(List.of("hosts", "time_series"))));
            }
        }
        try (EsqlQueryResponse resp = run("""
            FROM hosts* METADATA _index_mode, _index
            | WHERE _index_mode == "standard"
            | STATS BY _index, _index_mode
            """)) {
            assertThat(resp.columns(), equalTo(columns));
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(1));
            assertThat(values, equalTo(List.of(List.of("hosts-old", "standard"))));
        }
        try (EsqlQueryResponse resp = run("""
            FROM hosts* METADATA _index_mode, _index
            | STATS BY _index, _index_mode
            | SORT _index
            """)) {
            assertThat(resp.columns(), equalTo(columns));
            List<List<Object>> values = EsqlTestUtils.getValuesList(resp);
            assertThat(values, hasSize(2));
            assertThat(values, equalTo(List.of(List.of("hosts", "time_series"), List.of("hosts-old", "standard"))));
        }

        Exception failure = expectThrows(Exception.class, () -> {
            EsqlQueryRequest request = new EsqlQueryRequest();
            request.query("""
                TS hosts-old METADATA _index_mode, _index
                | STATS BY _index, _index_mode
                | SORT _index
                """);
            request.allowPartialResults(false);
            run(request).close();
        });
        assertThat(failure.getMessage(), containsString("Unknown index [hosts-old]"));
    }

    public void testFieldDoesNotExist() {
        // the old-hosts index doesn't have the cpu field
        Settings settings = Settings.builder().put("mode", "time_series").putList("routing_path", List.of("host", "cluster")).build();
        client().admin()
            .indices()
            .prepareCreate("old-hosts")
            .setSettings(settings)
            .setMapping(
                "@timestamp",
                "type=date",
                "host",
                "type=keyword,time_series_dimension=true",
                "cluster",
                "type=keyword,time_series_dimension=true",
                "memory",
                "type=long,time_series_metric=gauge",
                "request_count",
                "type=integer,time_series_metric=counter"
            )
            .get();
        Randomness.shuffle(docs);
        for (Doc doc : docs) {
            client().prepareIndex("old-hosts")
                .setSource(
                    "@timestamp",
                    doc.timestamp,
                    "host",
                    doc.host,
                    "cluster",
                    doc.cluster,
                    "memory",
                    doc.memory.getBytes(),
                    "request_count",
                    doc.requestCount
                )
                .get();
        }
        client().admin().indices().prepareRefresh("old-hosts").get();
        try (var resp1 = run("""
            TS hosts,old-hosts
            | STATS sum(rate(request_count)), max(last_over_time(cpu)), max(last_over_time(memory)) BY cluster, host
            | SORT cluster, host
            | DROP `sum(rate(request_count))`
            """)) {
            try (var resp2 = run("""
                TS hosts
                | STATS sum(rate(request_count)), max(last_over_time(cpu)), max(last_over_time(memory)) BY cluster, host
                | SORT cluster, host
                | DROP `sum(rate(request_count))`
                """)) {
                List<List<Object>> values1 = EsqlTestUtils.getValuesList(resp1);
                List<List<Object>> values2 = EsqlTestUtils.getValuesList(resp2);
                assertThat(values1, equalTo(values2));
            }
        }
    }

    public void testProfile() {
        EsqlQueryRequest request = new EsqlQueryRequest();
        request.profile(true);
        request.query("TS hosts | STATS sum(rate(request_count)) BY cluster, bucket(@timestamp, 1minute) | SORT cluster");
        try (var resp = run(request)) {
            EsqlQueryResponse.Profile profile = resp.profile();
            List<DriverProfile> dataProfiles = profile.drivers().stream().filter(d -> d.description().equals("data")).toList();
            int totalTimeSeries = 0;
            for (DriverProfile p : dataProfiles) {
                if (p.operators().stream().anyMatch(s -> s.status() instanceof TimeSeriesSourceOperator.Status)) {
                    totalTimeSeries++;
                    assertThat(p.operators(), hasSize(2));
                    assertThat(p.operators().get(1).operator(), equalTo("ExchangeSinkOperator"));
                } else if (p.operators().stream().anyMatch(s -> s.status() instanceof TimeSeriesAggregationOperator.Status)) {
                    assertThat(p.operators(), hasSize(3));
                    assertThat(p.operators().get(0).operator(), equalTo("ExchangeSourceOperator"));
                    assertThat(p.operators().get(1).operator(), containsString("TimeSeriesAggregationOperator"));
                    assertThat(p.operators().get(2).operator(), equalTo("ExchangeSinkOperator"));
                } else {
                    assertThat(p.operators(), hasSize(4));
                    assertThat(p.operators().get(0).operator(), equalTo("ExchangeSourceOperator"));
                    assertThat(p.operators().get(1).operator(), containsString("TimeSeriesExtractFieldOperator"));
                    assertThat(p.operators().get(2).operator(), containsString("EvalOperator"));
                    assertThat(p.operators().get(3).operator(), equalTo("ExchangeSinkOperator"));
                }
            }
            assertThat(totalTimeSeries, equalTo(dataProfiles.size() / 3));
        }
    }
}
