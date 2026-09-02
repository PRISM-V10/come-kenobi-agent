import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private boolean isActive = true;

    private final AuditTrail bossLog = new AuditTrail("BOSS");
    private final AuditTrail guardianLog = new AuditTrail("GUARDIAN");
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AlpacaClient client;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String supabaseUrl;
    private final String supabaseKey;

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

    // Checks a value loaded from .env first (local dev), then falls back to a
    // real OS environment variable (how Railway and most cloud hosts pass
    // secrets in — no .env file exists there, and never should).
    private static String getConfig(String key) {
        String fromEnvFile = System.getProperty(key);
        if (fromEnvFile != null && !fromEnvFile.isEmpty()) {
            return fromEnvFile;
        }
        return System.getenv(key);
    }

    public ComeKenobiAgent() {
        bossLog.log("Agent initializing…");
        this.client = buildClient();
        this.supabaseUrl = getConfig("SUPABASE_URL");
        this.supabaseKey = getConfig("SUPABASE_ANON_KEY");
        if (isBlank(supabaseUrl) || isBlank(supabaseKey)) {
            guardianLog.log("⚠️ Supabase credentials missing — trade history can't be verified, "
                + "so trades will be blocked (fail-safe) until SUPABASE_URL/SUPABASE_ANON_KEY are set.");
        }
        logAccountSnapshot();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private AlpacaClient buildClient() {
        String apiKey = getConfig("ALPACA_API_KEY");
        String secretKey = getConfig("ALPACA_SECRET_KEY");
        String baseUrl = getConfig("ALPACA_BASE_URL");

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
        if (!isActive) return;

        if (!canTrade()) {
            bossLog.log("⏳ Trading blocked – daily/5-day limit reached.");
            return;
        }

        bossLog.log("📈 Attempting buy: " + SYMBOL);
        executeTrade();
    }

    private boolean canTrade() {
        List<Long> recentTradeDays = fetchRecentTradeDays();
        if (recentTradeDays == null) {
            // Couldn't verify history from Supabase — block rather than risk
            // trading blind and exceeding the intended limit.
            guardianLog.log("⏳ Could not verify trade history — blocking trade to be safe.");
            return false;
        }

        long today = LocalDate.now().toEpochDay();
        recentTradeDays.removeIf(day -> today - day >= 5);

        boolean alreadyTradedToday = recentTradeDays.contains(today);
        return !alreadyTradedToday && recentTradeDays.size() < 3;
    }

    // Pulls recent trade timestamps from Supabase's trade_log table and
    // returns them as epoch-days. Returns null on any failure so canTrade()
    // can fail safe instead of assuming "no trades" and over-trading.
    private List<Long> fetchRecentTradeDays() {
        if (isBlank(supabaseUrl) || isBlank(supabaseKey)) {
            return null;
        }
        try {
            String url = supabaseUrl + "/rest/v1/trade_log?select=timestamp&order=timestamp.desc&limit=50";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer " + supabaseKey)
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                guardianLog.log("Supabase trade history fetch failed: HTTP " + response.statusCode()
                    + " " + response.body());
                return null;
            }

            List<Long> days = new ArrayList<>();
            Matcher m = Pattern.compile("\"timestamp\":\"([^\"]+)\"").matcher(response.body());
            while (m.find()) {
                days.add(OffsetDateTime.parse(m.group(1)).toLocalDate().toEpochDay());
            }
            return days;
        } catch (Exception e) {
            guardianLog.log("Error fetching trade history from Supabase: " + e.getMessage());
            return null;
        }
    }

    // Records a completed order in Supabase's trade_log table.
    private void recordTrade(String orderId, String status) {
        try {
            String body = "{"
                + "\"symbol\":\"" + SYMBOL + "\","
                + "\"side\":\"buy\","
                + "\"notional\":" + NOTIONAL_PER_TRADE + ","
                + "\"status\":\"" + status + "\","
                + "\"order_id\":\"" + orderId + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/rest/v1/trade_log"))
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer " + supabaseKey)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                bossLog.log("🗃️  Trade recorded in Supabase.");
            } else {
                guardianLog.log("Failed to record trade in Supabase: HTTP " + response.statusCode()
                    + " " + response.body());
            }
        } catch (Exception e) {
            guardianLog.log("Error recording trade in Supabase: " + e.getMessage());
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

recordTrade(String.valueOf(order.getId()), String.valueOf(order.getStatus()));            logAccountSnapshot(); // real numbers, not a fake increment

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