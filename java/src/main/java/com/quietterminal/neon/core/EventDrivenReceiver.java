package com.quietterminal.neon.core;

import java.io.IOException;
import java.nio.channels.Selector;
import java.util.function.Consumer;

final class EventDrivenReceiver {

    private final NeonSocket socket;
    private final Consumer<NeonSocket.ReceivedNeonPacket> handler;
    private volatile boolean running = false;
    private Thread thread;

    public EventDrivenReceiver(NeonSocket socket, Consumer<NeonSocket.ReceivedNeonPacket> handler) {
        this.socket = socket;
        this.handler = handler;
    }

    public void start() throws IOException {
        running = true;
        Selector selector = Selector.open();
        socket.registerWithSelector(selector);
        thread = Thread.ofVirtual().start(() -> {
            try {
                while (running && !socket.isClosed()) {
                    int ready = selector.select(100);
                    if (ready > 0) {
                        selector.selectedKeys().clear();
                        NeonSocket.ReceivedNeonPacket packet = socket.receivePacket();
                        if (packet != null) {
                            handler.accept(packet);
                        }
                    }
                }
            } catch (IOException e) {
                // socket closed or selector woken during stop
            } finally {
                try {
                    selector.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
