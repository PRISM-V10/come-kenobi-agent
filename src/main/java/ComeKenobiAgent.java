import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import markets.alpaca.client.AlpacaClient;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.TradingApiEnvironment;
import markets.alpaca.client.openapi.trading.api.AccountsApi;
import markets.alpaca.client.openapi.trading.api.AssetsApi;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.model.CreateOrderRequest;
import markets.alpaca.client.openapi.trading.model.OrderSide;
import markets.alpaca.client.openapi.trading.model.OrderType;
import markets.alpaca.client.openapi.trading.model.TimeInForce;

/**
 * Recurring, rate-limited SPY buyer on Alpaca.
 *
 * IMPORTANT — things this file intentionally does differently from the
 * original draft, and why:
 *
 *  1. Uses a NOTIONAL (dollar amount) order instead of qty=1 share. SPY
 *     trades in the $700s; a $20 account cannot buy 1 whole share. Notional
 *     orders only work on fractionable symbols, so we check that first.
 *
 *  2. Does NOT track a fake local "balance"/"profit". Instead it logs your
 *     real buying power/equity from Alpaca after each check-in. Real P&L
 *     tracking (cost basis in vs. proceeds out) isn't implemented because
 *     this bot never sells — see evaluateAndPickStrategy(). If you want an
 *     actual buy/sell strategy with P&L, that's a separate, deliberate
 *     feature to design, not something to bolt on silently.
 *
 *  3. The `markets.alpaca.client.*` package below matches the *official*
 *     alpaca-java SDK (github.com/alpacahq/alpaca-java) as of the current
 *     docs. That library is brand new (pre-1.0, published as a -SNAPSHOT in
 *     places) — double-check the exact method names in your IDE's
 *     autocomplete / the generated Javadoc before you trust this blindly,
 *     especially CreateOrderRequest.notional(...), which I inferred from
 *     Alpaca's public order schema but could not directly confirm in the
 *     hosted docs.
 */
public class ComeKenobiAgent {

    private static final String SYMBOL = "SPY";
    // Dollar amount to spend per buy (notional order), not a share count.
    private static final String NOTIONAL_PER_TRADE = "2.00";
    private static final String TRADE_STATE_FILE = "trade_state.txt";

    private boolean isActive = true;
    // Epoch-day timestamp of each recent trade. This is what makes the
    // 5-day limit an actual rolling window: on every check we drop entries
    // older than 5 days and count what's left, rather than resetting a
    // counter from a fixed anchor point.
    private final List<Long> recentTradeDays = new ArrayList<>();

    private final AuditTrail bossLog = new AuditTrail("BOSS");
    private final AuditTrail guardianLog = new AuditTrail("GUARDIAN");
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AlpacaClient client;

    public static void main(String[] args) throws Exception {
        loadEnv();
        ComeKenobiAgent agent = new ComeKenobiAgent();
        agent.startAutoTrade();
    }

    private static void loadEnv() {
        try {
            Files.lines(Paths.get(".env"))
                .filter(line -> !line.startsWith("#") && line.contains("="))
                .forEach(line -> {
                    String[] parts = line.split("=", 2);
                    System.setProperty(parts[0].trim(), parts[1].trim());
                });
            System.out.println("✅ Loaded .env file");
        } catch (IOException e) {
            System.out.println("⚠️ No .env file found – using environment variables.");
        }
    }

    public ComeKenobiAgent() {
        bossLog.log("Agent initializing…");
        this.client = buildClient();
        loadTradeState();
        logAccountSnapshot();
    }

    private AlpacaClient buildClient() {
        String apiKey = System.getProperty("ALPACA_API_KEY");
        String secretKey = System.getProperty("ALPACA_SECRET_KEY");
        String baseUrl = System.getProperty("ALPACA_BASE_URL");

        // Kept your existing .env variable names so you don't have to change
        // your .env file — just decide PAPER vs PRODUCTION from it.
        boolean isPaper = baseUrl == null || baseUrl.isEmpty() || baseUrl.contains("paper");
        TradingApiEnvironment env = isPaper ? TradingApiEnvironment.PAPER : TradingApiEnvironment.PRODUCTION;

        if (!isPaper) {
            bossLog.log("⚠️  LIVE trading environment selected — real money will move. "
                + "Double-check ALPACA_BASE_URL in your .env is really what you want.");
        } else {
            bossLog.log("📄 Paper trading environment selected.");
        }

        AlpacaCredentials credentials = new AlpacaCredentials(apiKey, secretKey);
        return AlpacaClient.builder(credentials)
            .tradingEnvironment(env)
            .build();
    }

