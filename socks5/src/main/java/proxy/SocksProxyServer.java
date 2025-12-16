package proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class SocksProxyServer {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java -jar <гредл кладет fat jar в build/libs/> <port>");
            System.exit(1);
        }
        int port = Integer.parseInt(args[0]);
        new SocksProxyServer().run(port);
    }

    private void run(int port) throws IOException {
        Selector selector = Selector.open();

        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        DatagramChannel dnsChannel = DatagramChannel.open();
        dnsChannel.configureBlocking(false);
        DnsResolver dnsResolver = new DnsResolver(dnsChannel);
        dnsChannel.register(selector, SelectionKey.OP_READ, dnsResolver);

        System.out.println("SOCKS5 proxy listening on port " + port);

        while (true) {
            selector.select();
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> it = keys.iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) {
                    continue;
                }
                try {
                    if (key.isAcceptable()) {
                        handleAccept(key, selector, dnsResolver);
                    } else if (key.channel() instanceof DatagramChannel) {
                        if (key.isReadable()) {
                            ((DnsResolver) key.attachment()).handleRead();
                        }
                    } else {
                        ConnectionAttachment att = (ConnectionAttachment) key.attachment();
                        if (att == null) {
                            continue;
                        }
                        if (key.isConnectable()) {
                            att.session.onConnect(att.endpoint);
                        }
                        if (key.isReadable()) {
                            att.session.onRead(att.endpoint);
                        }
                        if (key.isWritable()) {
                            att.session.onWrite(att.endpoint);
                        }
                    }
                } catch (CancelledKeyException ignored) {
                    // ignore
                } catch (IOException e) {
                    Object att = key.attachment();
                    if (att instanceof ConnectionAttachment ca) {
                        ca.session.close();
                    } else {
                        key.cancel();
                        key.channel().close();
                    }
                }
            }
        }
    }

    private void handleAccept(SelectionKey key, Selector selector, DnsResolver dnsResolver) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client == null) {
            return;
        }
        client.configureBlocking(false);
        ClientSession session = new ClientSession(client, dnsResolver);
        SelectionKey clientKey = client.register(
                selector,
                SelectionKey.OP_READ | SelectionKey.OP_WRITE,
                new ConnectionAttachment(session, Endpoint.CLIENT)
        );
        session.setClientKey(clientKey);
    }
}
