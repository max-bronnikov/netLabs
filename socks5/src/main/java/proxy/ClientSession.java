package proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

class ClientSession {

    private enum State {
        GREETING,
        REQUEST,
        WAIT_DNS,
        CONNECTING,
        RELAY,
        CLOSED
    }

    private static final int RELAY_BUFFER_SIZE = 64 * 1024;

    private final SocketChannel clientChannel;
    private SelectionKey clientKey;

    private SocketChannel remoteChannel;
    private SelectionKey remoteKey;

    private final DnsResolver dnsResolver;

    private State state = State.GREETING;

    private final ByteBuffer handshakeBuf = ByteBuffer.allocate(1024);

    private final ByteBuffer clientToRemote = ByteBuffer.allocate(RELAY_BUFFER_SIZE);
    private final ByteBuffer remoteToClient = ByteBuffer.allocate(RELAY_BUFFER_SIZE);

    private String pendingHost;
    private int pendingPort;
    private InetSocketAddress targetAddress;

    private boolean clientInputClosed = false;
    private boolean remoteInputClosed = false;

    ClientSession(SocketChannel clientChannel, DnsResolver dnsResolver) {
        this.clientChannel = clientChannel;
        this.dnsResolver = dnsResolver;
        clientToRemote.clear();
        remoteToClient.clear();
    }

    void setClientKey(SelectionKey key) {
        this.clientKey = key;
    }

    void onRead(Endpoint endpoint) {
        try {
            if (state == State.CLOSED) {
                return;
            }
            if (endpoint == Endpoint.CLIENT) {
                if (state == State.GREETING || state == State.REQUEST) {
                    readFromClientHandshake();
                } else if (state == State.RELAY) {
                    relayRead(clientChannel, clientToRemote, remoteKey);
                }
            } else {
                if (state == State.CONNECTING || state == State.WAIT_DNS) {
                    return;
                }
                if (state == State.RELAY) {
                    relayRead(remoteChannel, remoteToClient, clientKey);
                }
            }
        } catch (IOException e) {
            close();
        }
    }

    void onWrite(Endpoint endpoint) {
        try {
            if (state == State.CLOSED) {
                return;
            }
            if (endpoint == Endpoint.CLIENT) {
                relayWrite(clientChannel, remoteToClient, clientKey);
            } else {
                relayWrite(remoteChannel, clientToRemote, remoteKey);
            }
        } catch (IOException e) {
            close();
        }
    }

    void onConnect(Endpoint endpoint) {
        if (endpoint != Endpoint.REMOTE || remoteChannel == null || state != State.CONNECTING) {
            return;
        }
        try {
            if (remoteChannel.finishConnect()) {
                sendConnectReply((byte) 0x00);
                state = State.RELAY;
                enableRelay();
            } else {
                sendConnectReply((byte) 0x05);
                close();
            }
        } catch (IOException e) {
            sendConnectReply((byte) 0x05);
            close();
        }
    }

    void onDnsResolved(InetAddress addr) {
        if (state != State.WAIT_DNS) {
            return;
        }
        targetAddress = new InetSocketAddress(addr, pendingPort);
        try {
            startConnectToTarget();
        } catch (IOException e) {
            sendConnectReply((byte) 0x05);
            close();
        }
    }

    void onDnsFailed() {
        if (state == State.WAIT_DNS) {
            sendConnectReply((byte) 0x04);
            close();
        }
    }

    private void readFromClientHandshake() throws IOException {
        int read = clientChannel.read(handshakeBuf);
        if (read == -1) {
            close();
            return;
        }
        if (read == 0) {
            return;
        }
        handshakeBuf.flip();
        while (true) {
            if (state == State.GREETING) {
                if (!parseGreeting()) {
                    break;
                }
            }
            if (state == State.REQUEST) {
                if (!parseRequest()) {
                    break;
                }
            } else {
                break;
            }
        }
        handshakeBuf.compact();
    }

    private boolean parseGreeting() throws IOException {
        if (handshakeBuf.remaining() < 2) {
            return false;
        }
        byte ver = handshakeBuf.get();
        byte nMethods = handshakeBuf.get();
        if (ver != 0x05) {
            close();
            return false;
        }
        if (handshakeBuf.remaining() < nMethods) {
            handshakeBuf.position(handshakeBuf.position() - 2);
            return false;
        }
        boolean noAuth = false;
        for (int i = 0; i < nMethods; i++) {
            byte m = handshakeBuf.get();
            if (m == 0x00) {
                noAuth = true;
            }
        }
        byte[] resp = new byte[] {0x05, noAuth ? (byte) 0x00 : (byte) 0xFF};
        remoteToClient.put(resp);
        clientKey.interestOps(clientKey.interestOps() | SelectionKey.OP_WRITE);
        if (!noAuth) {
            state = State.CLOSED;
            return false;
        }
        state = State.REQUEST;
        return true;
    }

