package org.lolwat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.dreambot.api.Client;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.container.impl.bank.BankLocation;
import org.dreambot.api.methods.interactive.NPCs;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.map.Area;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.tabs.Tab;
import org.dreambot.api.methods.tabs.Tabs;
import org.dreambot.api.methods.trade.Trade;
import org.dreambot.api.methods.walking.impl.Walking;
import org.dreambot.api.methods.world.Worlds;
import org.dreambot.api.randoms.RandomEvent;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.Category;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.script.listener.ChatListener;
import org.dreambot.api.utilities.AccountManager;
import org.dreambot.api.utilities.Logger;
import org.dreambot.api.utilities.Sleep;
import org.dreambot.api.utilities.Timer;
import org.dreambot.api.wrappers.interactive.Player;
import org.dreambot.api.wrappers.items.Item;
import org.dreambot.api.wrappers.widgets.Menu;
import org.dreambot.api.wrappers.widgets.message.Message;
import org.dreambot.api.wrappers.widgets.message.MessageType;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ScriptManifest(name = "WatMule", description = "It is what it is", author = "lolwat",
        version = 1.1, category = Category.MISC,  image = "https://api.botbuddy.net/WatScripts.png")
public class WatMule extends AbstractScript implements ChatListener {
    private enum State {
        WAITING,
        LOGGED_IN,
        SETUP
    }

    private State currentState;
    private static HashMap<String, Long> queue;
    private int goldCollected = 0;
    private String target = "";
    private static HashMap<String, Integer> coinsToReverse = new HashMap<>();
    private static final Area MULE_AREA = new Area(3158, 3496, 3171, 3483);
    private static final Area CUSTOM_AREA = new Area(
            new Tile[] {
                    new Tile(3161, 3499, 0),
                    new Tile(3168, 3499, 0),
                    new Tile(3174, 3494, 0),
                    new Tile(3174, 3486, 0),
                    new Tile(3169, 3481, 0),
                    new Tile(3160, 3481, 0),
                    new Tile(3155, 3486, 0),
                    new Tile(3155, 3493, 0),
                    new Tile(3163, 3492, 0),
                    new Tile(3166, 3492, 0),
                    new Tile(3167, 3491, 0),
                    new Tile(3167, 3488, 0),
                    new Tile(3163, 3488, 0),
                    new Tile(3162, 3489, 0),
                    new Tile(3162, 3492, 0),
                    new Tile(3155, 3493, 0)
            }
    );
    Timer timer;

    private static final HashMap<String, String> webhookUrls = new HashMap<String, String>() {{
        put("lolwat", "https://discord.com/api/webhooks/1295107274628399114/RIXif-CYVpuZ2lJxhauRwQXfybL3A792rVdtJdmIA5cdxQeAETXjGh8zu3aGfrl4ClAb");
        put("svensson", "https://discord.com/api/webhooks/1302790039360634890/jkZrdFnH8-A4SFneDH_Ccs3imXIYHuGIkUomCgm9jxf_5_ZEzHsVGTMJJLgm3XyW60cb");
        put("ronscapes", "https://discord.com/api/webhooks/1332117032778600531/ZeK-sVHrXUAtynvoXVQpWZlaqSPYvBjZ8PYaGBjqeN_gKRtal0ow7hu27yxfM4UVSb2H");
    }};

    private static void handleClient(Socket clientSocket) {
        try {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = clientSocket.getInputStream().read(buffer)) != -1) {
                String receivedMessage = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);

