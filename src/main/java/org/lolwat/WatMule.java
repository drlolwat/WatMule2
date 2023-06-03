package org.lolwat;

import org.dreambot.api.Client;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.container.impl.bank.BankLocation;
import org.dreambot.api.methods.interactive.NPCs;
import org.dreambot.api.methods.interactive.Players;
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
import org.dreambot.api.utilities.Logger;
import org.dreambot.api.utilities.Sleep;
import org.dreambot.api.utilities.Timer;
import org.dreambot.api.wrappers.widgets.message.Message;
import org.dreambot.api.wrappers.widgets.message.MessageType;

import java.awt.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ScriptManifest(name = "WatMule", description = "It is what it is", author = "lolwat",
        version = 1.0, category = Category.MISC, image = "")
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
    Timer timer;

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

    @Override
    public void onStart() {
        Walking.setMinimapTargetSize(15);
        Client.getInstance().setMouseMovementAlgorithm(new BezierMouse());
        executorService = Executors.newFixedThreadPool(10);

        executorService.submit(() -> {
            try {
                serverSocket = new ServerSocket(8081);
                Logger.log("Mule server running");

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
    }

    @Override
    public int onLoop() {
        switch(currentState) {
            case WAITING: {
                if(queue.size() > 0) {
                    getRandomManager().enableSolver(RandomEvent.LOGIN);
                    currentState = State.LOGGED_IN;
                    return 1;
                }

                return 500;
            }

            case LOGGED_IN: {
                if(Client.isLoggedIn()) {
                    if(!Tabs.isOpen(Tab.INVENTORY)) {
                        Inventory.open();
                    }

                    int coinsRequired = 0;
                    if(coinsToReverse.size() > 0) {
                        for (Integer coins : coinsToReverse.values()) {
                            coinsRequired += coins;
                        }
                    }

                    if(coinsRequired > 0 && !Trade.isOpen()) {
                        if(!Inventory.contains("Coins") || Inventory.get("Coins").getAmount() < coinsRequired) {
                            if (!Bank.isOpen()) {
                                if (Bank.open()) {
                                    if (Bank.contains("Coins") && Bank.get("Coins").getAmount() >= coinsRequired) {
                                        Bank.withdraw("Coins", coinsRequired);
                                    }

                                    Bank.close();
                                }
                            }
                        }
                    }

                    if(queue.size() > 0) {
                        if(target.isEmpty()) {
                            // check for possible dead clients. slaves in queue who havent traded me for 5 minutes
                            List<String> toRemove = new ArrayList<>();
                            for(Map.Entry<String, Long> kv : queue.entrySet()) {
                                if((Instant.now().getEpochSecond() - kv.getValue()) >= 300) {
                                    toRemove.add(kv.getKey());
                                }
                            }

                            for(String n : toRemove) {
                                queue.remove(n);
                                Logger.log("removed " + n + " from queue due to delinquency");
                            }

                            toRemove.clear();
                            return 2000; // wait for target to trade me
                        }
                        else {
                            if (Players.closest(target) != null && Players.closest(target).distance() <= 2) {
                                if (!Trade.isOpen()) {
                                    Trade.tradeWithPlayer(target);
                                    Sleep.sleepUntil(Trade::isOpen, 6000);
                                    return 1;
                                } else {
                                    if (Trade.isOpen(1)) {
                                        if(coinsToReverse.containsKey(target) && !Trade.contains(true, "Coins")) {
                                            Trade.addItem(Inventory.get("Coins"), coinsToReverse.get(target));
                                            Sleep.sleep(500, 800);
                                        }

                                        Trade.acceptTrade(1);
                                        Sleep.sleepUntil(() -> Trade.isOpen(2), 2000);
                                    }

                                    if (Trade.isOpen(2)) {
                                        Trade.acceptTrade(2);
                                        if (Trade.getItem(false, "Coins") != null) {
                                            goldCollected += Trade.getItem(false, "Coins").getAmount();
                                        }

                                        Sleep.sleepUntil(() -> !Trade.isOpen(), 2000);

                                        coinsToReverse.remove(target);
                                        queue.remove(target);
                                        target = "";
                                        tradeCount++; // Increment the trade count
                                        return 1;
                                    }
                                }
                            } else {
                                if(queue.containsKey(target) && (Instant.now().getEpochSecond() - queue.get(target)) >= 120) {
                                    queue.remove(target);
                                    Logger.log("removed " + target + " from queue due to delinquency");
                                    target = "";
                                }
                            }
                        }
                    } else {
                        if(!Tabs.isOpen(Tab.LOGOUT)) {
                            Tabs.open(Tab.LOGOUT);
                            Sleep.sleep(300, 500);
                            Tabs.logout();
                        }

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
                            Bank.depositAllItems();
                        }

                        Sleep.sleep(500, 750);

                        Bank.close();
                        if(!Tabs.isOpen(Tab.LOGOUT)) {
                            Tabs.open(Tab.LOGOUT);
                            Sleep.sleep(300, 500);
                            Tabs.logout();
                        }

                        getRandomManager().disableSolver(RandomEvent.LOGIN);
                        Logger.log("Disabled login solver");
                        currentState = State.WAITING;
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

    public static String simplifyNumber(double number) {
        if (number >= 1000000) {
            return String.format("%.2fM", number / 1000000);
        } else if (number >= 1000) {
            return String.format("%.2fK", number / 1000);
        } else {
            return String.format("%.2f", number);
        }
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
        g.drawString("WatMule: " + (queue.size() > 0 ? "Processing queue of " + queue.size() : "Waiting"), 15, 30);
        g.drawLine(15, 42, boxWidth - 15, 42);

        // Draw the GP collected information
        g.setColor(new Color(220, 220, 220));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        g.drawString("GP collected: " + simplifyNumber(goldCollected), 15, 70);
        g.drawString("GP per hour: " + simplifyNumber(avgGpPerHour), 15, 90); // New line for GP per hour
        g.drawString("Runtime: " + Timer.formatTime(timer.elapsed()), boxWidth / 2, 70);

        // Draw the trade count information
        g.drawString("Trades: " + tradeCount, boxWidth / 2, 90);
    }
}