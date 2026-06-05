# Contributing

Each implementation has its own contribution guide covering language-specific requirements, build tooling, and code style. This file covers the general guidelines that apply across all implementations.

## Pull Requests

- Keep PRs focused: one logical change per PR
- All tests must pass
- Update [CHANGELOG.md](CHANGELOG.md) under `[Unreleased]`
- Document all new public API surface in whatever format is idiomatic for that implementation

## Reporting Bugs

Open an issue at [github.com/Quiet-Terminal-Interactive/QTINeon/issues](https://github.com/Quiet-Terminal-Interactive/QTINeon/issues).

Include:
- The implementation and version you are using
- A minimal reproducible test or description of the packet trace
- The config you are using (or "defaults")

## Adding a Packet Type

Any new packet type must be added to all implementations to maintain interoperability. The wire format is defined in [PROTOCOL.md](PROTOCOL.md) — that is the source of truth.

<details>
<summary>Java</summary>

1. Add a constant to `PacketType` with its byte value
2. Add a `record` implementing `PacketPayload` with `toBytes()` and `fromBytes(byte[])`
3. Register the deserializer in `PayloadDeserializer` / `NeonPacket.fromBytes()`
4. Handle it in `NeonRelay.handlePacket()`, `NeonHost.handlePacket()`, or `NeonClient.handlePacket()` as appropriate
5. Add tests

</details>

## Requirements

<details>
<summary>Java</summary>

- Java 25 (OpenJDK 25.0.2+)
- Maven 3.9+

</details>

## Build

<details>
<summary>Java</summary>

```bash
mvn verify
```

This compiles, runs all tests, generates Javadoc, and enforces code coverage.

</details>

## Tests

<details>
<summary>Java</summary>

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

| Package       | What it tests                                   |
| ------------- | ----------------------------------------------- |
| `core`        | Protocol parsing, config, buffer pool           |
| `relay`       | NeonRelay with raw socket counterparts          |
| `host`        | NeonHost with a mock relay                      |
| `client`      | NeonClient with a mock relay                    |
| `reliability` | ReliablePacketManager in isolation              |
| `integration` | Full stack: relay + host + client over loopback |

</details>

## Code Style

<details>
<summary>Java</summary>

- Java 25 — use records, sealed interfaces, pattern matching, and virtual threads where natural
- No `System.out.println` in `src/main/`
- No comments that describe *what* the code does — only *why*, when non-obvious
- No speculative abstractions — solve the problem in front of you
- Package-private for implementation classes; `public` only for the API surface
- One `Logger` per class via `Logger.getLogger(Foo.class.getName())`

</details>