import lpi.server.rmi.IServer;
import lpi.server.rmi.IServer.ArgumentException;
import lpi.server.rmi.IServer.FileInfo;
import lpi.server.rmi.IServer.LoginException;
import lpi.server.rmi.IServer.Message;
import lpi.server.rmi.IServer.ServerException;

import java.io.File;
import java.io.IOException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;

public class Main {

    private static final String HOST = "localhost"; // для локального server.rmi
    private static final int PORT = 152;
    private static final long POLL_PERIOD_MS = 3000;

    private static IServer proxy;
    private static String sessionId = null;
    private static Timer pollTimer = null;

    public static void main(String[] args) {
        try {
            Registry registry = LocateRegistry.getRegistry(HOST, PORT);
            proxy = (IServer) registry.lookup(IServer.RMI_SERVER_NAME);
            System.out.println("Connected to server");
        } catch (Exception e) {
            System.out.println("Connection error: " + e.getMessage());
            return;
        }

        System.out.println("Available commands:");
        System.out.println("ping");
        System.out.println("echo <text>");
        System.out.println("login <username> <password>");
        System.out.println("list");
        System.out.println("msg <user> <text>");
        System.out.println("file <user> <path_to_file>");
        System.out.println("exit");

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            try {
                if (input.equals("ping")) {
                    proxy.ping();
                    System.out.println("Ping OK");
                } else if (input.startsWith("echo ")) {
                    String text = input.substring(5);
                    System.out.println(proxy.echo(text));
                } else if (input.startsWith("login ")) {
                    handleLogin(input);
                } else if (input.equals("list")) {
                    handleList();
                } else if (input.startsWith("msg ")) {
                    handleMessage(input);
                } else if (input.startsWith("file ")) {
                    handleFile(input);
                } else if (input.equals("exit")) {
                    handleExit();
                    break;
                } else {
                    System.out.println("Unknown command");
                }
            } catch (LoginException e) {
                System.out.println("Login failed: " + e.getMessage());
            } catch (ArgumentException e) {
                System.out.println("Argument error"
                        + (e.getArgumentName() != null ? " [" + e.getArgumentName() + "]" : "")
                        + ": " + e.getMessage());
            } catch (ServerException e) {
                System.out.println("Server error: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("File error: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }

        scanner.close();
    }

    private static void handleLogin(String input)
            throws Exception {
        String[] parts = input.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: login <username> <password>");
            return;
        }

        String username = parts[1];
        String password = parts[2];

        sessionId = proxy.login(username, password);
        System.out.println("Logged in successfully");
        startPolling();
    }

    private static void handleList()
            throws Exception {
        if (!isLoggedIn()) {
            System.out.println("You must login first");
            return;
        }

        String[] users = proxy.listUsers(sessionId);

        if (users == null || users.length == 0) {
            System.out.println("No active users");
            return;
        }

        System.out.println("Active users:");
        for (String user : users) {
            System.out.println("- " + user);
        }
    }

    private static void handleMessage(String input)
            throws Exception {
        if (!isLoggedIn()) {
            System.out.println("You must login first");
            return;
        }

        String[] parts = input.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: msg <user> <text>");
            return;
        }

        String receiver = parts[1];
        String text = parts[2];

        Message msg = new Message(receiver, text);
        proxy.sendMessage(sessionId, msg);
        System.out.println("Message sent");
    }

    private static void handleFile(String input)
            throws Exception {
        if (!isLoggedIn()) {
            System.out.println("You must login first");
            return;
        }

        String[] parts = input.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: file <user> <path_to_file>");
            return;
        }

        String receiver = parts[1];
        String filePath = parts[2];

        File localFile = new File(filePath);
        if (!localFile.exists() || !localFile.isFile()) {
            System.out.println("File not found: " + filePath);
            return;
        }

        FileInfo fileInfo = new FileInfo(receiver, localFile);
        proxy.sendFile(sessionId, fileInfo);
        System.out.println("File sent");
    }

    private static void handleExit() {
        stopPolling();

        if (sessionId != null) {
            try {
                proxy.exit(sessionId);
                System.out.println("Logged out");
            } catch (Exception e) {
                System.out.println("Exit warning: " + e.getMessage());
            }
        }

        System.out.println("Bye!");
    }

    private static boolean isLoggedIn() {
        return sessionId != null && !sessionId.isBlank();
    }

    private static void startPolling() {
        stopPolling();

        pollTimer = new Timer(true);
        pollTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!isLoggedIn()) {
                    return;
                }

                try {
                    Message msg = proxy.receiveMessage(sessionId);
                    if (msg != null) {
                        System.out.println();
                        System.out.println("[NEW MESSAGE] from " + msg.getSender() + ": " + msg.getMessage());
                        System.out.print("> ");
                    }

                    FileInfo file = proxy.receiveFile(sessionId);
                    if (file != null) {
                        File receiveDir = new File("received");
                        if (!receiveDir.exists()) {
                            receiveDir.mkdirs();
                        }

                        file.saveFileTo(receiveDir);

                        System.out.println();
                        System.out.println("[NEW FILE] from " + file.getSender()
                                + ": " + file.getFilename()
                                + " saved to folder 'received'");
                        System.out.print("> ");
                    }
                } catch (Exception e) {
                    System.out.println();
                    System.out.println("[Polling error] " + e.getMessage());
                    System.out.print("> ");
                }
            }
        }, 0, POLL_PERIOD_MS);
    }

    private static void stopPolling() {
        if (pollTimer != null) {
            pollTimer.cancel();
            pollTimer = null;
        }
    }
}