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
    int port = 12345;
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
    System.out.println("Server is now running on port " + port);

    while (true) {
      Socket client = server.accept();
      String nickname = (new Scanner ( client.getInputStream() )).nextLine();
      nickname = nickname.replace(",", "").replace(" ", "_");
      System.out.println("New Client: \"" + nickname + "\" Host: " + client.getInetAddress().getHostAddress());

      User newUser = new User(client, nickname);
      this.clients.add(newUser);

      newUser.getOutStream().println("<b>Welcome</b> " + newUser.toString());

      new Thread(new UserHandler(this, newUser)).start();
    }
  }

  public void removeUser(User user){
    this.clients.remove(user);
  }

  public void broadcastMessages(String msg, User userSender) {
    for (User client : this.clients) {
      client.getOutStream().println(userSender.toString() + "<span>: " + msg + "</span>");
    }
  }

  public void broadcastAllUsers(){
    for (User client : this.clients) {
      client.getOutStream().println(this.clients);
    }
  }

  public void sendMessageToUser(String msg, User userSender, String user){
    boolean find = false;
    for (User client : this.clients) {
      if (client.getNickname().equals(user) && client != userSender) {
        find = true;
        userSender.getOutStream().println(userSender.toString() + " -> " + client.toString() +": " + msg);
        client.getOutStream().println("(<b>Private</b>)" + userSender.toString() + "<span>: " + msg+"</span>");
      }
    }
    if (!find) {
      userSender.getOutStream().println(userSender.toString() + " -> (<b>no one!</b>): " + msg);
    }
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

      // CRITICAL FIX: HTML Sanitization (Stops users from hacking the chat UI)
      message = message.replace("<", "&lt;").replace(">", "&gt;");

      // Smileys
      message = message.replace(":)", "😊").replace(":(", "☹️").replace(":D", "😁")
                       .replace("-_-", "😑").replace(";)", "😉").replace(":P", "😛")
                       .replace(":o", "😲").replace(":O", "😲");

      if (message.charAt(0) == '@' && message.contains(" ")){
        int firstSpace = message.indexOf(" ");
        String userPrivate = message.substring(1, firstSpace);
        server.sendMessageToUser(message.substring(firstSpace+1), user, userPrivate);
      } else if (message.charAt(0) == '#'){
        user.changeColor(message);
        this.server.broadcastAllUsers();
      } else {
        server.broadcastMessages(message, user);
      }
    }
    server.removeUser(user);
    this.server.broadcastAllUsers();
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
  }

  public void changeColor(String hexColor){
    Pattern colorPattern = Pattern.compile("#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})");
    Matcher m = colorPattern.matcher(hexColor);
    if (m.matches()){
      this.color = hexColor;
      this.getOutStream().println("<b>Color changed successfully</b> " + this.toString());
      return;
    }
    this.getOutStream().println("<b>Failed to change color</b>");
  }

  public PrintStream getOutStream(){ return this.streamOut; }
  public InputStream getInputStream(){ return this.streamIn; }
  public String getNickname(){ return this.nickname; }
  public String toString(){
    return "<u><span style='color:"+ this.color +"'>" + this.getNickname() + "</span></u>";
  }
}

class ColorInt {
    public static String[] mColors = {
            "#7289DA", "#e15258", "#f9845b", "#53bbb4", "#51b46d", 
            "#e0ab18", "#f092b0", "#e8d174", "#d64d4d", "#4d7358"
    };
    public static String getColor(int i) {
        return mColors[i % mColors.length];
    }
}