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
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.function.SupplierEx;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static java.lang.System.lineSeparator;
import static java.util.stream.IntStream.range;

public class S3MockTest extends S3TestBase {

    @ClassRule
    public static S3MockContainer s3MockContainer = new S3MockContainer();

    private static final String SOURCE_BUCKET = "source-bucket";
    private static final String SINK_BUCKET = "sink-bucket";

    private static AmazonS3 s3Client;

    private JetInstance jet;

    @BeforeClass
    public static void setupS3() {
        s3Client = s3MockContainer.client();
        s3Client.createBucket(SOURCE_BUCKET);
        s3Client.createBucket(SINK_BUCKET);
    }

    @Before
    public void setup() {
        jet = createJetMembers(2)[0];
    }

    @Test
    public void testMockSink() {
        testSink(jet, SINK_BUCKET);
    }

    @Test
    public void testMockSource() {
        int objectCount = 20;
        int lineCount = 100;
        generateAndUploadObjects(objectCount, lineCount);

        testSource(jet, SOURCE_BUCKET, "object-", objectCount, lineCount);
    }

    SupplierEx<AmazonS3> client() {
        return () -> S3MockContainer.client(s3MockContainer.endpointURL());
    }


    private void generateAndUploadObjects(int objectCount, int lineCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < objectCount; i++) {
            range(0, lineCount).forEach(j -> builder.append("line-").append(j).append(lineSeparator()));
            s3Client.putObject(SOURCE_BUCKET, "object-" + i, builder.toString());
            builder.setLength(0);
        }
    }
}
