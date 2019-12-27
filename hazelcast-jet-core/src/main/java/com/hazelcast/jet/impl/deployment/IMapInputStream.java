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

package com.hazelcast.jet.impl.deployment;

import com.hazelcast.map.IMap;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

import static java.lang.Math.min;

public class IMapInputStream extends InputStream {

    private final IMap<String, byte[]> map;
    private final String id;
    private final int chunkCount;

    private ByteBuffer currentChunk;
    private int currentChunkIndex;

    public IMapInputStream(IMap<String, byte[]> map, String jobId, String fileId) {
        this.map = map;
        this.id = jobId + fileId;
        byte[] array = Objects.requireNonNull(map.get(id), "The file with id: " + fileId +
                " does not exist for the job: " + jobId);
        this.chunkCount = ByteBuffer.wrap(array).getInt();
    }

    @Override
    public void close() throws IOException {
        currentChunk = null;
        currentChunkIndex = -1;
    }

    @Override
    public int read(@Nonnull byte[] b, int off, int len) throws IOException {
        if ((len | off) < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException(String.format(
                    "b.length == %,d, off == %,d, len == %,d", b.length, off, len));
        }
        if (currentChunkIndex == -1) {
            throw new IOException("Stream already closed");
        }
        try {
            if (currentChunkIndex == 0 && !fetchNextChunk()) {
                return -1;
            }
            int readCount = 0;
            do {
                int countToGet = min(len - readCount, currentChunk.remaining());
                currentChunk.get(b, off + readCount, countToGet);
                readCount += countToGet;
            } while (readCount < len && fetchNextChunk());
            return readCount > 0 ? readCount : -1;
        } catch (Exception e) {
            throw new IOException("Reading chunked IMap failed", e);
        }
    }

    @Override
    public int read() throws IOException {
        throw new UnsupportedOperationException("Single-byte read not implemented");
    }

    private boolean fetchNextChunk() {
        if (currentChunkIndex == chunkCount) {
            return false;
        }
        currentChunk = ByteBuffer.wrap(map.get(id + '_' + (currentChunkIndex + 1)));
        // Update currentChunkIndex only after map.get() succeeded
        currentChunkIndex++;
        return true;
    }
}