                if(receivedMessage.startsWith("READY")) {
                    Logger.log("REC: " + receivedMessage);
                    //
                    String[] messageContent = receivedMessage.split("\\|");
                    if(receivedMessage.contains("READY-REVERSE")) {
                        String[] reverseContent = receivedMessage.split(";");
                        for(String item : reverseContent) {
                            String[] itemContents = item.split(":");
                            if(!itemContents[0].isEmpty() && !itemContents[1].isEmpty()) {
                                coinsToReverse.put(messageContent[1], Integer.parseInt(itemContents[1]));
                            }
                        }

                        if (!queue.containsKey(messageContent[1])) {
                            queue.put(messageContent[1], Instant.now().getEpochSecond());
                        }

                        Logger.log("Added " + messageContent[1] + " to reverse mule queue, giving them " + coinsToReverse.get(messageContent[1]) + " coins");
                        String response = "OK|" + Players.getLocal().getName() + "|" + Worlds.getCurrentWorld();
                        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                        clientSocket.getOutputStream().write(responseBytes);
                    }
                    else {
                        if (messageContent.length > 0) {
                            if (!queue.containsKey(messageContent[1])) {
                                queue.put(messageContent[1], Instant.now().getEpochSecond());
                            }
                            Logger.log("Added " + messageContent[1] + " to mule queue");
                            String response = "OK|" + Players.getLocal().getName() + "|" + Worlds.getCurrentWorld();
                            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                            clientSocket.getOutputStream().write(responseBytes);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private int tradeCount = 0;

    @Override
    public void onMessage(Message m) {
        if(target.isEmpty()) {
            if (m.getType().equals(MessageType.TRADE)) {
                if (!queue.isEmpty() && queue.containsKey(m.getUsername())) {
                    target = m.getUsername();
                }
            }
        }
    }

    @Override
    public void onExit() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            if (executorService != null) {
                executorService.shutdown();
            }
        } catch (IOException e) {
            Logger.log(e.toString());
        }
    }

    public void doStart(String profile) {
        ConfigManager.setInstance(new ConfigManager());
        ConfigManager.getInstance().loadFromProfile(profile);

        Walking.setMinimapTargetSize(15);
        executorService = Executors.newFixedThreadPool(10);

        executorService.submit(() -> {
            try {
                serverSocket = new ServerSocket(ConfigManager.getInstance().getConfigInt("listen_port"));
                Logger.log("Mule server running on port " + ConfigManager.getInstance().getConfigInt("listen_port"));

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    executorService.submit(() -> handleClient(clientSocket));
                }
            } catch (IOException e) {
                Logger.log(e.toString());
            }
        });

        currentState = State.SETUP;
        queue = new HashMap<>();
        timer = new Timer();

        if (!org.dreambot.api.wrappers.widgets.Menu.isMenuManipulationActive()) {
            Logger.log("Enabling menu manipulation");
            Menu.toggleMenuManipulation(true);
        }
    }

    @Override
    public void onStart() {
        doStart("default");
    }

    @Override
    public void onStart(String... params) {
        if (params.length > 0) {
            doStart(params[0]);
        } else {
            doStart("default");
        }
    }

