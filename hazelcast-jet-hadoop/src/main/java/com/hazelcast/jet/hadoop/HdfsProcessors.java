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

package com.hazelcast.jet.hadoop;

import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.function.BiFunctionEx;
import com.hazelcast.jet.function.FunctionEx;
import com.hazelcast.jet.hadoop.impl.ReadHdfsP;
import com.hazelcast.jet.hadoop.impl.SerializableConfiguration;
import com.hazelcast.jet.hadoop.impl.SerializableJobConf;
import com.hazelcast.jet.hadoop.impl.WriteHdfsP;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobConf;

import javax.annotation.Nonnull;

/**
 * Static utility class with factories of Apache Hadoop HDFS source and sink
 * processors.
 *
 * @since 3.0
 */
public final class HdfsProcessors {

    private HdfsProcessors() {
    }

    /**
     * Returns a supplier of processors for
     * {@link HdfsSources#hdfs(JobConf, BiFunctionEx)}.
     */
    @Nonnull
    public static <K, V, R> ReadHdfsP.MetaSupplier<K, V, R> readHdfsP(
            @Nonnull JobConf jobConf, @Nonnull BiFunctionEx<K, V, R> mapper
    ) {
        return new ReadHdfsP.MetaSupplier<>(SerializableJobConf.asSerializable(jobConf), mapper);
    }

    /**
     * Returns a supplier of processors for
     * {@link HdfsSinks#hdfs(Configuration, FunctionEx, FunctionEx)}.
     */
    @Nonnull
    public static <E, K, V> ProcessorMetaSupplier writeHdfsP(
            @Nonnull Configuration jobConf,
            @Nonnull FunctionEx<? super E, K> extractKeyFn,
            @Nonnull FunctionEx<? super E, V> extractValueFn
    ) {
        return new WriteHdfsP.MetaSupplier<>(SerializableConfiguration.asSerializable(jobConf), extractKeyFn,
                extractValueFn);
    }
}
