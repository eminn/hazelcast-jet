package com.hazelcast.jet.cpp;

import com.hazelcast.function.FunctionEx;
import com.hazelcast.jet.pipeline.ServiceFactory;
import com.hazelcast.jet.pipeline.StreamStage;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * date: 1/16/20
 * author: emindemirci
 */
public class CppService {

    public static FunctionEx<StreamStage<String>, StreamStage<String>> mapUsingCpp(String libName, String resourceId) {
        return s -> {
            ServiceFactory<MethodInvocationContext, MethodInvocationContext> factory = ServiceFactory
                    .withCreateContextFn(context -> {
                        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                        File directory = context.attachedDirectory(resourceId);
                        Class clazz = classLoader.loadClass("com.hazelcast.jet.cpp.EntryPoint");
                        Object object = clazz.newInstance();
                        Method method = clazz.getMethod("runAsync", String.class, String.class);
                        return new MethodInvocationContext(method, object, directory.toPath().resolve(libName).toString());
                    })
                    .withCreateServiceFn((context, entryPoint) -> entryPoint)
                    .withDestroyServiceFn(methodInvocationContext -> System.gc())
                    .withDestroyContextFn(methodInvocationContext -> System.gc());
            return s.mapUsingServiceAsync(factory, (ctx, data) -> (CompletableFuture<String>) ctx.method.invoke(ctx.instance, data, ctx.libName))
                    .setName("mapUsingPython");
        };
    }

    private static class MethodInvocationContext {
        private Method method;
        private Object instance;
        private String libName;

        public MethodInvocationContext(Method method, Object instance, String libName) {
            this.method = method;
            this.instance = instance;
            this.libName = libName;
        }
    }

}
