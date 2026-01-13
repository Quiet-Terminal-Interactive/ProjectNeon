# Project Neon - Roadmap to 1.0

**Current Version:** 0.2.0
**Target Version:** 1.0.0
**Status:** Beta - Not production ready

---

## Overview

Project Neon has a solid foundation with excellent architecture, but requires critical security, testing, and reliability improvements before 1.0 release. This roadmap outlines required work organized by priority.

---

## Critical Blockers (Must Complete for 1.0)

### 1. Security Hardening

#### ~~1.1 Input Validation (HIGH PRIORITY)~~ COMPLETE
- [x] Add security constants to core protocol:
  ```java
  public static final int MAX_NAME_LENGTH = 64;
  public static final int MAX_DESCRIPTION_LENGTH = 256;
  public static final int MAX_PACKET_COUNT = 100;
  public static final int MAX_PAYLOAD_SIZE = 65507; // Max UDP payload
  ```
- [x] Fix buffer overflow in `PacketPayload.deserializeConnectRequest()`:
  - Location: `src/main/java/com/quietterminal/projectneon/core/PacketPayload.java:46-48`
  - Add: `if (nameLen > MAX_NAME_LENGTH) throw new IllegalArgumentException(...)`
- [x] Fix buffer overflow in `PacketPayload.deserializePacketTypeRegistry()`:
  - Location: `src/main/java/com/quietterminal/projectneon/core/PacketPayload.java:159-164`
  - Validate count and string lengths
- [x] Fix buffer overflow in `PacketPayload.deserializeAck()`:
  - Location: `src/main/java/com/quietterminal/projectneon/core/PacketPayload.java:233`
  - Validate packet count
- [x] Add ByteBuffer bounds checking before all `.get()` operations
- [x] Validate session IDs are positive integers
- [x] Sanitize client names (remove control characters, enforce UTF-8)

#### ~~1.2 Denial of Service Protection~~ COMPLETE
- [x] Implement per-client rate limiting in relay (max packets/second)
- [x] Add maximum connections per session limit
- [x] Add maximum total connections to relay
- [x] Implement packet flood detection and throttling
- [x] Add memory usage limits for packet queues

#### ~~1.3 Security Documentation~~ COMPLETE
- [x] Add "Security Considerations" section to README.md
- [x] Document lack of encryption (plaintext UDP)
- [x] Document lack of authentication (open sessions)
- [x] Provide recommendations for deploying securely
- [x] Add security.md with threat model and mitigations
- [x] Document that game developers must implement auth at app layer

---

### 2. Comprehensive Test Suite

#### ~~2.1 Unit Tests (REQUIRED)~~ COMPLETE
- [x] Add JUnit 5 dependency to `pom.xml`
- [x] Create `src/test/java/com/quietterminal/projectneon/core/` test package
- [x] **PacketHeader Tests:**
  - [x] Serialization round-trip
  - [x] Magic number validation
  - [x] Version compatibility
  - [x] Invalid header handling
- [x] **PacketPayload Tests:**
  - [x] All packet type serialization/deserialization
  - [x] Buffer overflow scenarios
  - [x] Malformed packet handling
  - [x] Empty/null payload handling
- [x] **PacketType Tests:**
  - [x] Enum value mapping
  - [x] Invalid byte values
  - [x] Core vs game packet detection
- [x] **NeonSocket Tests:**
  - [x] Connection lifecycle
  - [x] Timeout handling
  - [x] Invalid address handling
  - [x] Concurrent send/receive

#### ~~2.2 Integration Tests~~ COMPLETE
- [x] Multi-client connection test (10+ clients)
- [x] Packet loss simulation test
- [x] Network partition test (relay restart)
- [x] Large payload test (approach MTU limits)
- [x] Concurrent session test (multiple sessions on one relay)

#### ~~2.3 Reliability Tests~~ COMPLETE
- [x] ACK/retry mechanism test
- [x] Timeout and cleanup test
- [x] Ping/pong heartbeat test
- [x] Client disconnect detection test

#### ~~2.4 Security Tests~~ COMPLETE
- [x] Buffer overflow attack test
- [x] Packet flood DoS test
- [x] Malformed packet fuzzing test
- [x] Session hijacking attempt test

#### ~~2.5 Test Infrastructure~~ COMPLETE
- [x] Configure Maven Surefire plugin for test execution
- [x] Set up code coverage reporting (JaCoCo)
- [x] Target minimum 80% code coverage
- [x] Add CI/CD pipeline (GitHub Actions) to run tests automatically

---

### ~~3. Error Handling Improvements~~ COMPLETE

- [x] Replace `System.err.println()` with proper logging framework
  - Implemented: `java.util.logging` (built-in, no dependencies)
  - Updated: `NeonSocket.java`, `NeonRelay.java`, `NeonHost.java`, `NeonClient.java`
  - Updated: All Main classes and JNI wrappers
