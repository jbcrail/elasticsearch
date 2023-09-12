/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.profiling;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ChunkedToXContentHelper;
import org.elasticsearch.common.xcontent.ChunkedToXContentObject;
import org.elasticsearch.xcontent.ToXContent;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;

/*
 * TopNSample
 * - Category String
 * - Percentage Double
 * - Timestamp Integer
 * - Count Optional<Integer>
 *
 * TopNResponse
 * - TotalCount Integer
 * - TopN List<TopNSample>
 * - Labels Map<String, String>
 * - Metadata Map<String, List<StackFrameMetadata>>
 */
public class GetTopNResponse extends ActionResponse implements ChunkedToXContentObject {
    private final int totalCount;

    public GetTopNResponse(StreamInput in) throws IOException {
        this.totalCount = in.readInt();
    }

    public GetTopNResponse(int totalCount) {
        this.totalCount = totalCount;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeInt(totalCount);
    }

    public int getTotalCount() {
        return totalCount;
    }

    @Override
    public Iterator<? extends ToXContent> toXContentChunked(ToXContent.Params params) {
        return Iterators.concat(
            ChunkedToXContentHelper.startObject(),
            Iterators.single((b, p) -> b.field("total_count", totalCount)),
            ChunkedToXContentHelper.endObject()
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GetTopNResponse response = (GetTopNResponse) o;
        return totalCount == response.totalCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalCount);
    }
}
