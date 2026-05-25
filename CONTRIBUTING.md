# Contributing

## Requirements

- Java 25 (OpenJDK 25.0.2+)
- Maven 3.9+

## Build

```bash
mvn verify
```

This compiles, runs all tests, generates Javadoc, and enforces code coverage.

## Tests

Most tests open real UDP sockets on loopback. Run them in a terminal — not in a sandboxed IDE runner:

```bash
mvn test
```

Run a specific test class:

```bash
mvn test -Dtest="NeonHostTest"
```

Run only the integration tests:

```bash
mvn test -Dtest="*Integration*"
```

Tests are split by concern:

| Package | What it tests |
|---------|---------------|
| `core` | Protocol parsing, config, buffer pool |
| `relay` | NeonRelay with raw socket counterparts |
| `host` | NeonHost with a mock relay |
| `client` | NeonClient with a mock relay |
| `reliability` | ReliablePacketManager in isolation |
| `integration` | Full stack: relay + host + client over loopback |

## Code Style

- Java 25 — use records, sealed interfaces, pattern matching, and virtual threads where natural
- No `System.out.println` in `src/main/`
- No comments that describe *what* the code does — only *why*, when non-obvious
- No speculative abstractions — solve the problem in front of you
- Package-private for implementation classes; `public` only for the API surface
- One `Logger` per class via `Logger.getLogger(Foo.class.getName())`

## Adding a Packet Type

1. Add a constant to `PacketType` with its byte value
2. Add a `record` implementing `PacketPayload` with `toBytes()` and `fromBytes(byte[])`
3. Register the deserializer in `PayloadDeserializer` / `NeonPacket.fromBytes()`
4. Handle it in `NeonRelay.handlePacket()`, `NeonHost.handlePacket()`, or `NeonClient.handlePacket()` as appropriate
5. Add tests

## Pull Requests

- Keep PRs focused: one logical change per PR
- All tests must pass: `mvn verify`
- Update [CHANGELOG.md](CHANGELOG.md) under `[Unreleased]`
- Javadoc all new public methods

## Reporting Bugs

Open an issue at [github.com/Quiet-Terminal-Interactive/QTINeon/issues](https://github.com/Quiet-Terminal-Interactive/QTINeon/issues).

Include:
- Java version (`java -version`)
- A minimal reproducible test or description of the packet trace
- The `NeonConfig` you're using (or "defaults")
