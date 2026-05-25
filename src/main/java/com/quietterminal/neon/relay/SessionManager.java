package com.quietterminal.neon.relay;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class SessionManager {

    private static final class Session {
        final ConcurrentHashMap<Byte, SocketAddress> peers = new ConcurrentHashMap<>();
        volatile long lastActivity = System.currentTimeMillis();
    }

    private final ConcurrentHashMap<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketAddress, Integer> peerSessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketAddress, Byte> peerClientIdMap = new ConcurrentHashMap<>();

    void registerHost(int sessionId, SocketAddress addr) {
        Session session = new Session();
        sessions.put(sessionId, session);
        session.peers.put((byte) 1, addr);
        peerSessionMap.put(addr, sessionId);
        peerClientIdMap.put(addr, (byte) 1);
    }

    void registerPeer(int sessionId, byte clientId, SocketAddress addr) {
        Session session = sessions.get(sessionId);
        if (session == null)
            return;
        session.peers.put(clientId, addr);
        peerSessionMap.put(addr, sessionId);
        peerClientIdMap.put(addr, clientId);
        session.lastActivity = System.currentTimeMillis();
    }

    void removePeer(SocketAddress addr) {
        Integer sessionId = peerSessionMap.remove(addr);
        if (sessionId == null)
            return;
        Byte clientId = peerClientIdMap.remove(addr);
        if (clientId == null)
            return;
        Session session = sessions.get(sessionId);
        if (session == null)
            return;
        session.peers.remove(clientId);
        if (clientId == (byte) 1) {
            sessions.remove(sessionId);
            session.peers.forEach((id, peerAddr) -> {
                peerSessionMap.remove(peerAddr);
                peerClientIdMap.remove(peerAddr);
            });
        }
    }

    SocketAddress getHost(int sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? null : session.peers.get((byte) 1);
    }

    SocketAddress getPeerAddress(int sessionId, byte clientId) {
        Session session = sessions.get(sessionId);
        return session == null ? null : session.peers.get(clientId);
    }

    List<SocketAddress> getAllPeersExcept(int sessionId, SocketAddress exclude) {
        Session session = sessions.get(sessionId);
        if (session == null)
            return List.of();
        List<SocketAddress> result = new ArrayList<>();
        session.peers.forEach((id, addr) -> {
            if (!addr.equals(exclude))
                result.add(addr);
        });
        return result;
    }

    Integer getSessionForPeer(SocketAddress addr) {
        return peerSessionMap.get(addr);
    }

    int getClientCount(int sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null)
            return 0;
        return Math.max(0, session.peers.size() - 1);
    }

    void updateLastSeen(SocketAddress addr) {
        Integer sessionId = peerSessionMap.get(addr);
        if (sessionId == null)
            return;
        Session session = sessions.get(sessionId);
        if (session != null)
            session.lastActivity = System.currentTimeMillis();
    }

    void updatePeerAddress(int sessionId, byte clientId, SocketAddress newAddr) {
        Session session = sessions.get(sessionId);
        if (session == null)
            return;
        SocketAddress oldAddr = session.peers.get(clientId);
        if (oldAddr != null) {
            peerSessionMap.remove(oldAddr);
            peerClientIdMap.remove(oldAddr);
        }
        session.peers.put(clientId, newAddr);
        peerSessionMap.put(newAddr, sessionId);
        peerClientIdMap.put(newAddr, clientId);
    }

    void removeStale(long thresholdMs) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastActivity > thresholdMs) {
                entry.getValue().peers.forEach((id, addr) -> {
                    peerSessionMap.remove(addr);
                    peerClientIdMap.remove(addr);
                });
                return true;
            }
            return false;
        });
    }
}
