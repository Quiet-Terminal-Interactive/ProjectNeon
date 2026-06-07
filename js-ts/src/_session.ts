import { Address, addressKey } from "./_socket";

interface Session {
  peers: Map<number, Address>;
  lastActivity: number;
}

export class SessionManager {
  private sessions = new Map<number, Session>();
  private peerToSession = new Map<string, number>();
  private peerToClientId = new Map<string, number>();

  registerHost(sessionId: number, addr: Address): void {
    const session: Session = { peers: new Map([[1, addr]]), lastActivity: Date.now() };
    this.sessions.set(sessionId, session);
    const key = addressKey(addr);
    this.peerToSession.set(key, sessionId);
    this.peerToClientId.set(key, 1);
  }

  registerPeer(sessionId: number, clientId: number, addr: Address): void {
    const session = this.sessions.get(sessionId);
    if (!session) return;
    const key = addressKey(addr);
    session.peers.set(clientId, addr);
    this.peerToSession.set(key, sessionId);
    this.peerToClientId.set(key, clientId);
    session.lastActivity = Date.now();
  }

  removePeer(addr: Address): void {
    const key = addressKey(addr);
    const sessionId = this.peerToSession.get(key);
    if (sessionId === undefined) return;
    const clientId = this.peerToClientId.get(key);
    if (clientId === undefined) return;
    this.peerToSession.delete(key);
    this.peerToClientId.delete(key);
    const session = this.sessions.get(sessionId);
    if (!session) return;
    session.peers.delete(clientId);
    if (clientId === 1) {
      for (const peerAddr of session.peers.values()) {
        const k = addressKey(peerAddr);
        this.peerToSession.delete(k);
        this.peerToClientId.delete(k);
      }
      this.sessions.delete(sessionId);
    }
  }

  getHost(sessionId: number): Address | undefined {
    return this.sessions.get(sessionId)?.peers.get(1);
  }

  getPeerAddress(sessionId: number, clientId: number): Address | undefined {
    return this.sessions.get(sessionId)?.peers.get(clientId);
  }

  getAllPeersExcept(sessionId: number, exclude: Address): Address[] {
    const session = this.sessions.get(sessionId);
    if (!session) return [];
    const excKey = addressKey(exclude);
    const result: Address[] = [];
    for (const addr of session.peers.values()) {
      if (addressKey(addr) !== excKey) result.push(addr);
    }
    return result;
  }

  getSessionForPeer(addr: Address): number | undefined {
    return this.peerToSession.get(addressKey(addr));
  }

  getClientCount(sessionId: number): number {
    const session = this.sessions.get(sessionId);
    if (!session) return 0;
    return Math.max(0, session.peers.size - 1);
  }

  updateLastSeen(addr: Address): void {
    const sessionId = this.peerToSession.get(addressKey(addr));
    if (sessionId === undefined) return;
    const session = this.sessions.get(sessionId);
    if (session) session.lastActivity = Date.now();
  }

  updatePeerAddress(sessionId: number, clientId: number, newAddr: Address): void {
    const session = this.sessions.get(sessionId);
    if (!session) return;
    const oldAddr = session.peers.get(clientId);
    if (oldAddr !== undefined) {
      const oldKey = addressKey(oldAddr);
      this.peerToSession.delete(oldKey);
      this.peerToClientId.delete(oldKey);
    }
    const newKey = addressKey(newAddr);
    session.peers.set(clientId, newAddr);
    this.peerToSession.set(newKey, sessionId);
    this.peerToClientId.set(newKey, clientId);
  }

  removeStale(thresholdMs: number): void {
    const now = Date.now();
    const stale: number[] = [];
    for (const [sessionId, session] of this.sessions) {
      if (now - session.lastActivity > thresholdMs) stale.push(sessionId);
    }
    for (const sessionId of stale) {
      const session = this.sessions.get(sessionId)!;
      for (const addr of session.peers.values()) {
        const k = addressKey(addr);
        this.peerToSession.delete(k);
        this.peerToClientId.delete(k);
      }
      this.sessions.delete(sessionId);
    }
  }
}
