package com.quietterminal.neon.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public interface Transport {

    void send(byte[] data, SocketAddress address) throws IOException;

    ReceivedData receive() throws IOException;

    InetSocketAddress getLocalAddress() throws IOException;

    boolean isClosed();

    record ReceivedData(byte[] data, SocketAddress source) {}
}
