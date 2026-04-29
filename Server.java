import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Server extends WebSocketServer {

    private static final byte PKT_LOGIN = 1;
    private static final byte PKT_CHAT  = 2;
    private static final byte PKT_USERS = 3;
    private static final byte PKT_FILE  = 4;
    private static final byte PKT_SYS   = 5;

    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public static void main(String[] args) {
        int port = 8080; // Default port for Cloud deployment
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try { port = Integer.parseInt(envPort.trim()); } catch (Exception ignored) {}
        }
        
        Server server = new Server(port);
        server.start();
        System.out.println("WebSocket Server running on port " + port);
    }

    public Server(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        ClientHandler handler = new ClientHandler(this, conn);
        conn.setAttachment(handler);
        clients.add(handler);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientHandler handler = conn.getAttachment();
        if (handler != null) {
            clients.remove(handler);
            System.out.println("Client disconnected: " + handler.nickname);
            broadcastUsers();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // We only use binary messages
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message.array()));
            byte type = in.readByte();
            int len = in.readInt();
            byte[] payload = new byte[len];
            in.readFully(payload);

            ClientHandler handler = conn.getAttachment();
            if (handler != null) {
                handler.handlePacket(type, payload);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            ClientHandler handler = conn.getAttachment();
            if (handler != null) clients.remove(handler);
        }
    }

    @Override
    public void onStart() {
        System.out.println("Server started successfully!");
    }

    public void broadcastUsers() {
        StringBuilder csv = new StringBuilder();
        for (ClientHandler c : clients) {
            if (c.loggedIn && c.nickname != null && !c.nickname.isBlank()) {
                if (csv.length() > 0) csv.append(",");
                csv.append(c.nickname);
            }
        }
        String users = csv.toString();
        for (ClientHandler c : clients) {
            if (c.loggedIn) c.sendStringPacket(PKT_USERS, users);
        }
    }

    public void broadcastChat(String msgHtmlSafe, ClientHandler sender) {
        String line = sender.userTag() + "<span>: " + msgHtmlSafe + "</span>";
        for (ClientHandler c : clients) {
            if (c.loggedIn) c.sendStringPacket(PKT_CHAT, line);
        }
    }

    public void sendPrivateMessage(String msgHtmlSafe, ClientHandler sender, String targetName) {
        ClientHandler target = null;
        for (ClientHandler c : clients) {
            if (c.loggedIn && c.nickname.equals(targetName)) {
                target = c;
                break;
            }
        }
        if (target == null) {
            sender.sendStringPacket(PKT_SYS, "User @" + targetName + " not found.");
            return;
        }
        sender.sendStringPacket(PKT_CHAT, sender.userTag() + " -> " + target.userTag() + ": " + msgHtmlSafe);
        target.sendStringPacket(PKT_CHAT, "(<b>Private</b>) " + sender.userTag() + "<span>: " + msgHtmlSafe + "</span>");
    }

    public void relayFile(String type, String senderName, String fileName, byte[] fileBytes, ClientHandler sender) {
        for (ClientHandler c : clients) {
            if (c.loggedIn && c != sender) c.sendFilePacket(type, senderName, fileName, fileBytes);
        }
    }

    public void relayFileToSingleUser(String type, String sender, String fileName, byte[] fileBytes, ClientHandler senderClient, String targetUser) {
        ClientHandler target = null;
        for (ClientHandler c : clients) {
            if (c.loggedIn && c.nickname.equals(targetUser)) { target = c; break; }
        }
        if (target == null) {
            senderClient.sendStringPacket(PKT_SYS, "User @" + targetUser + " not found.");
            return;
        }
        target.sendFilePacket(type, sender, fileName, fileBytes);
        senderClient.sendStringPacket(PKT_SYS, "Private " + type + " sent to @" + targetUser);
    }

    static class ClientHandler {
        private final Server server;
        private final WebSocket conn;
        boolean loggedIn = false;
        String nickname = "user";
        String color;
        private static int colorIdx = 0;
        private static final String[] COLORS = { "#7289DA", "#e15258", "#f9845b", "#53bbb4", "#51b46d", "#e0ab18", "#f092b0", "#e8d174", "#d64d4d", "#4d7358" };

        ClientHandler(Server server, WebSocket conn) {
            this.server = server;
            this.conn = conn;
            this.color = COLORS[(colorIdx++) % COLORS.length];
        }

        void handlePacket(byte pType, byte[] pData) throws Exception {
            if (!loggedIn) {
                if (pType == PKT_LOGIN) {
                    nickname = sanitizeNickname(new String(pData, StandardCharsets.UTF_8));
                    loggedIn = true;
                    System.out.println("New Client: " + nickname);
                    sendStringPacket(PKT_CHAT, "<b>Welcome</b> " + userTag());
                    server.broadcastUsers();
                }
                return;
            }

            if (pType == PKT_CHAT) handleChatPacket(pData);
            else if (pType == PKT_FILE) handleFilePacket(pData);
        }

        private void handleChatPacket(byte[] pData) {
            String msg = sanitizeChat(new String(pData, StandardCharsets.UTF_8));
            if (msg.isBlank()) return;

            msg = msg.replace(":)", "😊").replace(":(", "☹️").replace(":D", "😁").replace("-_-", "😑").replace(";)", "😉").replace(":P", "😛");

            if (msg.startsWith("@") && msg.contains(" ")) {
                int firstSpace = msg.indexOf(' ');
                String target = msg.substring(1, firstSpace).trim();
                String body = msg.substring(firstSpace + 1).trim();
                if (!target.isEmpty() && !body.isEmpty()) server.sendPrivateMessage(body, this, target);
                return;
            }

            if (msg.startsWith("#")) {
                if (changeColor(msg.trim())) {
                    sendStringPacket(PKT_SYS, "Color changed successfully.");
                    server.broadcastUsers();
                } else sendStringPacket(PKT_SYS, "Invalid color hex.");
                return;
            }
            server.broadcastChat(msg, this);
        }

        private void handleFilePacket(byte[] pData) throws Exception {
            DataInputStream p = new DataInputStream(new ByteArrayInputStream(pData));
            String fileType = readUTF8(p);
            String targetUser = readUTF8(p);
            String senderName = readUTF8(p);
            String fileName = readUTF8(p);
            long fileSize = p.readLong();

            byte[] fileBytes = new byte[(int) fileSize];
            p.readFully(fileBytes);

            if (!"*".equals(targetUser.trim())) server.relayFileToSingleUser(fileType, senderName, fileName, fileBytes, this, targetUser.trim());
            else server.relayFile(fileType, senderName, fileName, fileBytes, this);

            sendStringPacket(PKT_SYS, "Uploaded " + fileType + ": " + fileName);
        }

        void sendFilePacket(String fileType, String senderName, String fileName, byte[] fileBytes) {
            try {
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

                sendPacket(PKT_FILE, bos.toByteArray());
            } catch (Exception ignored) {}
        }

        void sendStringPacket(byte type, String text) {
            sendPacket(type, text.getBytes(StandardCharsets.UTF_8));
        }

        private void sendPacket(byte type, byte[] payload) {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(bos);
                dos.writeByte(type);
                dos.writeInt(payload.length);
                dos.write(payload);
                conn.send(bos.toByteArray());
            } catch (Exception ignored) {}
        }

        String userTag() { return "<u><span style='color:" + color + "'>" + nickname + "</span></u>"; }

        private boolean changeColor(String hex) {
            if (Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matcher(hex).matches()) {
                color = hex; return true;
            }
            return false;
        }

        private static String readUTF8(DataInputStream in) throws IOException {
            int len = in.readInt();
            byte[] b = new byte[len];
            in.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        private static String sanitizeNickname(String n) {
            n = n.trim().replace(",", "").replace(" ", "_");
            return n.isEmpty() ? "user" : (n.length() > 24 ? n.substring(0, 24) : n);
        }

        private static String sanitizeChat(String msg) {
            msg = msg.trim();
            return (msg.length() > 2000 ? msg.substring(0, 2000) : msg).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