- [x] Add consistent exception handling strategy:
  - Document which methods throw checked exceptions
  - Wrap or declare IOException appropriately
- [x] Add detailed error messages with context:
  - Include packet type, client ID, session ID in errors
  - Structured logging with contextual parameters
- [x] Handle `BufferUnderflowException` in packet parsing
  - Added specific catch blocks in `NeonSocket.receivePacket()`
  - Graceful degradation with warning logs
- [x] Add graceful degradation for non-critical errors
  - Non-critical errors log warnings and continue processing
  - Critical errors log severe messages with full context
- [x] Create custom exception types:
  - `PacketValidationException`
  - `ConnectionTimeoutException`
  - `SessionNotFoundException`
  - `NeonException` (base class)

---

### 4. Reliability & Connection Management

#### ~~4.1 Disconnect Handling~~ COMPLETE
- [x] Implement `DISCONNECT_NOTICE` packet sending:
  - Send when `NeonClient.close()` is called
  - Send when `NeonHost.close()` is called
- [x] Process `DISCONNECT_NOTICE` on receive:
  - Immediately remove client from relay
  - Trigger disconnect callback
  - Clean up resources
- [x] Add graceful shutdown timeout (wait for pending ACKs)

#### ~~4.2 Reconnection Support~~ COMPLETE
- [x] Add reconnection logic to `NeonClient`:
  - Exponential backoff strategy (1s → 2s → 4s → 8s → 16s → 30s max)
  - Configurable max reconnection attempts (default: 5)
  - State preservation during reconnect
- [x] Add session resumption tokens:
  - Generate unique token on connect (SecureRandom)
  - Allow client to rejoin with same client ID
  - Configurable session timeout (default: 5 minutes)
- [x] Relay forwards reconnect requests to host
- [x] Host validates tokens and timeout before accepting reconnection

#### ~~4.3 Reliability Layer (Optional for Game Packets)~~ COMPLETE
- [x] Design optional reliability layer for game packets:
  - Opt-in via `ReliablePacketManager` utility class
  - Uses existing sequence numbers for ordering
  - ACK mechanism similar to SessionConfig
- [x] Implemented as optional utility class (not in protocol core)
- [x] Document when to use reliable vs unreliable packets
  - Created comprehensive `docs/ReliabilityGuide.md`
  - Covers performance tradeoffs and best practices

---

### ~~5. JNI Implementation~~ COMPLETE
- [x] Implement `src/main/native/neon_jni.c`:
  - JVM initialization and lifecycle (JNI_OnLoad/JNI_OnUnload)
  - Handle creation/destruction functions for client and host
  - Callback marshalling (C function pointers stored in handles)
  - Error handling and reporting (thread-local error buffers)
  - Thread safety for callbacks (automatic JVM thread attachment)
- [x] Build native library for all platforms:
  - Created `CMakeLists.txt` for cross-platform builds
  - Linux: `libneon_jni.so`
  - macOS: `libneon_jni.dylib`
  - Windows: `neon_jni.dll`
- [x] Test JNI layer with example C programs:
  - Created `examples/client_example.c` - Full client integration demo
  - Created `examples/host_example.c` - Full host integration demo
  - Both examples demonstrate callbacks, packet processing, lifecycle
- [x] Update build documentation:
  - Created `BUILD.md` - Comprehensive build instructions
  - Created `INTEGRATION.md` - Game engine integration guide (Unreal, Unity)
  - Created `examples/README.md` - Example usage documentation
- [x] Add JNI test to CI pipeline:
  - Added `jni-build` job for multi-platform builds (Windows, Linux, macOS)
  - Added `jni-examples` job to build and verify example programs
  - Automatic artifact uploads for all platforms
  - Integrated with existing GitHub Actions workflow

---

## Important Improvements (Should Have for 1.0)

### ~~6. Configuration API~~ COMPLETE

- [x] Create `NeonConfig` class for configurable parameters:
  - All timing, size, and limit parameters are now configurable
  - Comprehensive validation with detailed error messages
  - Fluent API with method chaining for easy configuration
- [x] Accept `NeonConfig` in constructors for client/host/relay:
  - `NeonSocket(port, config)` - configurable buffer size
  - `NeonRelay(bindAddress, config)` - all relay parameters
  - `NeonHost(sessionId, relayAddress, config)` - all host parameters
  - `NeonClient(name, config)` - all client parameters
  - `ReliablePacketManager(socket, relayAddr, clientId, config)` - reliable packet settings
- [x] Replace hardcoded constants throughout codebase:
  - All timeouts, intervals, and limits now use config values
  - Socket timeouts, processing loop sleep intervals
  - Rate limiting and flood detection parameters
  - ACK retry and reconnection parameters
- [x] Document configuration options in README:
  - Complete configuration guide with examples
  - Table of all parameters with defaults and descriptions
  - Tuning recommendations for different scenarios
