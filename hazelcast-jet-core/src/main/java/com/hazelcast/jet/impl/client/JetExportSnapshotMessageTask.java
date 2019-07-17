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

package com.hazelcast.jet.impl.client;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.JetExportSnapshotCodec;
import com.hazelcast.client.impl.protocol.task.BlockingMessageTask;
import com.hazelcast.instance.impl.Node;
import com.hazelcast.nio.Connection;
import com.hazelcast.spi.impl.operationservice.Operation;

public class JetExportSnapshotMessageTask extends AbstractJetMessageTask<JetExportSnapshotCodec.RequestParameters, Void>
        implements BlockingMessageTask {

    JetExportSnapshotMessageTask(ClientMessage clientMessage, Node node, Connection connection) {
        super(clientMessage, node, connection, JetExportSnapshotCodec::decodeRequest,
                o -> JetExportSnapshotCodec.encodeResponse());
    }

    @Override
    protected Operation prepareOperation() {
        return getJetService().createExportSnapshotOperation(parameters.jobId, parameters.name, parameters.cancelJob);
    }

    @Override
    public String getMethodName() {
        return "exportSnapshot";
    }

    @Override
    public Object[] getParameters() {
        return new Object[0];
    }
}
