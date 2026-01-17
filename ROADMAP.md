# Project Neon - Roadmap

- [x] Full-size receive buffers + enforcement
- [x] Honest relay semantics
- [x] Unsealed payload architecture
- [x] Centralized ACK state machine
- [x] Event-driven receive loop (no sleep polling)
- [x] Game packet registry + subtype/version/validation
- [x] Core metrics
- [x] Runtime configuration
- [x] Payload size enforcement
- [x] Explicit version mismatch handling
- [x] Clean start/stop lifecycle
- [x] Transport interface
- [x] Structured logging
- [x] Better backpressure signals
- [x] Design session state serialization format
- [ ] Save relay state to disk periodically
- [ ] Restore sessions on relay restart
- [ ] Configurable persistence backend
- [ ] Design authentication framework
- [ ] Add optional password/token to ConnectRequest
- [ ] Host validation callback for auth
- [ ] Session access control lists
- [ ] Research DTLS integration
- [ ] Add TLS over UDP option
- [ ] Make encryption opt-in per session
- [ ] Document performance impact
- [ ] Expose Prometheus metrics endpoint
- [ ] Track packet counts, errors, latency
- [ ] Health check endpoint for relay
- [ ] Add structured logging (JSON)
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
- **0.2.0** - Java rewrite, full Maven support
- **1.0.0** - Production-ready release (current)
- **1.1.0** - Advanced features (target)

---

**Last Updated:** 17-01-2026
**Maintained by:** Kohan Mathers
