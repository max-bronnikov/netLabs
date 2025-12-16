package proxy;

final class ConnectionAttachment {
    final ClientSession session;
    final Endpoint endpoint;

    ConnectionAttachment(ClientSession session, Endpoint endpoint) {
        this.session = session;
        this.endpoint = endpoint;
    }
}

