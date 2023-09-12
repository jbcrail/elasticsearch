/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.profiling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.ParentTaskAssigningClient;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.SumAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.SumBucketPipelineAggregationBuilder;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.time.Instant;
import java.util.concurrent.Executor;

public class TransportGetTopNTracesAction extends HandledTransportAction<GetTopNRequest, GetTopNResponse> {
    private static final Logger log = LogManager.getLogger(TransportGetTopNTracesAction.class);

    private final NodeClient nodeClient;
    private final ClusterService clusterService;
    private final TransportService transportService;
    private final Executor responseExecutor;

    @Inject
    public TransportGetTopNTracesAction(
        Settings settings,
        ThreadPool threadPool,
        ClusterService clusterService,
        TransportService transportService,
        ActionFilters actionFilters,
        NodeClient nodeClient,
        IndexNameExpressionResolver resolver
    ) {
        super(GetTopNTracesAction.NAME, transportService, actionFilters, GetTopNRequest::new);
        this.nodeClient = nodeClient;
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.responseExecutor = threadPool.executor(ProfilingPlugin.PROFILING_THREAD_POOL_NAME);
    }

    @Override
    protected void doExecute(Task submitTask, GetTopNRequest request, ActionListener<GetTopNResponse> submitListener) {
        long start = System.nanoTime();
        Client client = new ParentTaskAssigningClient(this.nodeClient, transportService.getLocalNode(), submitTask);
        EventsIndex mediumDownsampled = EventsIndex.MEDIUM_DOWNSAMPLED;
        client.prepareSearch(mediumDownsampled.getName())
            .setSize(0)
            .setQuery(request.getQuery())
            .setTrackTotalHits(true)
            .execute(ActionListener.wrap(searchResponse -> {
                long sampleCount = searchResponse.getHits().getTotalHits().value;
                EventsIndex resampledIndex = mediumDownsampled.getResampledIndex(request.getSampleSize(), sampleCount);
                log.debug("getResampledIndex took [" + (System.nanoTime() - start) / 1_000_000.0d + " ms].");
                searchEventAggregateByField(client, request, resampledIndex, submitListener);
            }, e -> {
                // All profiling-events data streams are created lazily. In a relatively empty cluster it can happen that there are so few
                // data that we need to resort to the "full" events stream. As this is an edge case we'd rather fail instead of prematurely
                // checking for existence in all cases.
                if (e instanceof IndexNotFoundException) {
                    String missingIndex = ((IndexNotFoundException) e).getIndex().getName();
                    EventsIndex fullIndex = EventsIndex.FULL_INDEX;
                    log.debug("Index [{}] does not exist. Using [{}] instead.", missingIndex, fullIndex.getName());
                    searchEventAggregateByField(client, request, fullIndex, submitListener);
                } else {
                    submitListener.onFailure(e);
                }
            }));
    }

    private long computeBucketWidth(long timeFrom, long timeTo, int numBuckets) {
        return Math.max((timeTo - timeFrom) / numBuckets, 1);
    }

    private SearchRequestBuilder buildSearchRequest(
        Client client,
        GetTopNRequest request,
        String index,
        String searchField,
        long bucketWidth,
        String executionHintMode
    ) {
        DateHistogramInterval fixedInterval = new DateHistogramInterval("" + bucketWidth + "s");
        SumAggregationBuilder sumAggregation = new SumAggregationBuilder("count").field("Stacktrace.count");
        DateHistogramAggregationBuilder overTimeAggregation = new DateHistogramAggregationBuilder("over_time").field("@timestamp")
            .fixedInterval(fixedInterval)
            .subAggregation(sumAggregation);
        /*
        TopMetricsAggregationBuilder topMetricsAggregation = new TopMetricsAggregationBuilder(
            "sample", new FieldSortBuilder("@timestamp").order(SortOrder.DESC), 1,
            "host.name", "host.ip");
        */
        TermsAggregationBuilder termAggregation = new TermsAggregationBuilder("group_by").size(99)
            .field(searchField)
            .executionHint(executionHintMode);
        /*
        if (searchField.equals("host.id")) {
            termAggregation.subAggregation(topMetricsAggregation);
        }
        */
        termAggregation.subAggregation(overTimeAggregation)
            .subAggregation(new SumBucketPipelineAggregationBuilder("total_count", "over_time>count"));
        return client.prepareSearch(index)
            .setTrackTotalHits(false)
            .setSize(0)
            .setQuery(request.getQuery())
            .addAggregation(termAggregation)
            .addAggregation(overTimeAggregation)
            .addAggregation(sumAggregation);
    }

    private void searchEventAggregateByField(
        Client client,
        GetTopNRequest request,
        EventsIndex eventsIndex,
        ActionListener<GetTopNResponse> submitListener
    ) {
        long start = System.nanoTime();
        GetTopNResponseBuilder responseBuilder = new GetTopNResponseBuilder();
        buildSearchRequest(
            client,
            request,
            eventsIndex.getName(),
            "Stacktrace.id",
            computeBucketWidth(0, 60 * 60 * 24, 50),
            "global_ordinals"
        ).execute(ActionListener.wrap(searchResponse -> {
            log.debug("searchEventAggregateByField took [" + (System.nanoTime() - start) / 1_000_000.0d + " ms].");
            submitListener.onResponse(responseBuilder.build());
        }, e -> {
            // Data streams are created lazily; if even the "full" index does not exist no data have been indexed yet.
            if (e instanceof IndexNotFoundException) {
                log.debug("Index [{}] does not exist. Returning empty response.", ((IndexNotFoundException) e).getIndex());
                submitListener.onResponse(responseBuilder.build());
            } else {
                submitListener.onFailure(e);
            }
        }));
    }

    private static class GetTopNResponseBuilder {
        private Instant start;
        private Instant end;
        private int totalCount;

        public Instant getStart() {
            return start;
        }

        public void setStart(Instant start) {
            this.start = start;
        }

        public Instant getEnd() {
            return end;
        }

        public void setEnd(Instant end) {
            this.end = end;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(int totalCount) {
            this.totalCount = totalCount;
        }

        public GetTopNResponse build() {
            return new GetTopNResponse(totalCount);
        }
    }
}