    @Override
    public int onLoop() {
        if(queue.containsKey(target) && (Instant.now().getEpochSecond() - queue.get(target)) >= 120) {
            queue.remove(target);
            Logger.log("removed " + target + " from queue due to delinquency");
            target = "";
        }

        switch(currentState) {
            case WAITING: {
                if(!queue.isEmpty()) {
                    getRandomManager().enableSolver(RandomEvent.LOGIN);
                    currentState = State.LOGGED_IN;
                    return 1;
                }

                return 500;
            }

            case LOGGED_IN: {
                if(Client.isLoggedIn()) {
                    if (!CUSTOM_AREA.contains(Players.getLocal())) {
                        Tile centerTile = CUSTOM_AREA.getRandomTile();
                        Walking.walk(centerTile);
                        Sleep.sleepUntil(() -> CUSTOM_AREA.contains(Players.getLocal()), 5000);
                        return 1000;
                    }

                    if (Inventory.isFull()) {
                        Logger.log("Inventory is full. Depositing items except for coins.");
                        if (!Bank.open(BankLocation.GRAND_EXCHANGE)) {
                            Logger.log("Unable to open bank at Grand Exchange.");
                            return 1000;
                        }

                        Sleep.sleepUntil(Bank::isOpen, 5000);

                        if (Bank.isOpen()) {
                            // Deposit all items except coins
                            Bank.depositAllExcept("Coins");
                            Sleep.sleepUntil(() -> !Inventory.isFull(), 3000);
                        } else {
                            Logger.log("Failed to open bank.");
                            return 1000;
                        }

                        Logger.log("Items deposited. Resuming muling task.");
                    }

                    if(!Tabs.isOpen(Tab.INVENTORY)) {
                        Inventory.open();
                    }

                    int coinsRequired = 0;
                    if(!coinsToReverse.isEmpty()) {
                        for (Integer coins : coinsToReverse.values()) {
                            coinsRequired += coins;
                        }
                    }

                    if(coinsRequired > 0 && !Trade.isOpen()) {
                        if(!Inventory.contains("Coins") || Inventory.count("Coins") < coinsRequired) {
                            if (!Bank.isOpen()) {
                                if (Bank.open()) {
                                    if (Bank.contains("Coins") && Bank.count("Coins") >= coinsRequired) {
                                        Bank.withdraw("Coins", coinsRequired);
                                    }

                                    Bank.close();
                                }
                            }
                        }
                    }

                    if(!queue.isEmpty()) {
                        if(target.isEmpty()) {
                            List<String> toRemove = new ArrayList<>();
                            for(Map.Entry<String, Long> kv : queue.entrySet()) {
                                if((Instant.now().getEpochSecond() - kv.getValue()) >= 180) {
                                    toRemove.add(kv.getKey());
                                }
                            }

                            for(String n : toRemove) {
                                queue.remove(n);
                                Logger.log("removed " + n + " from queue due to delinquency");
                            }

                            toRemove.clear();
                            return 2000;
                        }
                        else {
                            int changeBy = 0;
                            Player slave = Players.closest(target);
                            if(slave != null && Players.getLocal().canReach(slave.getTile())) {
                                if(Trade.isOpen()) {
                                    if(Trade.isOpen(1)) {
                                        if (coinsToReverse.containsKey(slave.getName())) {
                                            if (!Trade.contains(true, "Coins") && Inventory.contains("Coins")) {
                                                Item coins = Inventory.get("Coins");
                                                if (coins != null) {
                                                    Trade.addItem(coins, Inventory.count("Coins")
                                                            >= coinsToReverse.get(slave.getName())
                                                            ? coinsToReverse.get(slave.getName())
                                                            : Inventory.count("Coins"));

                                                    Sleep.sleepUntil(() -> Trade.contains(true, "Coins"), 2000);
                                                }
                                            }
                                        }

                                        if (Trade.contains(true, "Coins")
                                                || Trade.contains(false, "Coins")) {
                                            Trade.acceptTrade(1);
                                            Sleep.sleepUntil(() -> Trade.isOpen(2), 2000);
                                        }
                                    }
                                    else if(Trade.isOpen(2)) {
                                        if(!Trade.contains(true, "Coins")
                                                && !Trade.contains(false, "Coins")) {

                                            Logger.log(target + " has not offered any coins. Cancelling trade.");
                                            Trade.declineTrade();
                                            target = "";
                                            return 1000;
                                        }

                                        if(Trade.contains(true, "Coins")
                                                || Trade.contains(false, "Coins")) {

                                            Item myCoins = Trade.getItem(true, "Coins");
                                            if(myCoins != null) {
                                                changeBy = -myCoins.getAmount();
                                                goldCollected -= myCoins.getAmount();
                                            }

                                            Item theirCoins = Trade.getItem(false, "Coins");
                                            if(theirCoins != null) {
                                                changeBy = theirCoins.getAmount();
                                                goldCollected += theirCoins.getAmount();
                                            }

                                            Trade.acceptTrade();
                                            Sleep.sleepUntil(() -> !Trade.isOpen(), 2000);
                                            sendMuleWebhook(target, changeBy);
                                            coinsToReverse.remove(target);
                                            queue.remove(target);
                                            target = "";
                                            tradeCount++;
                                        }
                                    }
                                } else {
                                    Trade.tradeWithPlayer(target);
                                    Sleep.sleepUntil(Trade::isOpen, 5000);
                                    return 300;
                                }
                            }
                        }
                    } else {
                        logout();
                        getRandomManager().disableSolver(RandomEvent.LOGIN);
                        Logger.log("Disabled login solver");
                        currentState = State.WAITING;
                    }
                }
                return 3;
            }

            case SETUP: {
                if(NPCs.closest("Grand Exchange Clerk") != null) {
                    if(!Bank.isOpen()) {
                        Bank.open();
                        Sleep.sleepUntil(Bank::isOpen, 5000);

                        if(!Inventory.isEmpty()) {
                            Bank.depositAllExcept("Coins");
                            Sleep.sleep(500, 750);
                        }

                        if(Bank.contains("Coins")) {
                            Bank.withdrawAll("Coins");
                        }

                        Sleep.sleep(500, 750);

                        Bank.close();
                        logout();

                        getRandomManager().disableSolver(RandomEvent.LOGIN);
                        Logger.log("Disabled login solver");
                        currentState = State.WAITING;
                        sendMuleWebhook("SCRIPT_SETUP", 0);
                    }
                } else {
                    if(Walking.shouldWalk(6)) {
                        Walking.walk(BankLocation.GRAND_EXCHANGE);
                    }
                }
                return 3;
            }
            default:
                return 3;
        }
    }

