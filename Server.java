import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.awt.Color;

public class Server {

  private int port;
  private List<User> clients;
  private ServerSocket server;

  public static void main(String[] args) throws IOException {
    // CLOUD-READY: Use Railway's port if available, otherwise use 12345 for local testing
    int port = 22395; // Changed to match your client's default port
    String envPort = System.getenv("PORT");
    if (envPort != null && !envPort.isEmpty()) {
        port = Integer.parseInt(envPort);
    }
    new Server(port).run();
  }

  public Server(int port) {
    this.port = port;
    // CRITICAL FIX: Thread-safe list prevents crashes when multiple users join/leave
    this.clients = new CopyOnWriteArrayList<User>(); 
  }

  public void run() throws IOException {
    server = new ServerSocket(port);
    System.out.println("✨ Server is now running on port " + port);
    System.out.println("📡 Waiting for connections...");

    while (true) {
      Socket client = server.accept();
      String nickname = (new Scanner(client.getInputStream())).nextLine();
      nickname = nickname.replace(",", "").replace(" ", "_");
      System.out.println("✅ New Client: \"" + nickname + "\" from: " + client.getInetAddress().getHostAddress());

      User newUser = new User(client, nickname);
      this.clients.add(newUser);

      // Send welcome message with HTML formatting
      newUser.getOutStream().println("<div style='background: #059669; padding: 10px; border-radius: 8px;'>" +
                                     "<span style='color: #ffffff;'>✨ Welcome to the chat, " + newUser.toString() + "!</span></div>");

      new Thread(new UserHandler(this, newUser)).start();
      
      // Broadcast updated user list to everyone
      broadcastAllUsers();
    }
  }

  public void removeUser(User user){
    this.clients.remove(user);
    System.out.println("👋 User left: " + user.getNickname());
    broadcastAllUsers(); // Update user list when someone leaves
  }

  public void broadcastMessages(String msg, User userSender) {
    String formattedMessage = "<b>" + userSender.toString() + ":</b> <span>" + escapeHtml(msg) + "</span>";
    for (User client : this.clients) {
      if (client != userSender) {
        client.getOutStream().println(formattedMessage);
      } else {
        // Show sender their own message with different styling
        client.getOutStream().println("<b style='color: #a5b4fc;'>You:</b> <span>" + escapeHtml(msg) + "</span>");
      }
    }
  }

  public void broadcastAllUsers(){
    StringBuilder userList = new StringBuilder("[");
    for (int i = 0; i < clients.size(); i++) {
      if (i > 0) userList.append(", ");
      userList.append(clients.get(i).getNickname());
    }
    userList.append("]");
    String userListString = userList.toString();
    
    for (User client : this.clients) {
      client.getOutStream().println(userListString);
    }
  }

  public void sendMessageToUser(String msg, User userSender, String targetUser){
    boolean find = false;
    for (User client : this.clients) {
      if (client.getNickname().equals(targetUser) && client != userSender) {
        find = true;
        // Send to sender
        userSender.getOutStream().println("[PRIVATE]" + targetUser + "|" + 
          "<b style='color: #a5b4fc;'>You → " + targetUser + ":</b> <span>" + escapeHtml(msg) + "</span>");
        // Send to recipient
        client.getOutStream().println("[PRIVATE]" + userSender.getNickname() + "|" + 
          "<b style='color: #fbbf24;'>🔒 Private from " + userSender.getNickname() + ":</b> <span>" + escapeHtml(msg) + "</span>");
        break;
      }
    }
    if (!find) {
      userSender.getOutStream().println("[PRIVATE]System|" + 
        "<b style='color: #ef4444;'>⚠️ User '" + targetUser + "' not found or offline</b>");
    }
  }
  
  private String escapeHtml(String text) {
    return text.replace("&", "&amp;")
              .replace("<", "&lt;")
              .replace(">", "&gt;")
              .replace("\"", "&quot;")
              .replace("'", "&#39;");
  }
}

class UserHandler implements Runnable {
  private Server server;
  private User user;

  public UserHandler(Server server, User user) {
    this.server = server;
    this.user = user;
    this.server.broadcastAllUsers();
  }

  public void run() {
    String message;
    Scanner sc = new Scanner(this.user.getInputStream());
    while (sc.hasNextLine()) {
      message = sc.nextLine();
      
      // Handle private messages
      if (message.startsWith("@PM ")) {
        // Format: @PM username message
        String[] parts = message.substring(4).split(" ", 2);
        if (parts.length == 2) {
          String targetUser = parts[0];
          String privateMsg = parts[1];
          server.sendMessageToUser(privateMsg, user, targetUser);
        }
      }
      // Handle regular private messages (backward compatibility)
      else if (message.charAt(0) == '@' && message.contains(" ")){
        int firstSpace = message.indexOf(" ");
        String userPrivate = message.substring(1, firstSpace);
        server.sendMessageToUser(message.substring(firstSpace+1), user, userPrivate);
      } 
      // Handle color change
      else if (message.charAt(0) == '#'){
        user.changeColor(message);
        this.server.broadcastAllUsers();
      } 
      // Handle regular broadcast messages
      else {
        server.broadcastMessages(message, user);
      }
    }
    server.removeUser(user);
    sc.close();
  }
}

class User {
  private static int nbUser = 0;
  private int userId;
  private PrintStream streamOut;
  private InputStream streamIn;
  private String nickname;
  private String color;

  public User(Socket client, String name) throws IOException {
    this.streamOut = new PrintStream(client.getOutputStream());
    this.streamIn = client.getInputStream();
    this.nickname = name;
    this.userId = nbUser++;
    this.color = ColorInt.getColor(this.userId);
    System.out.println("🎨 Assigned color " + this.color + " to user " + nickname);
  }

  public void changeColor(String hexColor){
    Pattern colorPattern = Pattern.compile("#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})", Pattern.CASE_INSENSITIVE);
    Matcher m = colorPattern.matcher(hexColor);
    if (m.find()){
      this.color = hexColor;
      this.getOutStream().println("<div style='background: #059669; padding: 8px; border-radius: 6px;'>" +
                                  "<span style='color: #ffffff;'>✅ Color changed successfully to " + this.toString() + "</span></div>");
      System.out.println("🎨 " + nickname + " changed color to " + hexColor);
      return;
    }
    this.getOutStream().println("<div style='background: #dc2626; padding: 8px; border-radius: 6px;'>" +
                                "<span style='color: #ffffff;'>❌ Failed to change color. Use format: #RRGGBB</span></div>");
  }

  public PrintStream getOutStream(){ return this.streamOut; }
  public InputStream getInputStream(){ return this.streamIn; }
  public String getNickname(){ return this.nickname; }
  
  public String toString(){
    return "<span style='color:" + this.color + "; font-weight: bold;'>" + 
           this.getNickname() + "</span>";
  }
}

class ColorInt {
    public static String[] mColors = {
            "#6366F1", // Indigo
            "#EF4444", // Red
            "#F59E0B", // Amber
            "#10B981", // Emerald
            "#3B82F6", // Blue
            "#8B5CF6", // Purple
            "#EC4899", // Pink
            "#14B8A6", // Teal
            "#F97316", // Orange
            "#06B6D4"  // Cyan
    };
    
    public static String getColor(int i) {
        return mColors[i % mColors.length];
    }
}
