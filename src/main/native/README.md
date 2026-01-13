# Project Neon JNI Native Layer

This directory contains the complete JNI implementation for integrating Project Neon with C/C++ applications and game engines.

## What's Inside

```
native/
├── neon_jni.c              # Full JNI implementation
├── project_neon.h          # C API header for integration
├── CMakeLists.txt          # Cross-platform build configuration
├── BUILD.md                # Detailed build instructions
├── INTEGRATION.md          # Game engine integration guide
└── examples/
    ├── client_example.c    # Example C client
    ├── host_example.c      # Example C host
    ├── CMakeLists.txt      # Examples build configuration
    └── README.md           # Examples documentation
```

## Features

- **Full JNI Implementation**: Complete wrapper for NeonClient and NeonHost
- **Cross-Platform**: Builds on Windows, Linux, and macOS
- **Thread-Safe**: Automatic JVM thread attachment/detachment
- **Error Handling**: Thread-local error reporting with `neon_get_last_error()`
- **Callback Support**: C function pointers for all events
- **Memory Management**: Proper handle lifecycle management
- **Zero Dependencies**: Only requires JDK (no external libraries)

## Quick Start

### 1. Build the Java Components

```bash
cd ProjectNeon
mvn clean compile
```

This generates the JNI headers needed for compilation.

### 2. Build the Native Library

```bash
cd src/main/native
mkdir build && cd build
cmake ..
cmake --build . --config Release
```

This creates:
- **Windows**: `neon_jni.dll`
- **Linux**: `libneon_jni.so`
- **macOS**: `libneon_jni.dylib`

### 3. Run the Examples

```bash
cd examples
mkdir build && cd build
cmake ..
cmake --build .

./client_example
```

## Documentation

- **[BUILD.md](BUILD.md)** - Comprehensive build instructions for all platforms
- **[INTEGRATION.md](INTEGRATION.md)** - Integration guides for Unreal, Unity, and custom engines
- **[examples/README.md](examples/README.md)** - Example programs and usage

## Use Cases

### Game Engines
- **Unreal Engine** (C++) - See INTEGRATION.md for subsystem example
- **Unity** (C# P/Invoke) - See INTEGRATION.md for wrapper example
- **Godot** (GDNative) - C API compatible
- **Custom engines** - Direct C integration

### Applications
- Any C/C++ application needing multiplayer networking
- Cross-platform game clients
- Embedded systems with C support

## API Overview

```c
#include "project_neon.h"

NeonClientHandle* client = neon_client_new("PlayerName");
neon_client_connect(client, 12345, "127.0.0.1:7777");

while (running) {
    neon_client_process_packets(client);
}

neon_client_free(client);
```

See [project_neon.h](project_neon.h) for the complete API.

## Testing

Example programs are provided to test the JNI layer:

1. **Start a relay**: `java -jar target/neon-relay.jar 7777`
2. **Run host example**: `./host_example 12345 127.0.0.1:7777`
3. **Run client example**: `./client_example Player1 12345 127.0.0.1:7777`

See [examples/README.md](examples/README.md) for detailed testing instructions.

## Requirements

### Build Time
- JDK 21+
- CMake 3.15+
- C compiler (GCC, Clang, MSVC)

### Runtime
- JRE 21+ (must be installed on target machine)
- Project Neon JAR (automatically included via JNI)

## Platform Support

| Platform | Architecture | Status |
|----------|-------------|--------|
| Windows  | x64         | ✅ Tested |
| Linux    | x64         | ✅ Tested |
| macOS    | x64         | ✅ Tested |
| macOS    | ARM64       | ⚠️ Untested (should work) |

## Troubleshooting

Common issues and solutions:

**Library not found:**
```bash
export LD_LIBRARY_PATH=/path/to/library:$LD_LIBRARY_PATH
```

**JVM not found:**
- Ensure `JAVA_HOME` is set
- Install JRE on target machine

**Build errors:**
- Run `mvn compile` first to generate headers
- Check CMake output for missing dependencies

See [BUILD.md](BUILD.md) for detailed troubleshooting.

## Contributing

When contributing to the JNI layer:
- Follow the existing code style
- Test on all platforms if possible
- Update documentation for API changes
- Add examples for new features

## License

Same as Project Neon main project. See LICENSE in repository root.

## Support

- **Issues**: https://github.com/Quiet-Terminal-Interactive/ProjectNeon/issues
- **Documentation**: See BUILD.md and INTEGRATION.md
- **Examples**: See examples/ directory
