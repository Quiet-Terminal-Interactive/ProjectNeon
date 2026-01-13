# Building the Project Neon JNI Layer

This document describes how to build the native JNI layer for Project Neon, enabling C/C++ integration with game engines like Unreal Engine, Unity, or custom engines.

## Prerequisites

### All Platforms
- **Java Development Kit (JDK) 21 or later**: Required for JNI headers
- **Maven 3.6+**: For building the Java components
- **CMake 3.15+**: For building native components

### Platform-Specific Requirements

#### Windows
- **Microsoft Visual Studio 2019 or later** (with C/C++ development tools)
- Or **MinGW-w64** with GCC

#### Linux
- **GCC 7.0+** or **Clang 8.0+**
- **build-essential** package: `sudo apt-get install build-essential`

#### macOS
- **Xcode Command Line Tools**: `xcode-select --install`
- **Clang** (included with Xcode)

## Build Steps

### 1. Build Java Components First

The JNI layer requires generated header files from the Java classes. Build the Java project first:

```bash
cd ProjectNeon
mvn clean compile
```

This will:
- Compile all Java sources
- Generate JNI headers in `target/native/include/`
- Create the header files:
  - `com_quietterminal_projectneon_jni_NeonClientJNI.h`
  - `com_quietterminal_projectneon_jni_NeonHostJNI.h`

### 2. Build Native Library

#### Option A: Using CMake (Recommended)

##### Windows

```cmd
cd src\main\native
mkdir build
cd build
cmake ..
cmake --build . --config Release
```

The output will be `neon_jni.dll` in the `build/Release` directory.

##### Linux

```bash
cd src/main/native
mkdir build
cd build
cmake ..
make
```

The output will be `libneon_jni.so` in the `build` directory.

##### macOS

```bash
cd src/main/native
mkdir build
cd build
cmake ..
make
```

The output will be `libneon_jni.dylib` in the `build` directory.

#### Option B: Using Maven Native Plugin

The Maven Native Plugin is configured in `pom.xml` but requires platform-specific configuration. This option is less flexible than CMake.

### 3. Install Native Library

After building, the library needs to be accessible to the JVM. You have several options:

#### Option 1: System Library Path (Recommended for Development)

**Windows:**
```cmd
copy build\Release\neon_jni.dll %JAVA_HOME%\bin\
```

**Linux:**
```bash
sudo cp build/libneon_jni.so /usr/lib/
sudo ldconfig
```

**macOS:**
```bash
sudo cp build/libneon_jni.dylib /usr/local/lib/
```

#### Option 2: Java Library Path

Set the `java.library.path` when running your application:

```bash
java -Djava.library.path=/path/to/native/build -jar your-application.jar
```

#### Option 3: Application Bundle (Recommended for Distribution)

Place the native library in your application's resources and load it from there at runtime.

### 4. Build Example Programs (Optional)

To build and test the C example programs:

```bash
cd src/main/native/examples
mkdir build
cd build
cmake ..
cmake --build .
```

This creates two executables:
- `client_example` - Tests the NeonClient JNI wrapper
- `host_example` - Tests the NeonHost JNI wrapper

## Testing the JNI Layer

### Prerequisites for Testing

1. Build the native library (see steps above)
2. Build the Java JAR: `mvn package`
3. Start a relay server:
   ```bash
   java -jar target/neon-relay.jar 7777
   ```

### Test with Example Programs

#### Terminal 1: Start Relay
```bash
java -jar target/neon-relay.jar 7777
```

#### Terminal 2: Start Host Example
```bash
cd src/main/native/examples/build
./host_example 12345 127.0.0.1:7777
```

#### Terminal 3: Start Client Example
```bash
cd src/main/native/examples/build
./client_example TestClient 12345 127.0.0.1:7777
```

You should see the client connect to the host through the relay, with callbacks being triggered.

## Cross-Compilation

### Building for Multiple Platforms

