package com.typesafe.zinc;

import sbt.classpath.ClassLoaderCache;
import sbt.compiler.AnalyzingCompiler;

import java.net.URL;
import java.net.URLClassLoader;

// Subvert the private[sbt] on sbt.classpath.ClassLoaderCache by using javac.
final class ClassLoaderCacheAccess {
    private ClassLoaderCacheAccess() {
    }

    static AnalyzingCompiler withClassLoaderCache(AnalyzingCompiler compiler, Object cache) {
        return compiler.withClassLoaderCache((ClassLoaderCache) cache);
    }
    static Object newClassLoaderCache() {
        return new ClassLoaderCache(new URLClassLoader(new URL[]{}));
    }
}
