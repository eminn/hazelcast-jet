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

package com.hazelcast.jet.impl.execution.init;

import com.hazelcast.internal.util.Preconditions;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.config.ResourceConfig;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.impl.JobRepository;
import com.hazelcast.jet.impl.deployment.IMapInputStream;
import com.hazelcast.jet.impl.util.ExceptionUtil;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.IMap;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentMap;

import static com.hazelcast.jet.Util.idToString;
import static com.hazelcast.jet.impl.util.Util.unzip;

public final class Contexts {

    private Contexts() {
    }

    static class MetaSupplierCtx implements ProcessorMetaSupplier.Context {
        private final JetInstance jetInstance;
        private final long jobId;
        private final long executionId;
        private final JobConfig jobConfig;
        private final ILogger logger;
        private final String vertexName;
        private final int localParallelism;
        private final int totalParallelism;
        private final int memberCount;
        private final ProcessingGuarantee processingGuarantee;

        MetaSupplierCtx(
                JetInstance jetInstance,
                long jobId,
                long executionId,
                JobConfig jobConfig,
                ILogger logger,
                String vertexName,
                int localParallelism,
                int totalParallelism,
                int memberCount,
                ProcessingGuarantee processingGuarantee
        ) {
            this.jetInstance = jetInstance;
            this.jobId = jobId;
            this.executionId = executionId;
            this.jobConfig = jobConfig;
            this.logger = logger;
            this.vertexName = vertexName;
            this.totalParallelism = totalParallelism;
            this.localParallelism = localParallelism;
            this.memberCount = memberCount;
            this.processingGuarantee = processingGuarantee;
        }

        @Nonnull
        @Override
        public JetInstance jetInstance() {
            return jetInstance;
        }

        @Override
        public long jobId() {
            return jobId;
        }

        @Override
        public long executionId() {
            return executionId;
        }

        @Override @Nonnull
        public JobConfig jobConfig() {
            return jobConfig;
        }

        @Override
        public int totalParallelism() {
            return totalParallelism;
        }

        @Override
        public int localParallelism() {
            return localParallelism;
        }

        @Override
        public int memberCount() {
            return memberCount;
        }

        @Nonnull @Override
        public String vertexName() {
            return vertexName;
        }

        @Nonnull @Override
        public ILogger logger() {
            return logger;
        }

        @Override
        public ProcessingGuarantee processingGuarantee() {
            return processingGuarantee;
        }

    }

    static class ProcSupplierCtx extends MetaSupplierCtx implements ProcessorSupplier.Context {

        private final int memberIndex;
        private final ConcurrentMap<String, File> localFiles;

        @SuppressWarnings("checkstyle:ParameterNumber")
        ProcSupplierCtx(
                JetInstance jetInstance,
                long jobId,
                long executionId,
                JobConfig jobConfig,
                ILogger logger,
                String vertexName,
                int localParallelism,
                int totalParallelism,
                int memberIndex,
                int memberCount,
                ProcessingGuarantee processingGuarantee,
                ConcurrentMap<String, File> localFiles
        ) {
            super(jetInstance, jobId, executionId, jobConfig, logger, vertexName, localParallelism, totalParallelism,
                    memberCount, processingGuarantee);
            this.memberIndex = memberIndex;
            this.localFiles = localFiles;
        }

        @Override
        public int memberIndex() {
            return memberIndex;
        }

        @Nonnull @Override
        public File attachedDirectory(@Nonnull String id) {
            Preconditions.checkHasText(id, "id cannot be null or empty");
            findResourceConfigOrThrowException(id);
            return localFiles.computeIfAbsent(id, this::extractFileToDisk);
        }

        @Nonnull @Override
        public File attachedFile(@Nonnull String id) {
            Preconditions.checkHasText(id, "id cannot be null or empty");
            ResourceConfig resourceConfig = findResourceConfigOrThrowException(id);
            return attachedDirectory(id).toPath().resolve(resourceConfig.getUrl().getFile()).toFile();
        }

        public ConcurrentMap<String, File> localFiles() {
            return localFiles;
        }

        private File extractFileToDisk(String key) {
            IMap<String, byte[]> map = jetInstance().getMap(JobRepository.jobFileStorageMapName(jobId()));
            try (IMapInputStream inputStream = new IMapInputStream(map, key)) {
                String prefix = "jet-" + jetInstance().getName() + "-" + idToString(jobId()) + "-" + key;
                Path directory = Files.createTempDirectory(prefix);
                unzip(inputStream, directory);
                return directory.toFile();
            } catch (IOException e) {
                throw ExceptionUtil.rethrow(e);
            }
        }

        private ResourceConfig findResourceConfigOrThrowException(String id) {
            return jobConfig()
                    .getResourceConfigs()
                    .stream()
                    .filter(config -> config.getId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Resource with id:" + id + "cannot be found"));
        }
    }

    public static class ProcCtx extends ProcSupplierCtx implements Processor.Context {

        private final int localProcessorIndex;
        private final int globalProcessorIndex;

        @SuppressWarnings("checkstyle:ParameterNumber")
        public ProcCtx(JetInstance instance, long jobId, long executionId, JobConfig jobConfig,
                       ILogger logger, String vertexName, int localProcessorIndex,
                       int globalProcessorIndex, ProcessingGuarantee processingGuarantee, int localParallelism,
                       int memberIndex, int memberCount, ConcurrentMap<String, File> localFiles) {
            super(instance, jobId, executionId, jobConfig, logger, vertexName, localParallelism,
                    memberCount * localParallelism, memberIndex, memberCount, processingGuarantee,
                    localFiles);
            this.localProcessorIndex = localProcessorIndex;
            this.globalProcessorIndex = globalProcessorIndex;
        }

        @Override
        public int localProcessorIndex() {
            return localProcessorIndex;
        }

        @Override
        public int globalProcessorIndex() {
            return globalProcessorIndex;
        }

    }
}
