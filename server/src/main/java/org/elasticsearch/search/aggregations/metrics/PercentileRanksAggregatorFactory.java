/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.aggregations.metrics;

import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.CardinalityUpperBound;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.aggregations.support.TimeSeriesValuesSourceType;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.support.ValuesSourceRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

class PercentileRanksAggregatorFactory extends ValuesSourceAggregatorFactory {

    private final PercentilesAggregatorSupplier aggregatorSupplier;
    private final double[] percents;
    private final PercentilesConfig percentilesConfig;
    private final boolean keyed;

    static void registerAggregators(ValuesSourceRegistry.Builder builder) {
        builder.register(
            PercentileRanksAggregationBuilder.REGISTRY_KEY,
            List.of(
                CoreValuesSourceType.NUMERIC,
                CoreValuesSourceType.DATE,
                CoreValuesSourceType.BOOLEAN,
                TimeSeriesValuesSourceType.COUNTER
            ),
            (name, config, context, parent, percents, percentilesConfig, keyed, formatter, metadata) -> percentilesConfig
                .createPercentileRanksAggregator(name, config, context, parent, percents, keyed, formatter, metadata),
            true
        );
    }

    PercentileRanksAggregatorFactory(
        String name,
        ValuesSourceConfig config,
        double[] percents,
        PercentilesConfig percentilesConfig,
        boolean keyed,
        AggregationContext context,
        AggregatorFactory parent,
        AggregatorFactories.Builder subFactoriesBuilder,
        Map<String, Object> metadata,
        PercentilesAggregatorSupplier aggregatorSupplier
    ) throws IOException {
        super(name, config, context, parent, subFactoriesBuilder, metadata);
        this.percents = percents;
        this.percentilesConfig = percentilesConfig;
        this.keyed = keyed;
        this.aggregatorSupplier = aggregatorSupplier;
    }

    @Override
    protected Aggregator createUnmapped(Aggregator parent, Map<String, Object> metadata) throws IOException {
        final InternalNumericMetricsAggregation.MultiValue empty = percentilesConfig.createEmptyPercentileRanksAggregator(
            name,
            percents,
            keyed,
            config.format(),
            metadata
        );
        final Predicate<String> hasMetric = s -> PercentilesConfig.indexOfKey(percents, Double.parseDouble(s)) >= 0;
        return new NonCollectingMultiMetricAggregator(name, context, parent, empty, hasMetric, metadata);
    }

    @Override
    protected Aggregator doCreateInternal(Aggregator parent, CardinalityUpperBound bucketCardinality, Map<String, Object> metadata)
        throws IOException {
        return aggregatorSupplier.build(name, config, context, parent, percents, percentilesConfig, keyed, config.format(), metadata);
    }
}
