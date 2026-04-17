import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Server {

    // Must match ClientGui
    private static final byte PKT_LOGIN = 1;
    private static final byte PKT_CHAT  = 2;
    private static final byte PKT_USERS = 3;
    private static final byte PKT_FILE  = 4;
    private static final byte PKT_SYS   = 5;

    private final int port;
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private ServerSocket serverSocket;

    public static void main(String[] args) throws Exception {
        int port = 12345;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try { port = Integer.parseInt(envPort.trim()); } catch (Exception ignored) {}
        }
        new Server(port).start();
    }

    public Server(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        serverSocket = new ServerSocket(port);
        System.out.println("Server is now running on port " + port);

        while (true) {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);

            ClientHandler handler = new ClientHandler(this, socket);
            clients.add(handler);
            new Thread(handler, "client-" + socket.getPort()).start();
        }
    }

    private void removeClient(ClientHandler client) {
        clients.remove(client);
        broadcastUsers();
    }

    private void broadcastUsers() {
        StringBuilder csv = new StringBuilder();
        for (ClientHandler c : clients) {
            if (c.nickname != null && !c.nickname.isBlank()) {
                if (csv.length() > 0) csv.append(",");
                csv.append(c.nickname);
            }
        }
        String users = csv.toString();
        for (ClientHandler c : clients) {
            try {
                c.sendStringPacket(PKT_USERS, users);
            } catch (Exception ignored) {}
        }
    }

    private void broadcastChat(String msgHtmlSafe, ClientHandler sender) {
        String line = sender.userTag() + "<span>: " + msgHtmlSafe + "</span>";
        for (ClientHandler c : clients) {
            try {
                c.sendStringPacket(PKT_CHAT, line);
            } catch (Exception ignored) {}
        }
    }

    private void sendPrivateMessage(String msgHtmlSafe, ClientHandler sender, String targetName) {
        ClientHandler target = null;
        for (ClientHandler c : clients) {
            if (c != sender && c.nickname.equals(targetName)) {
                target = c;
                break;
            }
        }

        try {
            if (target == null) {
                sender.sendStringPacket(PKT_SYS, "User @" + targetName + " not found.");
                return;
            }

            sender.sendStringPacket(PKT_CHAT,
                    sender.userTag() + " -> " + target.userTag() + ": " + msgHtmlSafe);

            target.sendStringPacket(PKT_CHAT,
                    "(<b>Private</b>) " + sender.userTag() + "<span>: " + msgHtmlSafe + "</span>");
        } catch (Exception ignored) {}
    }

    private void relayFile(String type, String senderName, String fileName, byte[] fileBytes, ClientHandler sender) {
        for (ClientHandler c : clients) {
            if (c == sender) continue;
            try {
                c.sendFilePacket(type, senderName, fileName, fileBytes);
            } catch (Exception ignored) {}
        }
    }

    static class ClientHandler implements Runnable {

        private final Server server;
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final Object writeLock = new Object();

        private static int colorIdx = 0;
        private static final String[] COLORS = {
                "#7289DA", "#e15258", "#f9845b", "#53bbb4", "#51b46d",
                "#e0ab18", "#f092b0", "#e8d174", "#d64d4d", "#4d7358"
        };

        String nickname = "user";
        String color = COLORS[(colorIdx++) % COLORS.length];

        ClientHandler(Server server, Socket socket) throws Exception {
            this.server = server;
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        @Override
        public void run() {
            try {
                // First packet should be LOGIN
                byte type = in.readByte();
                int len = in.readInt();
                if (type != PKT_LOGIN) throw new IOException("Expected login packet");
                if (len < 0 || len > 1_000_000) throw new IOException("Invalid login length");

                byte[] payload = in.readNBytes(len);
                if (payload.length != len) throw new EOFException("Login packet truncated");

                nickname = sanitizeNickname(new String(payload, StandardCharsets.UTF_8));

                System.out.println("New Client: \"" + nickname + "\" Host: " + socket.getInetAddress().getHostAddress());

                sendStringPacket(PKT_CHAT, "<b>Welcome</b> " + userTag());
                server.broadcastUsers();

                // Main packet loop
                while (true) {
                    byte pType = in.readByte();
                    int pLen = in.readInt();

                    if (pLen < 0 || pLen > 200_000_000) throw new IOException("Invalid packet length");
                    byte[] pData = in.readNBytes(pLen);
                    if (pData.length != pLen) throw new EOFException("Packet truncated");

                    if (pType == PKT_CHAT) {
                        handleChatPacket(pData);
                    } else if (pType == PKT_FILE) {
                        handleFilePacket(pData);
                    } else {
                        // Unknown packet type
                        throw new IOException("Unknown packet type: " + pType);
                    }
                }

            } catch (Exception ignored) {
                // disconnect / parse error
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
                server.removeClient(this);
                System.out.println("Client disconnected: " + nickname);
            }
        }

        private void handleChatPacket(byte[] pData) throws Exception {
            String msg = sanitizeChat(new String(pData, StandardCharsets.UTF_8));

            if (msg.isBlank()) return;

            // emoji shortcuts
            msg = msg.replace(":)", "😊").replace(":(", "☹️").replace(":D", "😁")
                     .replace("-_-", "😑").replace(";)", "😉").replace(":P", "😛")
                     .replace(":o", "😲").replace(":O", "😲");

            // private message: @user text
            if (msg.startsWith("@") && msg.contains(" ")) {
                int firstSpace = msg.indexOf(' ');
                String target = msg.substring(1, firstSpace).trim();
                String body = msg.substring(firstSpace + 1).trim();
                if (!target.isEmpty() && !body.isEmpty()) {
                    server.sendPrivateMessage(body, this, target);
                }
                return;
            }

            // color change: #hex
            if (msg.startsWith("#")) {
                if (changeColor(msg.trim())) {
                    sendStringPacket(PKT_SYS, "Color changed successfully.");
                    server.broadcastUsers();
                } else {
                    sendStringPacket(PKT_SYS, "Invalid color hex.");
                }
                return;
            }

            server.broadcastChat(msg, this);
        }

        // Client payload format:
        // [typeLen:int][typeBytes][senderLen:int][senderBytes][fileNameLen:int][fileNameBytes][fileSize:long][fileBytes]
        
        private void handleFilePacket(byte[] pData) throws Exception {
    DataInputStream p = new DataInputStream(new ByteArrayInputStream(pData));

    String fileType = readUTF8(p);
    String targetUser = readUTF8(p);
    String senderName = readUTF8(p);
    String fileName = readUTF8(p);
    long fileSize = p.readLong();

    System.out.println("DEBUG SERVER fileType=" + fileType + " targetUser=[" + targetUser + "] sender=" + senderName);

    if (fileSize < 0 || fileSize > 200_000_000) {
        throw new IOException("File too large or invalid size");
    }

    byte[] fileBytes = p.readNBytes((int) fileSize);
    if (fileBytes.length != (int) fileSize) {
        throw new EOFException("File payload truncated");
    }

    if (!"*".equals(targetUser.trim())) {
        server.relayFileToSingleUser(fileType, senderName, fileName, fileBytes, this, targetUser.trim());
    } else {
        server.relayFile(fileType, senderName, fileName, fileBytes, this);
    }

    sendStringPacket(PKT_SYS, "Uploaded " + fileType + ": " + fileName);
}

        // Server PKT_FILE payload back to clients:
        // [typeLen][type][senderLen][sender][fileNameLen][fileName][fileSize][fileBytes]
        void sendFilePacket(String fileType, String senderName, String fileName, byte[] fileBytes) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);

            byte[] t = fileType.getBytes(StandardCharsets.UTF_8);
            byte[] s = senderName.getBytes(StandardCharsets.UTF_8);
            byte[] n = fileName.getBytes(StandardCharsets.UTF_8);

            d.writeInt(t.length); d.write(t);
            d.writeInt(s.length); d.write(s);
            d.writeInt(n.length); d.write(n);
            d.writeLong(fileBytes.length);
            d.write(fileBytes);
            d.flush();

            byte[] payload = bos.toByteArray();

            synchronized (writeLock) {
                out.writeByte(PKT_FILE);
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            }
        }

        void sendStringPacket(byte type, String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                out.writeByte(type);
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            }
        }

        String userTag() {
            return "<u><span style='color:" + color + "'>" + nickname + "</span></u>";
        }

        private boolean changeColor(String hex) {
            Pattern colorPattern = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
            Matcher m = colorPattern.matcher(hex);
            if (m.matches()) {
                color = hex;
                return true;
            }
            return false;
        }

        private static String readUTF8(DataInputStream in) throws IOException {
            int len = in.readInt();
            if (len < 0 || len > 1_000_000) throw new IOException("Invalid string length");
            byte[] b = in.readNBytes(len);
            if (b.length != len) throw new EOFException("Truncated string");
            return new String(b, StandardCharsets.UTF_8);
        }

        private static String sanitizeNickname(String n) {
            if (n == null) return "user";
            n = n.trim().replace(",", "").replace(" ", "_");
            if (n.isEmpty()) n = "user";
            if (n.length() > 24) n = n.substring(0, 24);
            return n;
        }

        private static String sanitizeChat(String msg) {
            if (msg == null) return "";
            msg = msg.trim();
            if (msg.length() > 2000) msg = msg.substring(0, 2000);
            return msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private void relayFileToSingleUser(String type, String sender, String fileName, byte[] fileBytes,ClientHandler senderClient, String targetUser) {
            ClientHandler target = null;
            for (ClientHandler c : clients) {
                if (c.nickname.equals(targetUser)) { target = c; break; }
            }
            try {
                if (target == null) {
                    senderClient.sendStringPacket(PKT_SYS, "User @" + targetUser + " not found for private file.");
                    return;
                }
                target.sendFilePacket(type, sender, fileName, fileBytes);
                senderClient.sendStringPacket(PKT_SYS, "Private " + type + " sent to @" + targetUser);
            } catch (Exception ignored) {}
        }
}
