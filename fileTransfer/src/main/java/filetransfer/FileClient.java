package filetransfer;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class FileClient {

    private static final byte[] MAGIC = new byte[]{'F', 'T', '0', '1'};
    private static final int MAX_NAME_BYTES = 4096;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java -jar file-transfer.jar client <host> <port> <file-path>");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        Path filePath = Paths.get(args[2]);

        if (!Files.isRegularFile(filePath)) {
            System.err.println("File not found or not a regular file: " + filePath);
            System.exit(1);
        }

        new FileClient().sendFile(host, port, filePath);
    }

    private void sendFile(String host, int port, Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length == 0 || nameBytes.length > MAX_NAME_BYTES) {
            throw new IOException("File name length in UTF-8 is invalid: " + nameBytes.length);
        }

        long size = Files.size(filePath);

        System.out.println("Connecting to " + host + ":" + port);
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             InputStream fileIn = new BufferedInputStream(Files.newInputStream(filePath))) {

            out.write(MAGIC);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            out.writeLong(size);

            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int n = fileIn.read(buffer, 0, toRead);
                if (n == -1) {
                    throw new EOFException("Unexpected EOF while reading file");
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
            out.flush();

            int status = in.read();
            if (status == -1) {
                System.err.println("Server closed connection without status");
                return;
            }
            if (status == 0) {
                System.out.println("File transfer succeeded.");
            } else if (status == 1) {
                System.out.println("File transfer failed: size mismatch or partial data.");
            } else {
                System.out.println("File transfer failed: server reported error.");
            }
        }
    }
}
