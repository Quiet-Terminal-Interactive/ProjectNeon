# Contributing to Project Neon

Thank you for your interest in contributing to Project Neon! This document provides guidelines and instructions for contributing to the project.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Setup](#development-setup)
- [Code Style Guidelines](#code-style-guidelines)
- [Testing Guidelines](#testing-guidelines)
- [Commit Message Guidelines](#commit-message-guidelines)
- [Pull Request Process](#pull-request-process)
- [Project Structure](#project-structure)
- [Release Process](#release-process)

---

## Code of Conduct

### Our Pledge

We are committed to providing a welcoming and inclusive environment for all contributors, regardless of:
- Experience level
- Background
- Identity
- Personal characteristics

### Expected Behavior

- Be respectful and constructive in all interactions
- Accept feedback gracefully and provide it thoughtfully
- Focus on what is best for the project and community
- Show empathy towards other community members

### Unacceptable Behavior

- Harassment, discrimination, or offensive comments
- Personal attacks or trolling
- Publishing others' private information without permission
- Any conduct that would be unprofessional in a workplace setting

### Enforcement

Violations can be reported to: kohanmathersmcgonnell@gmail.com

---

## How Can I Contribute?

### Reporting Bugs

**Before submitting a bug report:**
1. Check the [existing issues](https://github.com/QuietTerminal/ProjectNeon/issues) to avoid duplicates
2. Verify the issue exists in the latest version (v0.2.0+)
3. Test with default configuration to isolate the problem
4. Enable detailed logging (Level.FINE) to capture diagnostic information

**Good bug reports include:**
- Clear, descriptive title
- Steps to reproduce (minimal code example if possible)
- Expected behavior vs. actual behavior
- Environment details (OS, Java version, network setup)
- Relevant log output or error messages
- Screenshots if applicable

**Template:**
```markdown
**Description:**
Brief summary of the bug

**Steps to Reproduce:**
1. Start relay with...
2. Connect client with...
3. Send packet...

**Expected Behavior:**
What should happen

**Actual Behavior:**
What actually happens

**Environment:**
- OS: Ubuntu 22.04 / Windows 11 / macOS 14
- Java Version: 21.0.1
- Project Neon Version: 0.2.0
- Network: LAN / Internet / localhost

**Logs:**
```
[paste relevant log output]
```

**Additional Context:**
Any other relevant information
```

### Suggesting Enhancements

**Enhancement suggestions should include:**
- Clear use case or problem being solved
- Proposed solution or API design
- Alternatives considered
- Backward compatibility implications
- Performance impact (if applicable)

**Label as:** `enhancement`

### Contributing Code

We welcome pull requests for:
- Bug fixes
- Performance improvements
- Documentation improvements
- New examples
- Test coverage improvements
- Refactoring for clarity or maintainability

**Not currently accepting:**
- Major protocol changes (breaking changes) - discuss first in an issue
- Features that add external dependencies (keep core dependency-free)
- Platform-specific code without cross-platform fallbacks

### Contributing Documentation

Documentation improvements are always welcome:
- Fixing typos or unclear wording
- Adding examples or use cases
- Expanding troubleshooting guides
- Improving API documentation (JavaDoc)
- Creating tutorials or guides

---

## Development Setup

### Prerequisites

- **Java 21+** - OpenJDK or Oracle JDK
- **Maven 3.6+** - Build tool
- **Git** - Version control
- **CMake 3.15+** (optional, for JNI development)
- **GCC/Clang/MSVC** (optional, for JNI development)

### Clone and Build

```bash
# Clone repository
git clone https://github.com/QuietTerminal/ProjectNeon.git
cd ProjectNeon

# Build project
mvn clean package

# Run tests
mvn test

# Generate coverage report
mvn clean test jacoco:report
# View: target/site/jacoco/index.html

# Generate JavaDoc
mvn javadoc:javadoc
# View: target/site/apidocs/index.html
```

### IDE Setup

**IntelliJ IDEA:**
```bash
# Import as Maven project
File → Open → Select ProjectNeon directory
# IDEA auto-detects pom.xml

# Enable annotation processing
Settings → Build → Compiler → Annotation Processors → Enable
```

**Eclipse:**
```bash
File → Import → Maven → Existing Maven Projects
Select ProjectNeon directory
```

**VS Code:**
```bash
# Install extensions:
# - Language Support for Java (Red Hat)
# - Debugger for Java (Microsoft)
# - Maven for Java (Microsoft)

# Open folder
code ProjectNeon
```

### Running Components for Development

```bash
# Terminal 1: Relay
mvn exec:java@relay

# Terminal 2: Host
mvn exec:java@host

# Terminal 3: Client
mvn exec:java@client
```

### JNI Development Setup

```bash
# Generate JNI headers
mvn compile  # Automatically generates headers in src/main/native/

# Build native library
cd src/main/native
mkdir build && cd build

# Linux/macOS
cmake ..
make

# Windows (Visual Studio)
cmake .. -G "Visual Studio 17 2022"
cmake --build . --config Release

# Install library (Linux)
sudo cp libneon_jni.so /usr/local/lib/
sudo ldconfig

# Test C examples
cd ../examples
gcc -o client_example client_example.c -L../build -lneon_jni -I.. -lpthread
./client_example
```

---

## Code Style Guidelines

### Java Code Style

Project Neon follows **standard Java conventions** with some specific preferences:

#### General Principles

- **Readability over cleverness**: Clear code is better than compact code
- **Simplicity over abstraction**: Avoid premature abstractions
- **Immutability by default**: Use `final` for variables and fields when possible
- **Fail fast**: Validate inputs early and throw clear exceptions

#### Formatting

```java
// Indentation: 4 spaces (no tabs)
public class Example {
    private final String field;  // Field declarations

    // Constructor
    public Example(String field) {
        this.field = field;  // Always use 'this.' for field access
    }

    // Methods have blank line before them
    public void method() {
        if (condition) {  // Opening brace on same line
            doSomething();
        } else {
            doSomethingElse();
        }
    }
}

// Line length: 120 characters maximum (prefer 100)
// Blank lines: One between methods, two between classes
```

#### Naming Conventions

```java
// Classes: PascalCase
public class NeonClient { }

// Interfaces: PascalCase (no 'I' prefix)
public interface PacketPayload { }

// Methods: camelCase (verb phrases)
public void sendPacket() { }
public boolean isConnected() { }
public String getName() { }

// Variables: camelCase
int clientId;
String playerName;
List<Integer> sessionIds;

// Constants: UPPER_SNAKE_CASE
public static final int MAX_PACKET_SIZE = 65507;
public static final String DEFAULT_ADDRESS = "127.0.0.1:7777";

// Packages: lowercase, no underscores
package com.quietterminal.projectneon.core;

// Acronyms: Treat as words (JSON → Json, UDP → Udp)
class JsonParser { }  // Not JSONParser
String udpAddress;    // Not UDPAddress
```

#### JavaDoc Requirements

All public APIs **must** have JavaDoc:

```java
/**
 * Brief one-line description of the class.
 * <p>
 * More detailed description if needed. Explain purpose, usage patterns,
 * and any important constraints or assumptions.
 * <p>
 * Example usage:
 * <pre>{@code
 * NeonClient client = new NeonClient("PlayerName");
 * client.connect(12345, "127.0.0.1:7777");
 * }</pre>
 *
 * @since 0.2.0
 * @see RelatedClass
 */
@PublicAPI
public class NeonClient implements AutoCloseable {

    /**
     * Brief description of what this method does.
     * <p>
     * Additional details about behavior, side effects, or constraints.
     *
     * @param sessionId the session ID to connect to (must be positive)
     * @param relayAddress the relay address in "host:port" format
     * @return true if connection succeeded, false otherwise
     * @throws IllegalArgumentException if sessionId is negative
     * @throws NeonException if connection fails due to network error
     * @since 0.2.0
     */
    public boolean connect(int sessionId, String relayAddress) {
        // Implementation
    }
}
```

#### Records and Sealed Types

```java
// Records: Use for immutable data carriers
public record ConnectRequest(
    byte clientVersion,
    String desiredName,
    int targetSessionId,
    int gameIdentifier
) implements PacketPayload { }

// Sealed interfaces: Explicitly list permitted implementations
public sealed interface PacketPayload
    permits ConnectRequest, ConnectAccept, ConnectDeny, /* ... */ { }
```

#### Error Handling

```java
// Validate inputs early
public void setName(String name) {
    if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Name cannot be null or blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
        throw new IllegalArgumentException(
            "Name exceeds maximum length of " + MAX_NAME_LENGTH
        );
    }
    this.name = name;
}

// Use specific exception types
throw new PacketValidationException("Invalid packet header: magic number mismatch");

// Log with context
logger.warning("Failed to send packet to client " + clientId +
               " in session " + sessionId + ": " + e.getMessage());

// Avoid catching Exception (catch specific types)
try {
    parsePacket(buffer);
} catch (BufferUnderflowException e) {
    logger.warning("Malformed packet: insufficient data");
} catch (PacketValidationException e) {
    logger.warning("Invalid packet: " + e.getMessage());
}
```

#### Resource Management

```java
// Use try-with-resources for AutoCloseable
try (NeonClient client = new NeonClient("PlayerName")) {
    client.connect(12345, "127.0.0.1:7777");
    // client.close() called automatically
}

// Close resources in reverse order of creation
try {
    socket = new DatagramSocket();
    // ... use socket ...
} finally {
    if (socket != null && !socket.isClosed()) {
        socket.close();
    }
}
```

### C Code Style (JNI)

For native code in `src/main/native/`:

```c
// Indentation: 4 spaces
// Braces: K&R style (opening brace on same line)
// Line length: 100 characters

// Function names: snake_case
jboolean neon_client_connect(NeonClientHandle* handle,
                               jint session_id,
                               const char* relay_address) {
    // Check parameters
    if (handle == NULL || relay_address == NULL) {
        return JNI_FALSE;
    }

    // Implementation
    return JNI_TRUE;
}

// Always check JNI return values
if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    return NULL;
}
```

---

## Testing Guidelines

### Test Coverage Requirements

- **Minimum coverage**: 80% line coverage, 75% branch coverage
- All new code must include tests
- Regression tests required for bug fixes

### Test Organization

```
src/test/java/com/quietterminal/projectneon/
├── core/              # Unit tests for protocol core
│   ├── PacketHeaderTest.java
│   ├── PacketPayloadTest.java
│   └── ...
├── integration/       # Multi-component tests
│   └── IntegrationTest.java
├── reliability/       # Connection reliability tests
│   ├── ReconnectionTest.java
│   └── ...
└── security/          # Security and validation tests
    └── SecurityTest.java
```

### Writing Unit Tests

```java
@Test
@DisplayName("ConnectRequest serialization round-trip should preserve all fields")
void testConnectRequestSerialization() {
    // Arrange
    PacketPayload.ConnectRequest original = new PacketPayload.ConnectRequest(
        (byte) 1,
        "TestPlayer",
        12345,
        0xABCDEF
    );

    // Act
    byte[] serialized = PacketPayload.serializeConnectRequest(original);
    PacketPayload.ConnectRequest deserialized =
        PacketPayload.deserializeConnectRequest(ByteBuffer.wrap(serialized));

    // Assert
    assertEquals(original.clientVersion(), deserialized.clientVersion());
    assertEquals(original.desiredName(), deserialized.desiredName());
    assertEquals(original.targetSessionId(), deserialized.targetSessionId());
    assertEquals(original.gameIdentifier(), deserialized.gameIdentifier());
}

@Test
@DisplayName("ConnectRequest should reject names exceeding maximum length")
void testConnectRequestNameValidation() {
    // Arrange
    String tooLongName = "a".repeat(MAX_NAME_LENGTH + 1);
    byte[] data = createMalformedConnectRequest(tooLongName);

    // Act & Assert
    assertThrows(PacketValidationException.class, () -> {
        PacketPayload.deserializeConnectRequest(ByteBuffer.wrap(data));
    });
}
```

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=PacketHeaderTest

# Run specific test method
mvn test -Dtest=PacketHeaderTest#testSerialization

# Run tests with coverage
mvn clean test jacoco:report

# Run only integration tests
mvn test -Dgroups=integration

# Run tests with detailed output
mvn test -X
```

### Test Naming Conventions

```java
// Test class: ClassName + Test
public class NeonClientTest { }

// Test method: test + MethodName + Scenario
@Test
void testConnectWithValidParametersSucceeds() { }

@Test
void testConnectWithNullAddressThrowsException() { }

@Test
void testProcessPacketsHandlesBufferUnderflow() { }

// Or use @DisplayName for readability
@Test
@DisplayName("Client should reconnect with exponential backoff after disconnect")
void reconnectionBackoff() { }
```

---

## Commit Message Guidelines

### Format

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types

- **feat**: New feature
- **fix**: Bug fix
- **docs**: Documentation changes
- **style**: Formatting, missing semicolons, etc. (no code change)
- **refactor**: Code refactoring without functionality change
- **test**: Adding or updating tests
- **chore**: Build process, dependencies, tools

### Examples

```
feat(client): add automatic reconnection with exponential backoff

Implements reconnection logic in NeonClient with configurable max attempts
and backoff strategy. Clients now automatically attempt to reconnect when
connection is lost, using exponential backoff (1s, 2s, 4s, 8s, 16s, 30s max).

Closes #42
```

```
fix(relay): prevent memory leak from stale client connections

Fixed issue where disconnected clients were not removed from the session map,
causing memory to grow unbounded. Added periodic cleanup task that removes
clients inactive for more than relayClientTimeoutMs (default 15s).

Fixes #73
```

```
docs(readme): add troubleshooting section for connection issues

Added comprehensive troubleshooting guide covering common connection,
packet, and performance issues with solutions.
```

### Rules

- Use present tense: "add feature" not "added feature"
- Use imperative mood: "fix bug" not "fixes bug"
- Limit subject line to 72 characters
- Capitalize subject line
- No period at end of subject line
- Wrap body at 72 characters
- Explain *what* and *why*, not *how* (code shows how)
- Reference issues: `Closes #123`, `Fixes #456`

---

## Pull Request Process

### Before Submitting

1. **Fork** the repository
2. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b fix/issue-123-description
   ```

3. **Make your changes** following code style guidelines

4. **Add tests** for new functionality

5. **Ensure all tests pass**:
   ```bash
   mvn clean test
   ```

6. **Check code coverage** (should not decrease):
   ```bash
   mvn jacoco:report
   # Open target/site/jacoco/index.html
   ```

7. **Update documentation** if needed:
   - JavaDoc for new public APIs
   - README.md for new features
   - ARCHITECTURE.md for design changes

8. **Commit your changes** with clear messages

9. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

### Creating the Pull Request

**Title Format:**
```
[Type] Brief description of changes
```

Example: `[Feature] Add support for session persistence`

**Description Template:**
```markdown
## Description
Brief summary of what this PR does and why.

## Changes
- Added X to...
- Modified Y to...
- Removed Z because...

## Testing
- [ ] All existing tests pass
- [ ] Added tests for new functionality
- [ ] Tested manually (describe scenario)
- [ ] Code coverage maintained or improved

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Commented hard-to-understand areas
- [ ] Documentation updated
- [ ] No new warnings

## Related Issues
Closes #123
Related to #456
```

### Review Process

1. **Automated Checks**: CI runs tests and checks coverage
2. **Code Review**: Maintainers review code quality, design, and tests
3. **Feedback**: Address review comments and update PR
4. **Approval**: Maintainer approves PR
5. **Merge**: Maintainer merges using squash or merge commit

### PR Guidelines

- **Keep PRs focused**: One feature or fix per PR
- **Small is beautiful**: Prefer multiple small PRs over one large PR
- **Link issues**: Reference related issues in description
- **Respond promptly**: Reply to review comments within 7 days
- **Be patient**: Reviews may take several days
- **Be respectful**: Maintainers volunteer their time

### After Your PR is Merged

- **Delete your branch**:
  ```bash
  git branch -d feature/your-feature-name
  git push origin --delete feature/your-feature-name
  ```

- **Update your fork**:
  ```bash
  git checkout main
  git pull upstream main
  git push origin main
  ```

---

## Project Structure

```
ProjectNeon/
├── src/
│   ├── main/
│   │   ├── java/com/quietterminal/projectneon/
│   │   │   ├── core/          # Protocol core (packets, socket, config)
│   │   │   ├── client/        # NeonClient implementation
│   │   │   ├── host/          # NeonHost implementation
│   │   │   ├── relay/         # NeonRelay server
│   │   │   ├── exceptions/    # Custom exception types
│   │   │   ├── jni/           # JNI bridge classes
│   │   │   └── util/          # Logging and utilities
│   │   └── native/
│   │       ├── neon_jni.c     # JNI implementation
│   │       ├── project_neon.h # C API header
│   │       ├── CMakeLists.txt # Build configuration
│   │       └── examples/      # C example programs
│   └── test/
│       └── java/com/quietterminal/projectneon/
│           ├── core/          # Unit tests
│           ├── integration/   # Integration tests
│           ├── reliability/   # Reliability tests
│           └── security/      # Security tests
├── docs/                      # GitHub Pages (JavaDoc)
├── pom.xml                    # Maven configuration
├── README.md                  # Project documentation
├── ROADMAP.md                 # Development roadmap
├── SECURITY.md                # Security documentation
├── CONTRIBUTING.md            # This file
└── ARCHITECTURE.md            # Design documentation
```

### Key Modules

- **core**: Protocol implementation (packets, headers, payloads, socket)
- **client**: Client-side connection management and packet processing
- **host**: Host-side session management and client tracking
- **relay**: Central server for packet routing and session management
- **jni**: Native bridge for C/C++ integration

---

## Release Process

(Maintainers only)

### Version Numbers

Project Neon follows **Semantic Versioning** (semver):
- `MAJOR.MINOR.PATCH` (e.g., 1.2.3)
- **MAJOR**: Breaking API changes
- **MINOR**: New features, backward compatible
- **PATCH**: Bug fixes, backward compatible

### Release Checklist

1. **Update version** in:
   - `pom.xml` (`<version>`)
   - `README.md` (version badge and header)
   - `ROADMAP.md` (version history)

2. **Update documentation**:
   - Finalize CHANGELOG.md entry
   - Update JavaDoc @since tags
   - Review and update README

3. **Run full test suite**:
   ```bash
   mvn clean test jacoco:report
   ```

4. **Build release artifacts**:
   ```bash
   mvn clean package
   ```

5. **Create Git tag**:
   ```bash
   git tag -a v1.0.0 -m "Release version 1.0.0"
   git push origin v1.0.0
   ```

6. **Create GitHub Release**:
   - Draft new release from tag
   - Copy CHANGELOG entry
   - Attach JARs: `neon-relay.jar`, `neon-host.jar`, `neon-client.jar`
   - Attach native libraries: `libneon_jni.so`, `neon_jni.dll`, `libneon_jni.dylib`

7. **Publish to Maven Central** (when ready):
   ```bash
   mvn clean deploy -P release
   ```

8. **Announce release**:
   - GitHub Discussions
   - Project website
   - Social media

---

## Questions?

- **GitHub Issues**: https://github.com/QuietTerminal/ProjectNeon/issues
- **GitHub Discussions**: https://github.com/QuietTerminal/ProjectNeon/discussions
- **Email**: kohanmathersmcgonnell@gmail.com

Thank you for contributing to Project Neon!