- [x] Add validation for config values:
  - Comprehensive validation in `NeonConfig.validate()` method
  - Validation automatically called when config is applied
  - Clear error messages indicating valid ranges

---

### 7. API Stability & Polish

- [ ] Review all public APIs for consistency
- [ ] Ensure all public methods have JavaDoc
- [ ] Add `@since 1.0` tags to new APIs
- [ ] Mark internal classes as package-private
- [ ] Create `@PublicAPI` annotation for guaranteed stability
- [ ] Review method naming consistency
- [ ] Add builder pattern for complex configurations
- [ ] Ensure all callbacks document thread safety

---

### 8. Documentation Updates

#### 8.1 API Documentation
- [ ] Generate JavaDoc HTML with `mvn javadoc:javadoc`
- [ ] Publish JavaDoc to GitHub Pages or docs site
- [ ] Add JavaDoc link to README

#### 8.2 README Improvements
- [ ] Add "Security Considerations" section
- [ ] Document UDP unreliability clearly
- [ ] Add troubleshooting section
- [ ] Add FAQ section
- [ ] Update version numbers to 1.0.0
- [ ] Add badges (build status, version, license)

#### 8.3 Developer Guides
- [ ] Create `CONTRIBUTING.md`:
  - Code style guidelines
  - How to run tests
  - How to submit issues/PRs
- [ ] Create `ARCHITECTURE.md`:
  - Design decisions
  - Packet flow diagrams
  - State machine diagrams
- [ ] Add example projects directory:
  - Simple chat application
  - Basic game example
  - Custom packet type example

---

### 9. Performance Optimization

- [ ] Add buffer pooling for packet processing:
  - Reduce garbage collection pressure
  - Reuse byte arrays
- [ ] Profile relay under high load (1000+ packets/sec)
- [ ] Optimize hot paths identified by profiling
- [ ] Consider using virtual threads (Java 21+) for clients
- [ ] Make receive buffer size configurable
- [ ] Add batch ACK processing (reduce packet count)
- [ ] Measure and document performance characteristics:
  - Latency (p50, p95, p99)
  - Throughput (packets/second)
  - Memory usage per connection

---

### 9.5. Some stuff I missed before that I got bullied into adding

- [ ] Full-size receive buffers + enforcement
- [ ] Honest relay semantics
- [ ] Unsealed payload architecture
- [ ] Centralized ACK state machine
- [ ] Event-driven receive loop (no sleep polling)
- [ ] Game packet registry + subtype/version/validation
- [ ] Core metrics
- [ ] Runtime configuration
- [ ] Payload size enforcement
- [ ] Explicit version mismatch handling
- [ ] Clean start/stop lifecycle
- [ ] Transport interface
- [ ] Structured logging
- [ ] Better backpressure signals

---

### 10. Packaging & Distribution

- [ ] Publish to Maven Central:
  - Set up Sonatype OSSRH account
  - Add GPG signing to `pom.xml`
  - Configure Maven deployment
- [ ] Create GitHub Release for 1.0:
  - Tag `v1.0.0`
  - Attach standalone JARs
  - Write release notes
- [ ] Add LICENSE file (choose license: MIT, Apache 2.0, etc.)
- [ ] Add proper Maven metadata:
  - Developers section
  - SCM information
  - Issue tracker URL

---
## Future Features (Post-1.0)

### Session Persistence
- [ ] Design session state serialization format
- [ ] Save relay state to disk periodically
- [ ] Restore sessions on relay restart
- [ ] Configurable persistence backend

### Authentication & Authorization
- [ ] Design authentication framework
- [ ] Add optional password/token to ConnectRequest
- [ ] Host validation callback for auth
- [ ] Session access control lists

### Encryption Support
- [ ] Research DTLS integration
- [ ] Add TLS over UDP option
- [ ] Make encryption opt-in per session
- [ ] Document performance impact

### Metrics & Monitoring
- [ ] Expose Prometheus metrics endpoint
- [ ] Track packet counts, errors, latency
- [ ] Health check endpoint for relay
- [ ] Add structured logging (JSON)

### Advanced Features
- [ ] NAT traversal support (STUN/TURN)
- [ ] IPv6 support
- [ ] Compression option for large payloads
- [ ] WebSocket relay variant for browser clients

---

## How to Contribute

See individual issues for each task. Priority labels:
-  **Critical** - Blocks 1.0 release
-  **Important** - Should complete for 1.0
- **Enhancement** - Nice to have

---

## Version History

- **0.1.0** - Initial protocol design
- **0.2.0** - Java rewrite, full Maven support (current)
- **1.0.0** - Production-ready release (target)
- **1.1.0** - Advanced features (planned)

---

**Last Updated:** 06-01-2026
**Maintained by:** Kohan Mathers
