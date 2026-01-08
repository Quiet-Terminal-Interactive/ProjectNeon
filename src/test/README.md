# Project Neon Test Suite

This directory contains the comprehensive test suite for Project Neon, covering unit tests, integration tests, reliability tests, and security tests.

## Test Structure

```
src/test/java/
├── com/quietterminal/projectneon/
│   ├── core/              # Unit tests for core protocol components
│   ├── integration/       # Integration tests (multi-component)
│   ├── reliability/       # Reliability and ACK/retry tests
│   └── security/          # Security and vulnerability tests
└── NeonTest.java          # Basic smoke test
```

## Running Tests

### Run all tests
```bash
mvn test
```

### Run specific test categories
```bash
# Unit tests only
mvn test -Dtest=*Test -DexcludeGroups=integration,reliability,security

# Integration tests
mvn test -Dtest=IntegrationTest

# Reliability tests
mvn test -Dtest=ReliabilityTest

# Security tests
mvn test -Dtest=SecurityTest
```

### Generate coverage report
```bash
mvn jacoco:report
```

Coverage report will be available at: `target/site/jacoco/index.html`

### Check coverage requirements
```bash
mvn jacoco:check
```

Current coverage targets:
- Line coverage: 80%
- Branch coverage: 75%

## Test Categories

### 1. Unit Tests (`core/`)
Tests individual components in isolation:
- **PacketHeaderTest**: Header serialization, validation, byte order
- **PacketPayloadTest**: All payload types, buffer overflow protection
- **PacketTypeTest**: Enum mappings, validation
- **NeonSocketTest**: Socket operations, timeout handling

### 2. Integration Tests (`integration/`)
Tests multi-component interactions:
- Multi-client connections (10+ clients)
- Large payload handling (approaching MTU limits)
- Concurrent sessions on single relay
- Network partition/relay restart scenarios
- Packet loss simulation

### 3. Reliability Tests (`reliability/`)
Tests ACK mechanisms, timeouts, and heartbeats:
- ACK/retry mechanism validation
- Timeout and cleanup handling
- Ping/pong heartbeat functionality
- Client disconnect detection
- Auto-ping with configurable intervals
- Connection state management

### 4. Security Tests (`security/`)
Tests security hardening and DoS protection:
- Buffer overflow attack prevention
- Packet flood DoS protection
- Malformed packet fuzzing
- Session ID validation
- Duplicate client name handling
- Maximum connections enforcement
- Control character sanitization
- Rate limiting validation

## Test Infrastructure

### Maven Surefire Plugin
- Configured for parallel test execution (4 threads)
- Test fork reuse enabled for performance
- Reports generated in `target/surefire-reports/`

### JaCoCo Coverage Plugin
- Line and branch coverage tracking
- Automatic report generation after tests
- Coverage enforcement with configurable thresholds
- HTML reports with detailed metrics

### CI/CD Pipeline
GitHub Actions workflow (`.github/workflows/ci.yml`):
- Runs on every push and pull request
- Executes full test suite
- Generates and uploads coverage reports
- Archives test results and artifacts
- Builds standalone JARs

## Writing New Tests

### Test Naming Convention
- Unit tests: `[ClassName]Test.java`
- Integration tests: Place in `integration/` package
- Use descriptive `@DisplayName` annotations
- Use `@Order` for test execution ordering when necessary

### Test Structure
```java
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MyTest {

    @BeforeEach
    void setUp() {
        // Initialize test fixtures
    }

    @AfterEach
    void tearDown() {
        // Clean up resources
    }

    @Test
    @Order(1)
    @DisplayName("Should handle expected scenario correctly")
    void testExpectedScenario() {
        // Arrange
        // Act
        // Assert
    }
}
```

### Best Practices
1. **Use descriptive test names**: Test methods should clearly describe what they test
2. **One assertion per test**: Focus each test on a single behavior
3. **Clean up resources**: Always close sockets, clients, hosts in `finally` blocks or use try-with-resources
4. **Avoid hardcoded delays**: Use proper synchronization (CountDownLatch, CompletableFuture) when possible
5. **Test edge cases**: Empty inputs, maximum values, null/invalid data
6. **Document assumptions**: Use comments to explain test setup and expectations

## Troubleshooting

### Port conflicts
Integration tests use specific ports (17777, 18888, 19999). If tests fail with "Address already in use":
```bash
# Linux/Mac: Check port usage
lsof -i :17777

# Windows: Check port usage
netstat -ano | findstr :17777

# Kill the process or use different ports
```

### Flaky tests
Some integration tests may occasionally fail due to timing. If a test fails:
1. Run it again to verify it's consistently failing
2. Check if delays need adjustment for slower systems
3. Verify no other processes are using test ports

### Coverage not meeting threshold
If coverage check fails:
```bash
# View detailed coverage report
mvn jacoco:report
# Open target/site/jacoco/index.html in browser
```

Add tests for uncovered code paths until threshold is met.

## Contributing

When adding new features:
1. Write unit tests first (TDD approach recommended)
2. Add integration tests for multi-component features
3. Update security tests if feature has security implications
4. Ensure all tests pass: `mvn clean test`
5. Verify coverage: `mvn jacoco:report` (aim for 80%+ line coverage)
6. Run specific test suites relevant to your changes

## Coverage Reports

After running tests, view coverage at:
- **HTML Report**: `target/site/jacoco/index.html`
- **XML Report**: `target/site/jacoco/jacoco.xml` (for CI tools)
- **CSV Report**: `target/site/jacoco/jacoco.csv`

## Performance Testing

For performance testing:
1. Use `@Disabled` to exclude from regular test runs
2. Mark with custom tag: `@Tag("performance")`
3. Run separately: `mvn test -Dgroups=performance`

## Future Test Additions

Planned test coverage improvements:
- Stress tests (1000+ clients)
- Long-running stability tests
- Memory leak detection tests
- Cross-platform compatibility tests
- IPv6 support tests (when implemented)
