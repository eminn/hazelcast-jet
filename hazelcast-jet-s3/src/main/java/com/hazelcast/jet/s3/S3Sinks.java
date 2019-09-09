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

package com.hazelcast.jet.s3;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.hazelcast.jet.function.FunctionEx;
import com.hazelcast.jet.function.SupplierEx;
import com.hazelcast.jet.pipeline.Sink;
import com.hazelcast.jet.pipeline.SinkBuilder;
import com.hazelcast.util.ExceptionUtil;
import com.hazelcast.util.StringUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.amazonaws.services.s3.internal.Constants.MAXIMUM_UPLOAD_PARTS;
import static com.amazonaws.services.s3.internal.Constants.MB;

/**
 * Contains factory methods for creating AWS S3 sinks.
 */
public final class S3Sinks {

    private S3Sinks() {
    }

    /**
     * Convenience for {@link #s3(String, String, Charset, SupplierEx, FunctionEx)}
     * Uses {@link Object#toString()} to convert the items to lines.
     */
    @Nonnull
    public static <T> Sink<? super T> s3(
            @Nonnull String bucketName,
            @Nonnull SupplierEx<? extends AmazonS3> clientSupplier
    ) {
        return s3(bucketName, null, StandardCharsets.UTF_8, clientSupplier, Object::toString);
    }

    /**
     * Creates an AWS S3 {@link Sink} which writes items to files into the
     * given bucket. Sink converts each item to string using given {@code
     * toStringFn} and writes it as a line. The sink creates a file
     * in the bucket for each processor instance. Name of the file will include
     * an user provided prefix (if defined) and processor's global index,
     * for example the processor having the
     * index 2 with prefix {@code my-object-} will create the object
     * {@code my-object-2}.
     * <p>
     * No state is saved to snapshot for this sink. If the job is restarted
     * previously written files will be overwritten.
     * <p>
     * The default local parallelism for this sink is 1.
     * <p>
     * Here is an example which reads from a map and writes the entries
     * to given bucket using {@link Object#toString()} to convert the
     * values to a line.
     *
     * <pre>{@code
     * Pipeline p = Pipeline.create();
     * p.drawFrom(Sources.map("map"))
     *  .drainTo(S3Sinks.s3("bucket", "my-map-", StandardCharsets.UTF_8,
     *      () -> AmazonS3ClientBuilder.standard().build(),
     *      Object::toString
     * ));
     * }</pre>
     *
     * @param <T>            type of the items the sink accepts
     * @param bucketName     the name of the bucket
     * @param prefix         the prefix to be included in the file name
     * @param charset        the charset to be used when encoding the strings
     * @param clientSupplier S3 client supplier
     * @param toStringFn     the function which converts each item to its
     *                       string representation
     */
    @Nonnull
    public static <T> Sink<? super T> s3(
            @Nonnull String bucketName,
            @Nullable String prefix,
            @Nonnull Charset charset,
            @Nonnull SupplierEx<? extends AmazonS3> clientSupplier,
            @Nonnull FunctionEx<? super T, String> toStringFn

    ) {
        String charsetName = charset.name();
        return SinkBuilder
                .sinkBuilder("s3-sink", context ->
                        new S3SinkContext<>(bucketName, prefix, charsetName, context.globalProcessorIndex(),
                                toStringFn, clientSupplier))
                .<T>receiveFn(S3SinkContext::receive)
                .flushFn(S3SinkContext::flush)
                .destroyFn(S3SinkContext::close)
                .build();
    }

    static final class S3SinkContext<T> {

        static final int MINIMUM_PART_NUMBER = 1;
        // visible for testing
        static int maximumPartNumber = MAXIMUM_UPLOAD_PARTS;

        private static final int DEFAULT_MINIMUM_UPLOAD_PART_SIZE = 5 * MB;

        private final String bucketName;
        private final String prefix;
        private final int processorIndex;
        private final AmazonS3 s3Client;
        private final FunctionEx<? super T, String> toStringFn;
        private final Charset charset;
        private final byte[] lineSeparator;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(2 * DEFAULT_MINIMUM_UPLOAD_PART_SIZE);
        private final List<PartETag> partETags = new ArrayList<>();

        private int partNumber = MINIMUM_PART_NUMBER; // must be between 1 and maximumPartNumber
        private int fileNumber;
        private String uploadId;

        private S3SinkContext(
                String bucketName,
                @Nullable String prefix,
                String charsetName, int processorIndex,
                FunctionEx<? super T, String> toStringFn,
                SupplierEx<? extends AmazonS3> clientSupplier) {
            this.bucketName = bucketName;
            String trimmedPrefix = StringUtil.trim(prefix);
            this.prefix = StringUtil.isNullOrEmpty(trimmedPrefix) ? "" : trimmedPrefix;
            this.processorIndex = processorIndex;
            this.s3Client = clientSupplier.get();
            this.toStringFn = toStringFn;
            this.charset = Charset.forName(charsetName);
            this.lineSeparator = System.lineSeparator().getBytes(charset);

            checkIfBucketExists();
        }

        private void initiateUpload() {
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName, key());
            uploadId = s3Client.initiateMultipartUpload(initRequest).getUploadId();
        }

        private void checkIfBucketExists() {
            if (!s3Client.doesBucketExistV2(bucketName)) {
                throw new IllegalArgumentException("Bucket [" + bucketName + "] does not exist");
            }
        }

        private void receive(T item) {
            buffer.put(toStringFn.apply(item).getBytes(charset));
            buffer.put(lineSeparator);
        }

        private void flush() {
            if (uploadId == null) {
                initiateUpload();
            }
            if (buffer.position() > DEFAULT_MINIMUM_UPLOAD_PART_SIZE) {
                boolean isLastPart = partNumber == maximumPartNumber;
                flushBuffer(isLastPart);
            }
        }

        private void close() {
            try {
                flushBuffer(true);
            } finally {
                s3Client.shutdown();
            }
        }

        private void flushBuffer(boolean isLastPart) {
            if (buffer.position() == 0) {
                return;
            }

            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            buffer.clear();
            UploadPartRequest uploadRequest = new UploadPartRequest()
                    .withBucketName(bucketName)
                    .withKey(key())
                    .withUploadId(uploadId)
                    .withPartNumber(partNumber)

                    .withInputStream(new ByteArrayInputStream(bytes))
                    .withPartSize(bytes.length)
                    .withLastPart(isLastPart);
            UploadPartResult uploadResult = s3Client.uploadPart(uploadRequest);
            partETags.add(uploadResult.getPartETag());
            partNumber++;
            if (isLastPart) {
                completeUpload();
            }
        }

        private void completeUpload() {
            try {
                if (partETags.isEmpty()) {
                    abortUpload();
                } else {
                    CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(bucketName, key(),
                            uploadId, partETags);
                    s3Client.completeMultipartUpload(compRequest);
                    partETags.clear();
                    partNumber = MINIMUM_PART_NUMBER;
                    uploadId = null;
                    fileNumber++;
                }
            } catch (Exception e) {
                abortUpload();
                ExceptionUtil.rethrow(e);
            }
        }

        private void abortUpload() {
            s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(bucketName, key(), uploadId));
        }

        private String key() {
            return prefix + processorIndex + (fileNumber == 0 ? "" : "." + fileNumber);
        }
    }
}
