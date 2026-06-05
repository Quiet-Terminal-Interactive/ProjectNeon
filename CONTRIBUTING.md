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

<details>
<summary>Python</summary>

1. Add a member to `PacketType` in `_protocol.py` with its byte value
2. Add a `@dataclass(frozen=True)` with `to_bytes()` and `from_bytes(data)` class methods
3. Add a `case` branch to `NeonPacket.from_bytes()` for the new type
4. Handle it in `NeonRelay._handle_packet()`, `NeonHost._handle_packet()`, or `NeonClient._handle_packet()` as appropriate
5. Add tests

</details>

## Requirements

<details>
<summary>Java</summary>

- Java 25 (OpenJDK 25.0.2+)
- Maven 3.9+

</details>

<details>
<summary>Python</summary>

- Python 3.11+
- pip / a virtual environment

Optional for DTLS:
- `pyopenssl>=23.0` (`pip install qti-neon[dtls]`)

</details>

## Build

<details>
<summary>Java</summary>

```bash
mvn verify
```

This compiles, runs all tests, generates Javadoc, and enforces code coverage.

</details>

<details>
<summary>Python</summary>

```bash
cd python
pip install -e ".[dev]"
```

Generate docs:

```bash
pdoc src/qti_neon --output-dir ../docs/python
# output: ../docs/python/qti_neon.html
```

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

<details>
<summary>Python</summary>

Most tests open real UDP sockets on loopback. Run them in a terminal — not in a sandboxed IDE runner:

```bash
cd python
pytest
```

Run a specific test file:

```bash
pytest tests/test_host.py
```

Run only the integration tests:

```bash
pytest tests/test_integration.py
```

Tests are split by concern:

| File                  | What it tests                                   |
| --------------------- | ----------------------------------------------- |
| `test_protocol.py`    | Packet parsing, serialisation, config           |
| `test_config.py`      | NeonConfig validation                           |
| `test_relay.py`       | NeonRelay with raw socket counterparts          |
| `test_host.py`        | NeonHost with a mock relay                      |
| `test_client.py`      | NeonClient with a mock relay                    |
| `test_reliable.py`    | ReliablePacketManager in isolation              |
| `test_integration.py` | Full stack: relay + host + client over loopback |

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

<details>
<summary>Python</summary>

- Python 3.11+ — use `match`/`case`, `dataclass(frozen=True)`, and `|` union types where natural
- No `print()` in `src/`
- No comments that describe *what* the code does — only *why*, when non-obvious
- No speculative abstractions — solve the problem in front of you
- Prefix internal classes and functions with `_`; public API only in `__init__.py`
- One `logger = logging.getLogger(__name__)` per module

</details>