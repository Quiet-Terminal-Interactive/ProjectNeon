# JNI Native Implementation

This directory contains the native C implementation for JNI bindings.

## Building the Native Library

### Linux

```bash
# Compile the JNI implementation
cd src/main/native
gcc -shared -fPIC -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux \
    -o libneon_jni.so neon_jni.c

# Copy to library path
sudo cp libneon_jni.so /usr/local/lib/
sudo ldconfig
```

### macOS

```bash
gcc -shared -fPIC -I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin \
    -o libneon_jni.dylib neon_jni.c
```

### Windows

```bash
gcc -shared -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" \
    -o neon_jni.dll neon_jni.c -Wl,--add-stdcall-alias
```

## Note

The JNI implementation is provided as stubs. To fully implement the native layer,
you need to:

1. Generate JNI headers: `javac -h . NeonClientJNI.java NeonHostJNI.java`
2. Implement the native methods in C
3. Link against the JVM library

For most Java-only use cases, you can use the Java classes directly without
the JNI layer. The JNI bindings are only needed for C/C++ game engine integration.
