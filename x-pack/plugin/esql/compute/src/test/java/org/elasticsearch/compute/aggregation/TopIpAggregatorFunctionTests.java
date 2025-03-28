/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.util.BytesRef;

public class TopIpAggregatorFunctionTests extends AbstractTopBytesRefAggregatorFunctionTests {
    @Override
    protected BytesRef randomValue() {
        return new BytesRef(InetAddressPoint.encode(randomIp(randomBoolean())));
    }

    @Override
    protected AggregatorFunctionSupplier aggregatorFunction() {
        return new TopIpAggregatorFunctionSupplier(LIMIT, true);
    }

    @Override
    protected String expectedDescriptionOfAggregator() {
        return "top of ips";
    }
}
