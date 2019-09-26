/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.hadoop.impl;

import com.hazelcast.core.Member;
import com.hazelcast.jet.Traverser;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.processor.Processors;
import com.hazelcast.jet.function.BiFunctionEx;
import com.hazelcast.jet.hadoop.HdfsSources;
import com.hazelcast.nio.Address;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.hazelcast.jet.Traversers.traverseIterable;
import static com.hazelcast.jet.impl.util.ExceptionUtil.sneakyThrow;
import static com.hazelcast.jet.impl.util.Util.uncheckCall;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.mapred.Reporter.NULL;

/**
 * See {@link HdfsSources#hdfs}.
 */
public final class ReadHdfsP<K, V, R> extends AbstractProcessor {

    private final Traverser<R> trav;
    private final BiFunctionEx<K, V, R> projectionFn;

    private ReadHdfsP(@Nonnull List<RecordReader> recordReaders, @Nonnull BiFunctionEx<K, V, R> projectionFn) {
        this.trav = traverseIterable(recordReaders).flatMap(this::traverseRecordReader);
        this.projectionFn = projectionFn;
    }

    @Override
    public boolean isCooperative() {
        return false;
    }

    @Override
    public boolean complete() {
        return emitFromTraverser(trav);
    }

    private Traverser<R> traverseRecordReader(RecordReader<K, V> r) {
        return () -> {
            K key = r.createKey();
            V value = r.createValue();
            try {
                while (r.next(key, value)) {
                    R projectedRecord = projectionFn.apply(key, value);
                    if (projectedRecord != null) {
                        return projectedRecord;
                    }
                }
                r.close();
                return null;
            } catch (IOException e) {
                throw sneakyThrow(e);
            }
        };
    }

    public static class MetaSupplier<K, V, R> extends ReadHdfsMetaSupplierBase {

        static final long serialVersionUID = 1L;

        private final SerializableJobConf jobConf;
        private final BiFunctionEx<K, V, R> mapper;

        private transient Map<Address, List<IndexedInputSplit>> assigned;


        public MetaSupplier(@Nonnull SerializableJobConf jobConf, @Nonnull BiFunctionEx<K, V, R> mapper) {
            this.jobConf = jobConf;
            this.mapper = mapper;
        }

        @Override
        public int preferredLocalParallelism() {
            return 2;
        }

        @Override
        public void init(@Nonnull Context context) throws Exception {
            super.init(context);
            int totalParallelism = context.totalParallelism();
            InputFormat inputFormat = jobConf.getInputFormat();
            InputSplit[] splits = inputFormat.getSplits(jobConf, totalParallelism);
            IndexedInputSplit[] indexedInputSplits = new IndexedInputSplit[splits.length];
            Arrays.setAll(indexedInputSplits, i -> new IndexedInputSplit(i, splits[i]));

            Address[] addrs = context.jetInstance().getCluster().getMembers()
                                     .stream().map(Member::getAddress).toArray(Address[]::new);
            assigned = assignSplitsToMembers(indexedInputSplits, addrs);
            printAssignments(assigned);
        }

        @Nonnull
        @Override
        public Function<Address, ProcessorSupplier> get(@Nonnull List<Address> addresses) {
            return address -> new Supplier<>(
                    jobConf,
                    assigned.get(address) != null ? assigned.get(address) : emptyList(),
                    mapper);
        }
    }

    private static class Supplier<K, V, R> extends HdfsProcessorSupplierBase<K, V, R> {

        static final long serialVersionUID = 1L;

        Supplier(SerializableJobConf jobConf,
                 List<IndexedInputSplit> assignedSplits,
                 @Nonnull BiFunctionEx<K, V, R> mapper
        ) {
            super(jobConf, assignedSplits, mapper);
        }

        @Override @Nonnull
        public List<Processor> get(int count) {
            Map<Integer, List<IndexedInputSplit>> processorToSplits = getProcessorToSplits(count);
            JobConf jobConfCasted = (JobConf) jobConf;
            InputFormat inputFormat = jobConfCasted.getInputFormat();

            return processorToSplits
                    .values().stream()
                    .map(splits -> splits.isEmpty()
                            ? Processors.noopP().get()
                            : new ReadHdfsP<>(splits.stream()
                                                    .map(IndexedInputSplit::getOldSplit)
                                                    .map(split -> uncheckCall(() ->
                                                            inputFormat.getRecordReader(split, jobConfCasted, NULL)))
                                                    .collect(toList()), mapper)
                    ).collect(toList());
        }
    }
}