    private void logout() {
        if(!Tabs.isOpen(Tab.LOGOUT)) {
            Tabs.open(Tab.LOGOUT);
            Sleep.sleep(300, 500);
            Tabs.logout();
        }
    }

    public static String simplifyNumber(double number) {
        boolean isNegative = number < 0;
        number = Math.abs(number);

        String simplifiedNumber;
        if (number >= 1000000) {
            simplifiedNumber = String.format("%.2fM", number / 1000000);
        } else if (number >= 1000) {
            simplifiedNumber = String.format("%.2fK", number / 1000);
        } else {
            simplifiedNumber = String.format("%.2f", number);
        }

        return isNegative ? "-" + simplifiedNumber : simplifiedNumber;
    }

    @Override
    public void onPaint(Graphics g) {
        // Calculate average GP per hour
        double hours = timer.elapsed() / 3600000.0; // convert milliseconds to hours
        double avgGpPerHour = (hours > 0) ? goldCollected / hours : 0;

        // Enable anti-aliasing for smoother text
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Set the font metrics for sizing the chatbox
        FontMetrics fm = g2d.getFontMetrics();

        // Determine the height of each section
        int sectionHeight = 58;

        // Draw the background
        g.setColor(new Color(42, 42, 42, 220));
        int boxWidth = Math.max(400, fm.stringWidth("WatMule: GP collected: " + simplifyNumber(goldCollected)));
        int boxHeight = 2 * sectionHeight;
        g.fillRect(0, 0, boxWidth, boxHeight);

        // Draw the border
        g.setColor(new Color(107, 107, 107));
        g.drawRect(0, 0, boxWidth, boxHeight);

        // Draw the title
        g.setColor(new Color(220, 220, 220));
        g.setFont(new Font("Segoe UI", Font.BOLD, 20));
        g.drawString("WatMule: " + (!queue.isEmpty() ? "Processing queue of " + queue.size() : "Waiting"), 15, 30);
        g.drawLine(15, 42, boxWidth - 15, 42);

        // Draw the GP collected information
        g.setColor(new Color(220, 220, 220));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        g.drawString("GP collected: " + simplifyNumber(goldCollected), 15, 70);
        g.drawString("GP per hour: " + simplifyNumber(avgGpPerHour), 15, 90); // New line for GP per hour
        g.drawString("Runtime: " + Timer.formatTime(timer.elapsed()), boxWidth / 2, 70);

        // Draw the trade count information
        //g.drawString("Trades: " + tradeCount, boxWidth / 2, 90);
    }

    public void sendMuleWebhook(String ptarget, int transacted) {
        String formattedMessage = String.format("**Mule**: %s\n**Target**: %s\n**Gold transacted**: %s\n**Gold on mule**: %s", AccountManager.getAccountNickname(), ptarget, simplifyNumber(transacted), simplifyNumber(Inventory.count("Coins")));
        sendWebhook(formattedMessage, false);
    }

    public void sendWebhook(String message, boolean error) {
        String webhookUrl = "https://api.botbuddy.net/ws_discord.php";
        try {
            getResponseCode(message, webhookUrl, error);
        } catch (Exception e) {
            Logger.log("webhook: " + e.getMessage());
        }
    }

    private static void getResponseCode(String message, String webhookUrl, boolean error) throws IOException {
        if(Client.getForumUser() == null)
            return;

        if(!webhookUrls.containsKey(Client.getForumUser().getUsername().toLowerCase()))
            return;

        URL url = new URL(webhookUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Mule");
        embed.addProperty("description", message);
        embed.addProperty("color", error ? 16711680 : 3447003);

        JsonObject payload = new JsonObject();
        payload.add("embeds", new Gson().toJsonTree(new JsonObject[]{embed}));
        payload.addProperty("webhook_url", webhookUrls.get(Client.getForumUser().getUsername().toLowerCase()));

        String jsonPayload = new Gson().toJson(payload);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        connection.getResponseCode();
    }
}