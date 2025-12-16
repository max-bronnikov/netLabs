package filetransfer;

import java.util.Arrays;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }
        String mode = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (mode) {
            case "server" -> FileServer.main(rest);
            case "client" -> FileClient.main(rest);
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  Server: java -jar <jar> server <port>");
        System.err.println("  Client: java -jar <jar> client <host> <port> <file-path>");
    }
}