To create a multi-platform distribution, you'll need to build on each target platform separately. The CMake build system supports cross-compilation toolchains.

#### Example: Linux to Windows Cross-Compilation

```bash
cd src/main/native/build
cmake .. -DCMAKE_TOOLCHAIN_FILE=../cmake/toolchains/mingw-w64.cmake
make
```

### Create Platform-Specific Packages

Use CMake's install target to create distributable packages:

```bash
cmake --build . --target install
cpack
```

## Common Build Issues

### Issue: JNI Headers Not Found

**Error:** `fatal error: jni.h: No such file or directory`

**Solution:** Ensure `JAVA_HOME` is set correctly:
```bash
export JAVA_HOME=/path/to/jdk
```

On Windows:
```cmd
set JAVA_HOME=C:\Path\To\JDK
```

### Issue: Generated Headers Missing

**Error:** `fatal error: com_quietterminal_projectneon_jni_NeonClientJNI.h: No such file or directory`

**Solution:** Run `mvn compile` first to generate the JNI headers.

### Issue: Library Not Found at Runtime

**Error:** `UnsatisfiedLinkError: no neon_jni in java.library.path`

**Solution:** Either:
1. Install the library to the system library path (see step 3)
2. Set `-Djava.library.path=/path/to/library`
3. Set `LD_LIBRARY_PATH` (Linux), `DYLD_LIBRARY_PATH` (macOS), or add to `PATH` (Windows)

### Issue: Wrong Architecture

**Error:** `Can't load IA 32-bit .dll on a AMD 64-bit platform`

**Solution:** Ensure your native library architecture matches your JVM architecture (both 32-bit or both 64-bit).

## Advanced Configuration

### Debug Builds

To build with debug symbols and verbose output:

```bash
cmake .. -DCMAKE_BUILD_TYPE=Debug
cmake --build . --config Debug
```

### Custom Java Location

If CMake can't find your JDK:

```bash
cmake .. -DJAVA_HOME=/path/to/jdk
```

### Static Linking (Advanced)

By default, the JNI library is dynamically linked. To create a static library:

```bash
cmake .. -DBUILD_SHARED_LIBS=OFF
```

Note: Static linking with JNI has limitations and is not recommended.

## Continuous Integration

### GitHub Actions Example

Add this to `.github/workflows/native-build.yml`:

```yaml
name: Native JNI Build

on: [push, pull_request]

jobs:
  build-native:
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest, macos-latest]
    runs-on: ${{ matrix.os }}

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build Java (Generate Headers)
        run: mvn compile

      - name: Build Native Library
        run: |
          cd src/main/native
          mkdir build
          cd build
          cmake ..
          cmake --build . --config Release

      - name: Upload Artifact
        uses: actions/upload-artifact@v3
        with:
          name: neon-jni-${{ matrix.os }}
          path: src/main/native/build/
```

## Distribution

When distributing your application with the JNI layer:

1. **Include platform-specific binaries** in your distribution package
2. **Bundle libraries in resources** and extract them at runtime
3. **Document system requirements** (JDK version, architecture)
4. **Test on clean systems** without development tools installed

### Recommended Directory Structure for Distribution

```
your-application/
├── lib/
│   ├── project-neon-0.2.0.jar
│   └── native/
│       ├── windows-x64/
│       │   └── neon_jni.dll
│       ├── linux-x64/
│       │   └── libneon_jni.so
│       └── macos-x64/
│           └── libneon_jni.dylib
├── bin/
│   └── your-application.jar
└── README.md
```

## Next Steps

- See [examples/README.md](examples/README.md) for usage examples
- Read [INTEGRATION.md](INTEGRATION.md) for game engine integration guides
- Check [../../../docs/JNI_API.md](../../../docs/JNI_API.md) for API documentation

## Support

For build issues, please open an issue at:
https://github.com/Quiet-Terminal-Interactive/ProjectNeon/issues
