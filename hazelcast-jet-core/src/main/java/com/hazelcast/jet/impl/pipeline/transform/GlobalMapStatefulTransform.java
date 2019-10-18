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

package com.hazelcast.jet.impl.pipeline.transform;

import com.hazelcast.jet.function.TriFunction;
import com.hazelcast.jet.impl.pipeline.Planner;
import com.hazelcast.jet.impl.pipeline.Planner.PlannerVertex;
import com.hazelcast.jet.impl.util.ConstantFunctionEx;
import com.hazelcast.function.ToLongFunctionEx;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

import static com.hazelcast.jet.core.processor.Processors.mapStatefulP;

public class GlobalMapStatefulTransform<T, S, R> extends AbstractTransform {

    private final ToLongFunctionEx<? super T> timestampFn;
    private final Supplier<? extends S> createFn;
    private final TriFunction<? super S, Object, ? super T, ? extends R> statefulMapFn;

    public GlobalMapStatefulTransform(
            @Nonnull Transform upstream,
            @Nonnull ToLongFunctionEx<? super T> timestampFn,
            @Nonnull Supplier<? extends S> createFn,
            @Nonnull TriFunction<? super S, Object, ? super T, ? extends R> statefulMapFn
    ) {
        super("map-stateful-global", upstream);
        this.timestampFn = timestampFn;
        this.createFn = createFn;
        this.statefulMapFn = statefulMapFn;
    }

    @Override
    public void addToDag(Planner p) {
        ConstantFunctionEx<T, Integer> keyFn = new ConstantFunctionEx<>(name().hashCode());
        PlannerVertex pv = p.addVertex(this, name(), 1,
                mapStatefulP(Long.MAX_VALUE, keyFn, timestampFn, createFn, statefulMapFn, null));
        p.addEdges(this, pv.v, edge -> edge.partitioned(keyFn).distributed());
    }
}
