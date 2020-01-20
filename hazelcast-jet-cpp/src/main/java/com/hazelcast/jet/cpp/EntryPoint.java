package com.hazelcast.jet.cpp;


import com.hazelcast.jet.impl.util.IOUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

public class EntryPoint {
    public EntryPoint() {
    }

    static {
        try {
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("libEntryPoint.dylib");
            File library = File.createTempFile("library", "dylib");
            OutputStream out = new FileOutputStream(library);
            IOUtil.copyStream(in, out);
            in.close();
            out.close();
            System.load(library.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load required library", e);
        }
    }

    public native String run(String data, String libraryName);

    public CompletableFuture<String> runAsync(String data, String libraryName) {
        return CompletableFuture.supplyAsync(() -> run(data, libraryName));
    }

    @Override
    public void finalize() throws Throwable {
        System.out.println("unloaded");
    }
}