    private void logAccountSnapshot() {
        try {
            AccountsApi accounts = new AccountsApi(client.newTradingClient());
            var account = accounts.getAccount();
            bossLog.log("💰 Real account — buying power: $" + account.getBuyingPower()
                + " | equity: $" + account.getEquity());
        } catch (Exception e) {
            guardianLog.log("Could not fetch account snapshot: " + e.getMessage());
        }
    }

    private void startAutoTrade() throws InterruptedException {
        bossLog.log("⏰ Auto-trade will run once per hour.");
        scheduler.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.HOURS);
        // Block the main thread on the scheduler itself instead of a manual
        // sleep loop.
        scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    private void tick() {
        loadTradeState();
        if (!isActive) return;

        if (!canTrade()) {
            bossLog.log("⏳ Trading blocked – daily/5-day limit reached.");
            return;
        }

        bossLog.log("📈 Attempting buy: " + SYMBOL);
        executeTrade();
    }

    private boolean canTrade() {
        long today = LocalDate.now().toEpochDay();

        // Drop any trade older than 5 days — this is the "rolling" part.
        // At any moment, recentTradeDays only holds trades from the last
        // 5 calendar days (today and the 4 before it).
        recentTradeDays.removeIf(day -> today - day >= 5);

        boolean alreadyTradedToday = recentTradeDays.contains(today);
        return !alreadyTradedToday && recentTradeDays.size() < 3;
    }

    private void loadTradeState() {
        recentTradeDays.clear();
        try {
            File file = new File(TRADE_STATE_FILE);
            if (file.exists()) {
                for (String line : Files.readAllLines(Paths.get(TRADE_STATE_FILE))) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        recentTradeDays.add(Long.parseLong(line));
                    }
                }
            }
        } catch (Exception e) {
            bossLog.log("⚠️ Failed to load trade state: " + e.getMessage());
        }
    }

    private void saveTradeState() {
        try {
            StringBuilder content = new StringBuilder();
            for (long day : recentTradeDays) {
                content.append(day).append("\n");
            }
            Files.write(Paths.get(TRADE_STATE_FILE), content.toString().getBytes());
        } catch (Exception e) {
            bossLog.log("⚠️ Failed to save trade state: " + e.getMessage());
        }
    }

    private void executeTrade() {
        try {
            AssetsApi assets = new AssetsApi(client.newTradingClient());
            var spy = assets.getV2AssetsSymbolOrAssetId(SYMBOL);

            if (spy.getTradable() == null || !spy.getTradable()) {
                bossLog.log("❌ " + SYMBOL + " is not tradable right now — skipping.");
                return;
            }
            if (spy.getFractionable() == null || !spy.getFractionable()) {
                bossLog.log("❌ " + SYMBOL + " doesn't support notional/fractional orders — "
                    + "can't place a $" + NOTIONAL_PER_TRADE + " order this way.");
                return;
            }

            OrdersApi ordersApi = new OrdersApi(client.newTradingClient());
            CreateOrderRequest request = new CreateOrderRequest()
                .symbol(SYMBOL)
                .notional(NOTIONAL_PER_TRADE)   // dollar amount, not share qty — verify this method name compiles
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .timeInForce(TimeInForce.DAY)
                .clientOrderId("comekenobi-" + UUID.randomUUID());

            var order = ordersApi.postOrder(request);
            bossLog.log("✅ Order submitted — id=" + order.getId() + " status=" + order.getStatus());

            recentTradeDays.add(LocalDate.now().toEpochDay());
            saveTradeState();
            logAccountSnapshot(); // real numbers, not a fake increment

        } catch (Exception e) {
            bossLog.log("❌ Trade failed: " + e.getMessage());
            guardianLog.log("Trade error: " + e.getMessage());
        }
    }

    class AuditTrail {
        private final String type;
        public AuditTrail(String type) { this.type = type; }
        public void log(String message) {
            System.out.println("[" + type + "] " + Instant.now() + " — " + message);
        }
    }
}