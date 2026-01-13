# Project Neon JNI Integration Guide

This guide explains how to integrate Project Neon's JNI layer into various game engines and C/C++ applications.

## Overview

The Project Neon JNI layer provides a C-compatible API that wraps the Java-based networking protocol. This allows integration with:

- **Unreal Engine** (C++)
- **Unity** (via C# P/Invoke or native plugins)
- **Godot** (via GDNative)
- **Custom C/C++ game engines**
- **Any application with C FFI support**

## Architecture

```
┌─────────────────────────────────────┐
│   Your Game Engine / Application    │
│          (C/C++/C#/etc.)            │
└──────────────┬──────────────────────┘
               │
               │ C API Calls
               ▼
┌─────────────────────────────────────┐
│     project_neon.h (C Headers)      │
└──────────────┬──────────────────────┘
               │
               │ JNI Calls
               ▼
┌─────────────────────────────────────┐
│   neon_jni.c (JNI Implementation)   │
└──────────────┬──────────────────────┘
               │
               │ Java Method Calls
               ▼
┌─────────────────────────────────────┐
│  Project Neon Java Library (JAR)    │
│  (NeonClient, NeonHost, Protocol)   │
└─────────────────────────────────────┘
```

## Quick Start

### 1. Build the Native Library

See [BUILD.md](BUILD.md) for detailed build instructions.

```bash
cd ProjectNeon
mvn clean compile
cd src/main/native
mkdir build && cd build
cmake ..
cmake --build . --config Release
```

### 2. Include Header in Your Code

```c
#include "project_neon.h"
```

### 3. Basic Client Example

```c
NeonClientHandle* client = neon_client_new("PlayerName");

if (!neon_client_connect(client, 12345, "127.0.0.1:7777")) {
    fprintf(stderr, "Failed to connect: %s\n", neon_get_last_error());
    return;
}

while (game_running) {
    neon_client_process_packets(client);

}

neon_client_free(client);
```

### 4. Basic Host Example

```c
NeonHostHandle* host = neon_host_new(12345, "127.0.0.1:7777");

while (server_running) {
    neon_host_process_packets(host);

}

neon_host_free(host);
```

## Unreal Engine Integration

### Setting Up

1. Create a new C++ project or use an existing one
2. Copy `project_neon.h` to `Source/YourProject/ThirdParty/ProjectNeon/Include/`
3. Copy the built library to `Source/YourProject/ThirdParty/ProjectNeon/Lib/`
4. Update your `YourProject.Build.cs`:

```csharp
using UnrealBuildTool;
using System.IO;

public class YourProject : ModuleRules
{
    public YourProject(ReadOnlyTargetRules Target) : base(Target)
    {
        PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;

        PublicDependencyModuleNames.AddRange(new string[] {
            "Core", "CoreUObject", "Engine", "InputCore"
        });

        string ProjectNeonPath = Path.Combine(ModuleDirectory, "ThirdParty", "ProjectNeon");
        string IncludePath = Path.Combine(ProjectNeonPath, "Include");
        string LibPath = Path.Combine(ProjectNeonPath, "Lib");

        PublicIncludePaths.Add(IncludePath);

        if (Target.Platform == UnrealTargetPlatform.Win64)
        {
            PublicAdditionalLibraries.Add(Path.Combine(LibPath, "neon_jni.lib"));
            RuntimeDependencies.Add(Path.Combine(LibPath, "neon_jni.dll"));
        }
        else if (Target.Platform == UnrealTargetPlatform.Linux)
        {
            PublicAdditionalLibraries.Add(Path.Combine(LibPath, "libneon_jni.so"));
        }
        else if (Target.Platform == UnrealTargetPlatform.Mac)
        {
            PublicAdditionalLibraries.Add(Path.Combine(LibPath, "libneon_jni.dylib"));
        }
    }
}
```

### Example Unreal Subsystem

```cpp
#pragma once

#include "CoreMinimal.h"
#include "Subsystems/GameInstanceSubsystem.h"
#include "project_neon.h"
#include "NeonNetworkSubsystem.generated.h"

UCLASS()
class YOURPROJECT_API UNeonNetworkSubsystem : public UGameInstanceSubsystem
{
    GENERATED_BODY()

public:
    virtual void Initialize(FSubsystemCollectionBase& Collection) override;
    virtual void Deinitialize() override;

    UFUNCTION(BlueprintCallable)
    bool ConnectToSession(const FString& PlayerName, int32 SessionId, const FString& RelayAddress);

    UFUNCTION(BlueprintCallable)
    void ProcessNetworkPackets();

    UFUNCTION(BlueprintCallable)
    bool IsConnected() const;

private:
    NeonClientHandle* ClientHandle = nullptr;

    static void OnPongReceived(uint64_t ResponseTimeMs, uint64_t OriginalTimestamp);
    static void OnSessionConfig(uint8_t Version, uint16_t TickRate, uint16_t MaxPacketSize);
};
```

```cpp
#include "NeonNetworkSubsystem.h"

void UNeonNetworkSubsystem::Initialize(FSubsystemCollectionBase& Collection)
{
    Super::Initialize(Collection);
    UE_LOG(LogTemp, Log, TEXT("NeonNetworkSubsystem initialized"));
}

void UNeonNetworkSubsystem::Deinitialize()
{
    if (ClientHandle)
    {
        neon_client_free(ClientHandle);
        ClientHandle = nullptr;
    }
    Super::Deinitialize();
}

bool UNeonNetworkSubsystem::ConnectToSession(const FString& PlayerName, int32 SessionId, const FString& RelayAddress)
{
    if (ClientHandle)
    {
        UE_LOG(LogTemp, Warning, TEXT("Already connected"));
        return false;
    }

    ClientHandle = neon_client_new(TCHAR_TO_UTF8(*PlayerName));
    if (!ClientHandle)
    {
        UE_LOG(LogTemp, Error, TEXT("Failed to create client: %s"),
               UTF8_TO_TCHAR(neon_get_last_error()));
        return false;
    }

    neon_client_set_pong_callback(ClientHandle, &UNeonNetworkSubsystem::OnPongReceived);
    neon_client_set_session_config_callback(ClientHandle, &UNeonNetworkSubsystem::OnSessionConfig);

    if (!neon_client_connect(ClientHandle, SessionId, TCHAR_TO_UTF8(*RelayAddress)))
    {
        UE_LOG(LogTemp, Error, TEXT("Failed to connect: %s"),
               UTF8_TO_TCHAR(neon_get_last_error()));
        neon_client_free(ClientHandle);
        ClientHandle = nullptr;
        return false;
    }

    return true;
}

void UNeonNetworkSubsystem::ProcessNetworkPackets()
{
    if (ClientHandle)
    {
        neon_client_process_packets(ClientHandle);
    }
}

bool UNeonNetworkSubsystem::IsConnected() const
{
    return ClientHandle && neon_client_is_connected(ClientHandle);
}

void UNeonNetworkSubsystem::OnPongReceived(uint64_t ResponseTimeMs, uint64_t OriginalTimestamp)
{
    UE_LOG(LogTemp, Log, TEXT("Pong received: %llu ms"), ResponseTimeMs);
}

void UNeonNetworkSubsystem::OnSessionConfig(uint8_t Version, uint16_t TickRate, uint16_t MaxPacketSize)
{
    UE_LOG(LogTemp, Log, TEXT("Session config: Version=%u, TickRate=%u, MaxPacketSize=%u"),
           Version, TickRate, MaxPacketSize);
}
```

### Calling from Blueprint

1. Add to your GameInstance Blueprint's Event Graph
2. Call "Connect To Session" node with parameters
3. Add "Process Network Packets" to your Tick event

## Unity Integration

### Using P/Invoke (C# Wrapper)

Create a C# wrapper script:

```csharp
using System;
using System.Runtime.InteropServices;
using UnityEngine;

public class ProjectNeonClient : MonoBehaviour
{
    #region Native Imports

    [DllImport("neon_jni")]
    private static extern IntPtr neon_client_new(string name);

    [DllImport("neon_jni")]
    private static extern bool neon_client_connect(IntPtr client, uint sessionId, string relayAddr);

    [DllImport("neon_jni")]
    private static extern int neon_client_process_packets(IntPtr client);

    [DllImport("neon_jni")]
    private static extern bool neon_client_is_connected(IntPtr client);

    [DllImport("neon_jni")]
    private static extern byte neon_client_get_id(IntPtr client);

    [DllImport("neon_jni")]
    private static extern void neon_client_free(IntPtr client);

    [DllImport("neon_jni")]
    private static extern IntPtr neon_get_last_error();

    #endregion

    private IntPtr clientHandle = IntPtr.Zero;

    [Header("Connection Settings")]
    public string playerName = "Player";
    public uint sessionId = 12345;
    public string relayAddress = "127.0.0.1:7777";

    void Start()
    {
        ConnectToSession();
    }

    void Update()
    {
        if (clientHandle != IntPtr.Zero)
        {
            neon_client_process_packets(clientHandle);
        }
    }

    void OnDestroy()
    {
        if (clientHandle != IntPtr.Zero)
        {
            neon_client_free(clientHandle);
            clientHandle = IntPtr.Zero;
        }
    }

    public void ConnectToSession()
    {
        clientHandle = neon_client_new(playerName);
        if (clientHandle == IntPtr.Zero)
        {
            Debug.LogError("Failed to create client");
            return;
        }

        if (!neon_client_connect(clientHandle, sessionId, relayAddress))
        {
            string error = Marshal.PtrToStringUTF8(neon_get_last_error());
            Debug.LogError($"Failed to connect: {error}");
            neon_client_free(clientHandle);
            clientHandle = IntPtr.Zero;
            return;
        }

        Debug.Log("Connected to session!");
    }

    public bool IsConnected()
    {
        return clientHandle != IntPtr.Zero && neon_client_is_connected(clientHandle);
    }
}
```

### Library Placement for Unity

Place the native libraries in:
- **Windows**: `Assets/Plugins/x86_64/neon_jni.dll`
- **Linux**: `Assets/Plugins/x86_64/libneon_jni.so`
- **macOS**: `Assets/Plugins/neon_jni.bundle`

## Threading Considerations

### Game Loop Integration

The recommended pattern is to call `process_packets()` once per frame in your game loop:

```c
void game_update(float deltaTime) {
    neon_client_process_packets(client);

}
```

### Dedicated Network Thread (Advanced)

For games with high network traffic, consider a dedicated thread:

```c
#include <pthread.h>

void* network_thread(void* arg) {
    NeonClientHandle* client = (NeonClientHandle*)arg;

    while (should_run_network) {
        neon_client_process_packets(client);
        usleep(16000);
    }

    return NULL;
}

pthread_t thread;
pthread_create(&thread, NULL, network_thread, client);
```

**Important:** Ensure your callback functions are thread-safe if using a dedicated network thread.

## Callback Best Practices

### Thread Safety

Callbacks are invoked on the thread that calls `process_packets()`. If you're using a dedicated network thread, ensure your callbacks synchronize with the game thread:

```c
typedef struct {
    uint64_t responseTime;
    uint64_t timestamp;
    bool hasNewData;
} PongData;

PongData g_pongData = {0};
pthread_mutex_t g_pongMutex = PTHREAD_MUTEX_INITIALIZER;

void on_pong(uint64_t responseTime, uint64_t timestamp) {
    pthread_mutex_lock(&g_pongMutex);
    g_pongData.responseTime = responseTime;
    g_pongData.timestamp = timestamp;
    g_pongData.hasNewData = true;
    pthread_mutex_unlock(&g_pongMutex);
}

void game_update() {
    pthread_mutex_lock(&g_pongMutex);
    if (g_pongData.hasNewData) {
        update_latency_display(g_pongData.responseTime);
        g_pongData.hasNewData = false;
    }
    pthread_mutex_unlock(&g_pongMutex);
}
```

### Avoiding Blocking Operations

Never perform long-running operations in callbacks:

```c
void on_client_connect(uint8_t client_id, const char* name, uint32_t session_id) {
    enqueue_event(EVENT_CLIENT_CONNECTED, client_id, name, session_id);
}
```

## Error Handling

Always check return values and use `neon_get_last_error()`:

```c
if (!neon_client_connect(client, sessionId, relayAddr)) {
    const char* error = neon_get_last_error();
    log_error("Connection failed: %s", error ? error : "Unknown error");
    return false;
}
```

## Memory Management

### Client/Host Lifecycle

Always free handles when done:

```c
NeonClientHandle* client = neon_client_new("Player");

client->sessionId = neon_client_get_session_id(client);

neon_client_free(client);
```

### String Ownership

Strings returned by callbacks are temporary. Copy them if needed:

```c
void on_client_connect(uint8_t client_id, const char* name, uint32_t session_id) {
    char nameCopy[256];
    strncpy(nameCopy, name, sizeof(nameCopy) - 1);
    nameCopy[sizeof(nameCopy) - 1] = '\0';

}
```

## Performance Tips

1. **Batch Processing**: Call `process_packets()` once per frame, not multiple times
2. **Avoid Polling**: Use callbacks instead of repeatedly checking state
3. **Connection Pooling**: Reuse connections instead of creating new ones
4. **Buffer Sizes**: Default sizes are optimized for most use cases
5. **Profile**: Use your engine's profiler to identify bottlenecks

## Platform-Specific Notes

### Windows
- Ensure Visual C++ Redistributables are installed on target machines
- Place DLL in same directory as executable or add to PATH

### Linux
- Set `LD_LIBRARY_PATH` or install library to `/usr/lib/`
- Consider using `rpath` for distribution

### macOS
- Sign the library for distribution: `codesign -s "Developer ID" libneon_jni.dylib`
- Set `DYLD_LIBRARY_PATH` or use `@rpath`

## Troubleshooting

### Library Loading Issues

**Problem:** `UnsatisfiedLinkError` or library not found

**Solutions:**
1. Verify library is in the correct location
2. Check architecture matches (32-bit vs 64-bit)
3. Ensure all dependencies are present
4. Set appropriate environment variables

### JVM Not Found

**Problem:** JVM initialization fails

**Solutions:**
1. Ensure JRE/JDK is installed on target machine
2. Set `JAVA_HOME` environment variable
3. Add JVM library path to system library path

### Callback Not Firing

**Problem:** Callbacks registered but never called

**Solutions:**
1. Ensure `process_packets()` is being called regularly
2. Verify callbacks are set before connecting
3. Check that events are actually occurring (use debug logs)

## Advanced Topics

### Custom Packet Types

See the main Project Neon documentation for implementing custom game packet types. The JNI layer supports all packet types defined in the Java API.

### Session Resumption

Use reconnection tokens for handling disconnects gracefully. See `docs/ReconnectionGuide.md` in the main project.

### Load Balancing

Deploy multiple relay servers and use DNS or a load balancer to distribute clients.

## Support and Resources

- **GitHub**: https://github.com/Quiet-Terminal-Interactive/ProjectNeon
- **Issues**: https://github.com/Quiet-Terminal-Interactive/ProjectNeon/issues
- **Documentation**: See `docs/` in the main repository
- **Examples**: See `src/main/native/examples/` for working code

## License

Project Neon JNI layer is released under the same license as the main project. See LICENSE file in the repository root.