    private boolean parseRequest() throws IOException {
        if (handshakeBuf.remaining() < 4) {
            return false;
        }
        handshakeBuf.mark();
        byte ver = handshakeBuf.get();
        byte cmd = handshakeBuf.get();
        byte rsv = handshakeBuf.get();
        byte atyp = handshakeBuf.get();
        if (ver != 0x05 || rsv != 0x00) {
            close();
            return false;
        }
        if (cmd != 0x01) {
            sendConnectReply((byte) 0x07);
            close();
            return false;
        }
        String host = null;
        InetAddress ip = null;
        int port;

        if (atyp == 0x01) {
            if (handshakeBuf.remaining() < 4 + 2) {
                handshakeBuf.reset();
                return false;
            }
            byte[] addrBytes = new byte[4];
            handshakeBuf.get(addrBytes);
            ip = InetAddress.getByAddress(addrBytes);
            port = ((handshakeBuf.get() & 0xFF) << 8) | (handshakeBuf.get() & 0xFF);
            targetAddress = new InetSocketAddress(ip, port);
            startConnectToTarget();
            return true;
        } else if (atyp == 0x03) {
            if (handshakeBuf.remaining() < 1) {
                handshakeBuf.reset();
                return false;
            }
            int len = handshakeBuf.get() & 0xFF;
            if (handshakeBuf.remaining() < len + 2) {
                handshakeBuf.reset();
                return false;
            }
            byte[] nameBytes = new byte[len];
            handshakeBuf.get(nameBytes);
            host = new String(nameBytes);
            port = ((handshakeBuf.get() & 0xFF) << 8) | (handshakeBuf.get() & 0xFF);
            pendingHost = host;
            pendingPort = port;
            state = State.WAIT_DNS;
            dnsResolver.resolve(host, this);
            return true;
        } else {
            sendConnectReply((byte) 0x08); // address type not supported
            close();
            return false;
        }
    }

    private void startConnectToTarget() throws IOException {
        remoteChannel = SocketChannel.open();
        remoteChannel.configureBlocking(false);
        remoteChannel.connect(targetAddress);
        remoteKey = remoteChannel.register(
                clientKey.selector(),
                SelectionKey.OP_CONNECT,
                new ConnectionAttachment(this, Endpoint.REMOTE)
        );
        state = State.CONNECTING;
    }

    private void sendConnectReply(byte rep) {
        if (state == State.CLOSED) {
            return;
        }
        byte[] resp = new byte[] {
                0x05, rep, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
        };
        if (remoteToClient.remaining() >= resp.length) {
            remoteToClient.put(resp);
            if (clientKey != null && clientKey.isValid()) {
                clientKey.interestOps(clientKey.interestOps() | SelectionKey.OP_WRITE);
            }
        } else {
            close();
        }
    }

    private void enableRelay() {
        if (clientKey != null && clientKey.isValid()) {
            clientKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
        if (remoteKey != null && remoteKey.isValid()) {
            remoteKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
    }

    private void relayRead(SocketChannel src, ByteBuffer outBuf, SelectionKey dstKey) throws IOException {
        if (!outBuf.hasRemaining()) {
            SelectionKey srcKey = (SelectionKey) src.keyFor(clientKey.selector());
            if (srcKey != null && srcKey.isValid()) {
                srcKey.interestOps(srcKey.interestOps() & ~SelectionKey.OP_READ);
            }
            return;
        }
        int n = src.read(outBuf);
        if (n == -1) {
            if (src == clientChannel) {
                clientInputClosed = true;
                if (remoteKey != null && remoteKey.isValid()) {
                    remoteKey.interestOps(remoteKey.interestOps() & ~SelectionKey.OP_READ);
                }
            } else {
                remoteInputClosed = true;
                if (clientKey != null && clientKey.isValid()) {
                    clientKey.interestOps(clientKey.interestOps() & ~SelectionKey.OP_READ);
                }
            }
            checkCloseAfterDrain();
            return;
        }
        if (n > 0 && dstKey != null && dstKey.isValid()) {
            dstKey.interestOps(dstKey.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    private void relayWrite(SocketChannel dst, ByteBuffer buf, SelectionKey key) throws IOException {
        buf.flip();
        int n = dst.write(buf);
        buf.compact();
        if (n == 0) {
            return;
        }
        if (!buf.hasRemaining()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            checkCloseAfterDrain();
        }
    }

    private void checkCloseAfterDrain() {
        if (clientInputClosed && remoteInputClosed &&
                !clientToRemote.hasRemaining() && !remoteToClient.hasRemaining()) {
            close();
        }
    }

    void close() {
        state = State.CLOSED;
        try {
            if (clientKey != null) {
                clientKey.cancel();
            }
        } catch (Exception ignored) {}
        try {
            if (remoteKey != null) {
                remoteKey.cancel();
            }
        } catch (Exception ignored) {}
        try {
            clientChannel.close();
        } catch (IOException ignored) {}
        if (remoteChannel != null) {
            try {
                remoteChannel.close();
            } catch (IOException ignored) {}
        }
    }
}
