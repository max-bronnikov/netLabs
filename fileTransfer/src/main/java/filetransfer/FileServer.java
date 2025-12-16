package filetransfer;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FileServer {

    private static final byte[] MAGIC = new byte[]{'F', 'T', '0', '1'};
    private static final int MAX_NAME_BYTES = 4096;
    private static final long MAX_FILE_SIZE = 1024L *1024*1024*1024;
    private static final long REPORT_INTERVAL_MS = 3000;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java -jar file-transfer.jar server <port>");
            System.exit(1);
        }
        int port = Integer.parseInt(args[0]);
        new FileServer().run(port);
    }

    private void run(int port) throws IOException {
        Path uploadsDir = Paths.get("uploads");
        Files.createDirectories(uploadsDir);
        Path uploadsReal = uploadsDir.toRealPath();

        AtomicInteger clientCounter = new AtomicInteger(1);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("File server listening on port " + port);
            System.out.println("Uploads directory: " + uploadsReal);

            while (true) {
                Socket socket = serverSocket.accept();
                int clientId = clientCounter.getAndIncrement();
                Thread t = new Thread(() ->
                        handleClient(socket, uploadsReal, clientId)
                );
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private void handleClient(Socket socket, Path uploadsReal, int clientId) {
        String clientInfo = "Client#" + clientId + " [" + socket.getRemoteSocketAddress() + "]";
        System.out.println(clientInfo + " connected");

        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            byte[] magicBuf = new byte[4];
            in.readFully(magicBuf);
            if (!matchesMagic(magicBuf)) {
                out.writeByte(2);
                out.flush();
                return;
            }

            int nameLen = in.readInt();
            if (nameLen <= 0 || nameLen > MAX_NAME_BYTES) {
                out.writeByte(2);
                out.flush();
                return;
            }

            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String originalName = new String(nameBytes, StandardCharsets.UTF_8);

            long fileSize = in.readLong();
            if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
                out.writeByte(2);
                out.flush();
                return;
            }

            String safeName = sanitizeFilename(originalName);
            Path target = resolveInUploads(uploadsReal, safeName);

            System.out.println(clientInfo + " sending \"" + originalName + "\" → " +
                    target.getFileName() + " (" + fileSize + " bytes)");

            long startTime = System.nanoTime();
            long lastReportTime = startTime;
            long lastReportedBytes = 0;
            long totalBytes = 0;
            boolean reported = false;

            byte[] buffer = new byte[64 * 1024];
            long remaining = fileSize;

            try (OutputStream fileOut =
                         new BufferedOutputStream(Files.newOutputStream(
                                 target,
                                 StandardOpenOption.CREATE_NEW,
                                 StandardOpenOption.WRITE
                         ))) {

                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int n = in.read(buffer, 0, toRead);
                    if (n == -1) {
                        out.writeByte(1);
                        out.flush();
                        return;
                    }

                    fileOut.write(buffer, 0, n);
                    totalBytes += n;
                    remaining -= n;

                    long now = System.nanoTime();
                    if (now - lastReportTime >= REPORT_INTERVAL_MS * 1_000_000L) {
                        reportSpeed(clientInfo, startTime, now,
                                lastReportTime, totalBytes, lastReportedBytes);
                        lastReportTime = now;
                        lastReportedBytes = totalBytes;
                        reported = true;
                    }
                }

                fileOut.flush();
            }

            long endTime = System.nanoTime();
            if (!reported) {
                reportSpeed(clientInfo, startTime, endTime,
                        lastReportTime, totalBytes, lastReportedBytes);
            }

            if (totalBytes == fileSize) {
                out.writeByte(0);
                System.out.println(clientInfo + " transfer completed successfully");
            } else {
                out.writeByte(1);
                System.err.println(clientInfo + " size mismatch");
            }
            out.flush();

        } catch (IOException e) {
            System.err.println(clientInfo + " error: " + e.getMessage());
        } finally {
            System.out.println(clientInfo + " disconnected");
        }
    }

    private static boolean matchesMagic(byte[] buf) {
        if (buf.length != MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (buf[i] != MAGIC[i]) return false;
        }
        return true;
    }

    private static String sanitizeFilename(String name) {
        String base = name.replace('\\', '/');
        int idx = base.lastIndexOf('/');
        if (idx >= 0) base = base.substring(idx + 1);

        base = base.replace("/", "_").replace("\u0000", "_");
        while (base.startsWith(".")) base = base.substring(1);
        if (base.isBlank()) base = "file";

        return base;
    }

    private static Path resolveInUploads(Path uploadsReal, String safeName) throws IOException {
        Path candidate = uploadsReal.resolve(safeName).normalize();
        if (!candidate.startsWith(uploadsReal)) {
            candidate = uploadsReal.resolve("file_" + System.currentTimeMillis()).normalize();
        }
        if (Files.exists(candidate)) {
            candidate = uploadsReal.resolve(
                    safeName + "_" + System.currentTimeMillis()
            ).normalize();
        }
        return candidate;
    }

    private static void reportSpeed(String clientInfo,
                                    long startTimeNs,
                                    long nowNs,
                                    long lastReportTimeNs,
                                    long totalBytes,
                                    long lastReportedBytes) {

        double totalSec = (nowNs - startTimeNs) / 1_000_000_000.0;
        double intervalSec = (nowNs - lastReportTimeNs) / 1_000_000_000.0;
        if (intervalSec <= 0) intervalSec = 1e-9;
        if (totalSec <= 0) totalSec = 1e-9;

        long intervalBytes = totalBytes - lastReportedBytes;

        double inst = intervalBytes / intervalSec;
        double avg = totalBytes / totalSec;

        System.out.printf(
                "%s speed: instant=%.2f B/s, avg=%.2f B/s%n",
                clientInfo, inst, avg
        );
    }
}
