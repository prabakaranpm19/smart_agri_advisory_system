import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.Date;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;

/**
 * Single-file web application entry point.
 * Integrates SQLite JDBC persistence, crop services, threads, exceptions,
 * and com.sun.net.httpserver.HttpServer backend API.
 */
public class CropAdvisoryApp {

    private static final int PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
    private static CropService cropService;
    private static final Queue<String> weatherAlerts = new ConcurrentLinkedQueue<>();
    private static final int MAX_WEATHER_ALERTS = 20;

    public static String fetchOpenMeteoWeather(double lat, double lon) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto",
                    lat, lon);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            System.err.println("Open-Meteo Weather API Fetch Error: " + e.getMessage());
        }
        // Fallback mock JSON if offline
        return String.format(Locale.US, "{\"current\":{\"temperature_2m\":29.5,\"relative_humidity_2m\":62,\"weather_code\":1,\"wind_speed_10m\":11.2},\"daily\":{\"time\":[\"2026-09-17\",\"2026-09-18\",\"2026-09-19\",\"2026-09-20\",\"2026-09-21\"],\"weather_code\":[1,2,3,0,1],\"temperature_2m_max\":[32.0,33.5,31.0,30.0,32.2],\"temperature_2m_min\":[22.0,23.0,21.5,20.0,21.0],\"precipitation_probability_max\":[15,20,65,40,10]}}");
    }

    public static void main(String[] args) {
        System.out.println("Starting Farmer Crop Advisory Web Server...");

        // 1. Initialize Service & Load Data
        cropService = new CropService();
        cropService.loadData();

        // 2. Start Background Weather Daemon Thread (Interval: 10 seconds for web response)
        WeatherAlertDaemon weatherDaemon = new WeatherAlertDaemon(10000);
        weatherDaemon.start();

        // 3. Start HTTP Server
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            
            // Serve API routes
            server.createContext("/api", new ApiHandler());
            
            // Serve Static Frontend files (index.html, style.css, app.js) from web directory
            server.createContext("/", new StaticFileHandler("web"));

            server.setExecutor(Executors.newFixedThreadPool(10)); // Thread pool for handling requests
            server.start();

            System.out.println("==========================================================");
            System.out.println("   FARMER CROP ADVISORY WEB APPLICATION IS LIVE!");
            System.out.println("   Open your browser and navigate to: http://localhost:" + PORT);
            System.out.println("   Database Engine: " + (DatabaseManager.isJdbcAvailable() ? "SQLite (JDBC)" : "Flat File (CSV Backup)"));
            System.out.println("==========================================================");

        } catch (IOException e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void addWeatherAlert(String alert) {
        if (weatherAlerts.size() >= MAX_WEATHER_ALERTS) {
            weatherAlerts.poll();
        }
        weatherAlerts.add(alert);
    }

    // =========================================================================
    // HTTP SERVER HANDLERS
    // =========================================================================

    /**
     * Serves static HTML, CSS, and JS files from the given root folder.
     */
    static class StaticFileHandler implements HttpHandler {
        private final String baseDir;

        public StaticFileHandler(String baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }

            File file = new File(baseDir, path);
            if (!file.exists() || file.isDirectory()) {
                // Fallback to absolute or sibling folder checks if run from nested dirs
                file = new File("d:/pbl/java/web", path);
            }

            if (!file.exists() || file.isDirectory()) {
                String errorMsg = "404 Not Found: " + path;
                exchange.sendResponseHeaders(404, errorMsg.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorMsg.getBytes());
                }
                return;
            }

            String mimeType = "text/plain";
            if (path.endsWith(".html")) mimeType = "text/html";
            else if (path.endsWith(".css")) mimeType = "text/css";
            else if (path.endsWith(".js")) mimeType = "application/javascript";
            else if (path.endsWith(".json")) mimeType = "application/json";

            exchange.getResponseHeaders().set("Content-Type", mimeType);
            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /**
     * Handles all REST API requests.
     */
    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Enable CORS for testing
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            try {
                if (path.equals("/api/status") && method.equals("GET")) {
                    handleStatus(exchange);
                } else if (path.equals("/api/crops") && method.equals("GET")) {
                    handleGetCrops(exchange);
                } else if (path.equals("/api/crops") && method.equals("POST")) {
                    handleCreateCrop(exchange);
                } else if (path.equals("/api/farmers") && method.equals("GET")) {
                    handleGetFarmers(exchange);
                } else if (path.equals("/api/farmers") && method.equals("POST")) {
                    handleCreateFarmer(exchange);
                } else if (path.startsWith("/api/farmers/") && path.endsWith("/history") && method.equals("GET")) {
                    handleGetFarmerHistory(exchange);
                } else if (path.equals("/api/advisory") && method.equals("POST")) {
                    handleRunAdvisory(exchange);
                } else if (path.equals("/api/simulate-water") && method.equals("POST")) {
                    handleSimulateWater(exchange);
                } else if (path.equals("/api/weather-alerts") && method.equals("GET")) {
                    handleGetWeatherAlerts(exchange);
                } else if (path.equals("/api/weather") && method.equals("GET")) {
                    handleGetWeather(exchange);
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"Route Not Found\"}");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }

        private void handleGetWeather(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            double lat = 30.90;
            double lon = 75.85;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        try {
                            if (pair[0].equals("lat")) lat = Double.parseDouble(pair[1]);
                            if (pair[0].equals("lon")) lon = Double.parseDouble(pair[1]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            String json = fetchOpenMeteoWeather(lat, lon);
            sendResponse(exchange, 200, json);
        }

        private void handleStatus(HttpExchange exchange) throws IOException {
            String json = String.format("{\"jdbcActive\":%b,\"cropsCount\":%d,\"farmersCount\":%d}",
                    DatabaseManager.isJdbcAvailable(),
                    cropService.getCropCatalog().size(),
                    cropService.getFarmers().size());
            sendResponse(exchange, 200, json);
        }

        private void handleGetCrops(HttpExchange exchange) throws IOException {
            List<Crop> crops = new ArrayList<>(cropService.getCropCatalog().values());
            crops.sort(Comparator.comparing(Crop::getName));

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < crops.size(); i++) {
                sb.append(crops.get(i).toJson());
                if (i < crops.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleCreateCrop(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            String name = params.get("name");
            String type = params.get("type");
            String soilsStr = params.get("soils");
            String seasonStr = params.get("season");
            String waterStr = params.get("water");
            double yield = Double.parseDouble(params.get("yield"));
            double urea = Double.parseDouble(params.get("urea"));
            double dap = Double.parseDouble(params.get("dap"));
            double mop = Double.parseDouble(params.get("mop"));
            String special = params.get("special");

            double marketPrice = (params.get("marketPrice") != null && !params.get("marketPrice").trim().isEmpty())
                    ? Double.parseDouble(params.get("marketPrice")) : 20000.0;
            double seedCost = (params.get("seedCost") != null && !params.get("seedCost").trim().isEmpty())
                    ? Double.parseDouble(params.get("seedCost")) : 3000.0;

            if (name == null || name.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Crop name cannot be empty.\"}");
                return;
            }

            List<Cultivable.SoilType> soils = new ArrayList<>();
            for (String s : soilsStr.split(";")) {
                if (!s.trim().isEmpty()) {
                    soils.add(Cultivable.SoilType.valueOf(s.trim().toUpperCase()));
                }
            }

            Cultivable.Season season = Cultivable.Season.fromString(seasonStr);
            Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(waterStr.toUpperCase());

            Crop crop;
            if ("CashCrop".equalsIgnoreCase(type)) {
                crop = new CashCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost);
            } else {
                crop = new FoodCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost);
            }

            cropService.addCrop(crop);
            sendResponse(exchange, 201, "{\"success\":true,\"message\":\"Crop added successfully\"}");
        }

        private void handleGetFarmers(HttpExchange exchange) throws IOException {
            List<Farmer> farmersList = new ArrayList<>(cropService.getFarmers().values());
            farmersList.sort(Comparator.comparing(Farmer::getFarmerId));

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < farmersList.size(); i++) {
                sb.append(farmersList.get(i).toJson());
                if (i < farmersList.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleCreateFarmer(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            String id = params.get("id");
            String name = params.get("name");
            double acres = Double.parseDouble(params.get("acres"));
            String soilStr = params.get("soil");
            String waterStr = params.get("water");
            String classChoice = params.get("classChoice"); // "1" for Small, "2" for Large
            String location = (params.get("location") != null && !params.get("location").trim().isEmpty())
                    ? params.get("location") : "Punjab, India";
            double latitude = (params.get("latitude") != null && !params.get("latitude").trim().isEmpty())
                    ? Double.parseDouble(params.get("latitude")) : 30.90;
            double longitude = (params.get("longitude") != null && !params.get("longitude").trim().isEmpty())
                    ? Double.parseDouble(params.get("longitude")) : 75.85;

            if (id == null || id.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Farmer ID cannot be empty.\"}");
                return;
            }
            if (cropService.getFarmers().containsKey(id)) {
                sendResponse(exchange, 400, "{\"error\":\"A farmer with ID '" + id + "' is already registered.\"}");
                return;
            }

            Cultivable.SoilType soil = Cultivable.SoilType.valueOf(soilStr.toUpperCase());
            Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(waterStr.toUpperCase());

            Farmer farmer;
            if ("1".equals(classChoice)) {
                farmer = new SmallFarmer(id, name, acres, soil, water, location, latitude, longitude);
            } else {
                farmer = new LargeFarmer(id, name, acres, soil, water, location, latitude, longitude);
            }

            cropService.registerFarmer(farmer);
            sendResponse(exchange, 201, "{\"success\":true,\"message\":\"Farmer registered successfully\"}");
        }

        private void handleGetFarmerHistory(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // Expected path: /api/farmers/{id}/history
            String[] parts = path.split("/");
            if (parts.length < 4) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid farmer ID route\"}");
                return;
            }
            String farmerId = parts[2];
            Farmer farmer = cropService.getFarmers().get(farmerId);
            if (farmer == null) {
                sendResponse(exchange, 404, "{\"error\":\"Farmer not found\"}");
                return;
            }

            StringBuilder sb = new StringBuilder("[");
            List<Farmer.FarmRecord> records = farmer.getHistory();
            for (int i = 0; i < records.size(); i++) {
                sb.append(records.get(i).toJson());
                if (i < records.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleRunAdvisory(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            String farmerId = params.get("farmerId");
            String seasonStr = params.get("season");

            if (farmerId == null || farmerId.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"farmerId parameter is required.\"}");
                return;
            }

            Farmer farmer = cropService.getFarmers().get(farmerId);
            if (farmer == null) {
                sendResponse(exchange, 404, "{\"error\":\"Farmer with ID '" + farmerId + "' was not found.\"}");
                return;
            }

            Cultivable.Season season = (seasonStr != null && !seasonStr.trim().isEmpty()) ? Cultivable.Season.fromString(seasonStr) : Cultivable.Season.SUMMER;
            List<CropService.AdvisoryResult> results;
            try {
                results = cropService.generateAdvisory(farmerId, season);
            } catch (CropExceptions.FarmerNotFoundException e) {
                sendResponse(exchange, 404, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                return;
            }

            // Generate JSON results array
            StringBuilder resultsJson = new StringBuilder("[");
            for (int i = 0; i < results.size(); i++) {
                CropService.AdvisoryResult r = results.get(i);
                resultsJson.append(String.format("{\"cropName\":\"%s\",\"classification\":\"%s\",\"score\":%d,\"yield\":%.2f}",
                        escapeJson(r.getCrop().getName()),
                        escapeJson(r.getCrop().getClassification()),
                        r.getScore(),
                        r.getCrop().estimateYield(farmer.getFarmSize())));
                if (i < results.size() - 1) resultsJson.append(",");
            }
            resultsJson.append("]");

            // Generate detailed layout report string
            String reportText = cropService.generateAdvisoryReport(farmer, results);

            double grossCost = 0.0, netCost = 0.0, reqUrea = 0.0, reqDap = 0.0, reqMop = 0.0;
            if (!results.isEmpty()) {
                Crop topCrop = results.get(0).getCrop();
                double size = farmer.getFarmSize();
                reqUrea = topCrop.getFertilizerNeeds().getUreaPerAcre() * size;
                reqDap = topCrop.getFertilizerNeeds().getDapPerAcre() * size;
                reqMop = topCrop.getFertilizerNeeds().getMopPerAcre() * size;
                grossCost = (reqUrea * 18.50) + (reqDap * 32.00) + (reqMop * 22.00);
                netCost = grossCost * (1.0 - farmer.getFertilizerSubsidyRate());
            }

            String responseJson = String.format(java.util.Locale.US, "{\"farmer\":%s,\"results\":%s,\"reportText\":\"%s\",\"grossFertilizerCost\":%.2f,\"netFertilizerCost\":%.2f,\"ureaKg\":%.2f,\"dapKg\":%.2f,\"mopKg\":%.2f}",
                    farmer.toJson(),
                    resultsJson.toString(),
                    escapeJson(reportText),
                    grossCost, netCost, reqUrea, reqDap, reqMop);

            sendResponse(exchange, 200, responseJson);
        }

        private void handleSimulateWater(HttpExchange exchange) throws IOException {
            // Run simulation and collect its logs dynamically
            String logs = WaterAllocationTest.runTestAndGetLogs();
            String json = String.format("{\"logs\":\"%s\"}", escapeJson(logs));
            sendResponse(exchange, 200, json);
        }

        private void handleGetWeatherAlerts(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            List<String> list = new ArrayList<>(weatherAlerts);
            for (int i = 0; i < list.size(); i++) {
                sb.append("\"").append(escapeJson(list.get(i))).append("\"");
                if (i < list.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String readRequestBody(HttpExchange exchange) throws IOException {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        }

        private Map<String, String> parseJsonMap(String json) {
            Map<String, String> map = new HashMap<>();
            if (json == null || json.trim().isEmpty()) return map;
            json = json.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1);
            }

            boolean inQuotes = false;
            StringBuilder currentKey = new StringBuilder();
            StringBuilder currentValue = new StringBuilder();
            boolean parsingKey = true;

            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                    continue;
                }
                if (!inQuotes) {
                    if (c == ':') {
                        parsingKey = false;
                        continue;
                    }
                    if (c == ',') {
                        map.put(currentKey.toString().trim(), currentValue.toString().trim());
                        currentKey.setLength(0);
                        currentValue.setLength(0);
                        parsingKey = true;
                        continue;
                    }
                }
                if (parsingKey) {
                    currentKey.append(c);
                } else {
                    currentValue.append(c);
                }
            }
            if (currentKey.length() > 0) {
                map.put(currentKey.toString().trim(), currentValue.toString().trim());
            }
            return map;
        }

        private String escapeJson(String str) {
            if (str == null) return "";
            return str.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r");
        }
    }
}

// =========================================================================
// INTERFACES & DOMAIN MODELS
// =========================================================================

interface Cultivable {
    enum SoilType {
        CLAY("Clay"), BLACK("Black"), SANDY("Sandy"), LOAMY("Loamy");
        private final String displayName;
        SoilType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    enum Season {
        SUMMER("Summer"), WINTER("Winter"), SPRING("Spring");
        private final String displayName;
        Season(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }

        public static Season fromString(String str) {
            if (str == null) return SUMMER;
            String clean = str.trim().toUpperCase();
            if (clean.equals("KHARIF") || clean.equals("SUMMER")) return SUMMER;
            if (clean.equals("RABI") || clean.equals("WINTER")) return WINTER;
            if (clean.equals("ZAID") || clean.equals("SPRING")) return SPRING;
            try {
                return Season.valueOf(clean);
            } catch (Exception e) {
                return SUMMER;
            }
        }
    }

    enum WaterLevel {
        LOW("Low"), MEDIUM("Medium"), HIGH("High");
        private final String displayName;
        WaterLevel(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    int calculateSuitabilityScore(SoilType soil, Season season, WaterLevel water);
    double estimateYield(double acres);
}

abstract class Crop implements Cultivable {
    private final String name;
    private final List<SoilType> suitableSoils;
    private final Season suitableSeason;
    private final WaterLevel requiredWaterLevel;
    private final double yieldPerAcre;
    private final FertilizerDetails fertilizerNeeds;
    private final double marketPricePerUnit;
    private final double seedCostPerAcre;

    public static class FertilizerDetails {
        private final double ureaPerAcre;
        private final double dapPerAcre;
        private final double mopPerAcre;

        public FertilizerDetails(double urea, double dap, double mop) {
            this.ureaPerAcre = urea;
            this.dapPerAcre = dap;
            this.mopPerAcre = mop;
        }

        public double getUreaPerAcre() { return ureaPerAcre; }
        public double getDapPerAcre() { return dapPerAcre; }
        public double getMopPerAcre() { return mopPerAcre; }

        @Override
        public String toString() {
            return String.format("Urea: %.1f Kg, DAP: %.1f Kg, MOP: %.1f Kg", ureaPerAcre, dapPerAcre, mopPerAcre);
        }
    }

    public Crop(String name, List<SoilType> suitableSoils, Season suitableSeason,
                WaterLevel requiredWater, double yield, double urea, double dap, double mop,
                double marketPricePerUnit, double seedCostPerAcre) {
        this.name = name;
        this.suitableSoils = suitableSoils;
        this.suitableSeason = suitableSeason;
        this.requiredWaterLevel = requiredWater;
        this.yieldPerAcre = yield;
        this.fertilizerNeeds = new FertilizerDetails(urea, dap, mop);
        this.marketPricePerUnit = marketPricePerUnit;
        this.seedCostPerAcre = seedCostPerAcre;
    }

    public Crop(String name, List<SoilType> suitableSoils, Season suitableSeason,
                WaterLevel requiredWater, double yield, double urea, double dap, double mop) {
        this(name, suitableSoils, suitableSeason, requiredWater, yield, urea, dap, mop, 20000.0, 3000.0);
    }

    public String getName() { return name; }
    public List<SoilType> getSuitableSoils() { return suitableSoils; }
    
    public String getSuitableSoilsString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < suitableSoils.size(); i++) {
            sb.append(suitableSoils.get(i).name());
            if (i < suitableSoils.size() - 1) sb.append(";");
        }
        return sb.toString();
    }

    public Season getSuitableSeason() { return suitableSeason; }
    public WaterLevel getRequiredWaterLevel() { return requiredWaterLevel; }
    public double getYieldPerAcre() { return yieldPerAcre; }
    public FertilizerDetails getFertilizerNeeds() { return fertilizerNeeds; }
    public double getMarketPricePerUnit() { return marketPricePerUnit; }
    public double getSeedCostPerAcre() { return seedCostPerAcre; }
    public abstract String getClassification();

    @Override
    public int calculateSuitabilityScore(SoilType userSoil, Season userSeason, WaterLevel userWater) {
        int score = 0;
        if (suitableSoils.contains(userSoil)) {
            score += 40;
        } else if (isSoilModeratelyCompatible(userSoil)) {
            score += 20;
        }

        if (suitableSeason == userSeason) {
            score += 30;
        }

        if (requiredWaterLevel == userWater) {
            score += 30;
        } else {
            int cropWaterVal = requiredWaterLevel.ordinal();
            int userWaterVal = userWater.ordinal();
            if (userWaterVal > cropWaterVal) {
                score += 20;
            } else if (userWaterVal == cropWaterVal - 1) {
                score += 10;
            }
        }
        return score;
    }

    private boolean isSoilModeratelyCompatible(SoilType userSoil) {
        if (suitableSoils.contains(SoilType.LOAMY) && 
            (userSoil == SoilType.CLAY || userSoil == SoilType.BLACK || userSoil == SoilType.SANDY)) {
            return true;
        }
        if (userSoil == SoilType.LOAMY && 
            (suitableSoils.contains(SoilType.CLAY) || suitableSoils.contains(SoilType.BLACK) || suitableSoils.contains(SoilType.SANDY))) {
            return true;
        }
        if (userSoil == SoilType.BLACK && suitableSoils.contains(SoilType.CLAY)) return true;
        if (userSoil == SoilType.CLAY && suitableSoils.contains(SoilType.BLACK)) return true;
        return false;
    }

    @Override
    public double estimateYield(double acres) {
        return yieldPerAcre * acres;
    }

    public String toJson() {
        String specialField = (this instanceof CashCrop) ? ((CashCrop) this).getTargetIndustry() : ((FoodCrop) this).getFoodCategory();
        String jsonSpecial = (specialField == null) ? "" : specialField.replace("\"", "\\\"");
        
        StringBuilder soilsJson = new StringBuilder("[");
        for (int i = 0; i < suitableSoils.size(); i++) {
            soilsJson.append("\"").append(suitableSoils.get(i).getDisplayName()).append("\"");
            if (i < suitableSoils.size() - 1) soilsJson.append(",");
        }
        soilsJson.append("]");

        return String.format(Locale.US, "{\"name\":\"%s\",\"type\":\"%s\",\"soils\":%s,\"season\":\"%s\",\"water\":\"%s\",\"yield\":%.2f,\"urea\":%.2f,\"dap\":%.2f,\"mop\":%.2f,\"special\":\"%s\",\"classification\":\"%s\",\"marketPrice\":%.2f,\"seedCost\":%.2f}",
                name.replace("\"", "\\\""),
                (this instanceof CashCrop) ? "CashCrop" : "FoodCrop",
                soilsJson.toString(),
                suitableSeason.getDisplayName(),
                requiredWaterLevel.getDisplayName(),
                yieldPerAcre,
                fertilizerNeeds.getUreaPerAcre(),
                fertilizerNeeds.getDapPerAcre(),
                fertilizerNeeds.getMopPerAcre(),
                jsonSpecial,
                getClassification().replace("\"", "\\\""),
                marketPricePerUnit,
                seedCostPerAcre);
    }
}

class FoodCrop extends Crop {
    private final String foodCategory;

    public FoodCrop(String name, List<SoilType> suitableSoils, Season suitableSeason,
                    WaterLevel requiredWater, double yield, double urea, double dap, double mop,
                    String foodCategory, double marketPrice, double seedCost) {
        super(name, suitableSoils, suitableSeason, requiredWater, yield, urea, dap, mop, marketPrice, seedCost);
        this.foodCategory = foodCategory;
    }

    public FoodCrop(String name, List<SoilType> suitableSoils, Season suitableSeason,
                    WaterLevel requiredWater, double yield, double urea, double dap, double mop, String foodCategory) {
        this(name, suitableSoils, suitableSeason, requiredWater, yield, urea, dap, mop, foodCategory, 20000.0, 3000.0);
    }

    public String getFoodCategory() { return foodCategory; }

    @Override
    public String getClassification() {
        return "Food Crop (" + foodCategory + ")";
    }
}

class CashCrop extends Crop {
    private final String targetIndustry;

    public CashCrop(String name, List<SoilType> suitableSoils, Season suitableSeason,
                    WaterLevel requiredWater, double yield, double urea, double dap, double mop,
                    String targetIndustry, double marketPrice, double seedCost) {
        super(name, suitableSoils, suitableSeason, requiredWater, yield, urea, dap, mop, marketPrice, seedCost);
        this.targetIndustry = targetIndustry;
    }

    public CashCrop(String name, List<SoilType> suitableSoils, Season suitableSeason,
                    WaterLevel requiredWater, double yield, double urea, double dap, double mop, String targetIndustry) {
        this(name, suitableSoils, suitableSeason, requiredWater, yield, urea, dap, mop, targetIndustry, 20000.0, 3000.0);
    }

    public String getTargetIndustry() { return targetIndustry; }

    @Override
    public String getClassification() {
        return "Cash Crop (Industry: " + targetIndustry + ")";
    }
}

abstract class Farmer {
    private final String farmerId;
    private final String name;
    private final double farmSize;
    private final Cultivable.SoilType soilType;
    private final Cultivable.WaterLevel waterAccess;
    private final String location;
    private final double latitude;
    private final double longitude;
    private final List<FarmRecord> history;

    public static class FarmRecord {
        private final String cropName;
        private final Date recommendationDate;
        private final int matchScore;
        private final double estimatedYield;

        public FarmRecord(String cropName, int matchScore, double estimatedYield) {
            this.cropName = cropName;
            this.matchScore = matchScore;
            this.estimatedYield = estimatedYield;
            this.recommendationDate = new Date();
        }

        public FarmRecord(String cropName, int matchScore, double estimatedYield, Date recommendationDate) {
            this.cropName = cropName;
            this.matchScore = matchScore;
            this.estimatedYield = estimatedYield;
            this.recommendationDate = recommendationDate;
        }

        public String getCropName() { return cropName; }
        public Date getRecommendationDate() { return recommendationDate; }
        public int getMatchScore() { return matchScore; }
        public double getEstimatedYield() { return estimatedYield; }

        public String toJson() {
            return String.format("{\"cropName\":\"%s\",\"score\":%d,\"yield\":%.2f,\"date\":\"%s\"}",
                    cropName.replace("\"", "\\\""),
                    matchScore,
                    estimatedYield,
                    recommendationDate.toString());
        }
    }

    public Farmer(String farmerId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess,
                  String location, double latitude, double longitude) {
        this.farmerId = farmerId;
        this.name = name;
        this.farmSize = farmSize;
        this.soilType = soilType;
        this.waterAccess = waterAccess;
        this.location = location;
        this.latitude = latitude;
        this.longitude = longitude;
        this.history = new ArrayList<>();
    }

    public Farmer(String farmerId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess) {
        this(farmerId, name, farmSize, soilType, waterAccess, "Punjab, India", 30.90, 75.85);
    }

    public String getFarmerId() { return farmerId; }
    public String getName() { return name; }
    public double getFarmSize() { return farmSize; }
    public Cultivable.SoilType getSoilType() { return soilType; }
    public Cultivable.WaterLevel getWaterAccess() { return waterAccess; }
    public String getLocation() { return location; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public List<FarmRecord> getHistory() { return history; }
    public abstract double getFertilizerSubsidyRate();
    public abstract String getFarmerType();

    public synchronized void addFarmRecord(String cropName, int matchScore, double estimatedYield) {
        this.history.add(new FarmRecord(cropName, matchScore, estimatedYield));
    }

    public String toJson() {
        return String.format(Locale.US, "{\"id\":\"%s\",\"name\":\"%s\",\"acres\":%.2f,\"soil\":\"%s\",\"water\":\"%s\",\"type\":\"%s\",\"subsidyRate\":%.2f,\"location\":\"%s\",\"latitude\":%.4f,\"longitude\":%.4f}",
                farmerId.replace("\"", "\\\""),
                name.replace("\"", "\\\""),
                farmSize,
                soilType.getDisplayName(),
                waterAccess.getDisplayName(),
                getFarmerType().replace("\"", "\\\""),
                getFertilizerSubsidyRate(),
                location.replace("\"", "\\\""),
                latitude,
                longitude);
    }
}

class SmallFarmer extends Farmer {
    public SmallFarmer(String farmerId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess,
                        String location, double latitude, double longitude) {
        super(farmerId, name, farmSize, soilType, waterAccess, location, latitude, longitude);
    }

    public SmallFarmer(String farmerId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess) {
        super(farmerId, name, farmSize, soilType, waterAccess);
    }
    @Override
    public double getFertilizerSubsidyRate() { return 0.30; }
    @Override
    public String getFarmerType() { return "Small Farmer (Subsidized)"; }
}

class LargeFarmer extends Farmer {
    public LargeFarmer(String farmerId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess,
                        String location, double latitude, double longitude) {
        super(farmerId, name, farmSize, soilType, waterAccess, location, latitude, longitude);
    }

    public LargeFarmer(String farmerId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess) {
        super(farmerId, name, farmSize, soilType, waterAccess);
    }
    @Override
    public double getFertilizerSubsidyRate() { return 0.0; }
    @Override
    public String getFarmerType() { return "Large Farmer (Standard)"; }
}

// =========================================================================
// EXCEPTIONS
// =========================================================================

class CropExceptions extends Exception {
    public CropExceptions(String message) { super(message); }

    public static class CropNotFoundException extends CropExceptions {
        public CropNotFoundException(String cropName) {
            super("Crop '" + cropName + "' is not registered in the system database.");
        }
    }

    public static class FarmerNotFoundException extends CropExceptions {
        public FarmerNotFoundException(String farmerId) {
            super("Farmer with ID '" + farmerId + "' was not found in the system registry.");
        }
    }

    public static class SoilIncompatibilityException extends CropExceptions {
        public SoilIncompatibilityException(String cropName, String soilType) {
            super("Crop '" + cropName + "' is highly incompatible with '" + soilType + "' soil.");
        }
    }
}

// =========================================================================
// DATABASE LAYER (JDBC / CSV BACKUP)
// =========================================================================

class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:d:/pbl/java/crop_advisory.db";
    private static final String CROPS_FILE = "d:/pbl/java/crops.txt";
    private static final String FARMERS_FILE = "d:/pbl/java/farmers.txt";
    private static final String ADVISORIES_FILE = "d:/pbl/java/advisories.txt";
    private static boolean jdbcAvailable = false;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
            jdbcAvailable = true;
        } catch (ClassNotFoundException e) {
            System.err.println("JDBC SQLite driver class not found in classpath. Falling back to CSV filesystem persistence.");
            jdbcAvailable = false;
        }
    }

    public static boolean isJdbcAvailable() {
        return jdbcAvailable;
    }

    public static void initialize() {
        if (jdbcAvailable) {
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement()) {

                stmt.execute("CREATE TABLE IF NOT EXISTS crops (" +
                             "name TEXT PRIMARY KEY, " +
                             "type TEXT, " +
                             "soils TEXT, " +
                             "season TEXT, " +
                             "water TEXT, " +
                             "yield DOUBLE, " +
                             "urea DOUBLE, " +
                             "dap DOUBLE, " +
                             "mop DOUBLE, " +
                             "special TEXT, " +
                             "market_price DOUBLE, " +
                             "seed_cost DOUBLE)");

                stmt.execute("CREATE TABLE IF NOT EXISTS farmers (" +
                             "id TEXT PRIMARY KEY, " +
                             "name TEXT, " +
                             "type TEXT, " +
                             "size DOUBLE, " +
                             "soil TEXT, " +
                             "water TEXT, " +
                             "location TEXT, " +
                             "latitude DOUBLE, " +
                             "longitude DOUBLE)");

                stmt.execute("CREATE TABLE IF NOT EXISTS advisories (" +
                             "farmer_id TEXT, " +
                             "crop_name TEXT, " +
                             "score INTEGER, " +
                             "yield DOUBLE, " +
                             "rec_date INTEGER)");

            } catch (SQLException e) {
                System.err.println("JDBC Connection Failed during initialization: " + e.getMessage() + ". Switching to CSV.");
                jdbcAvailable = false;
                initCSVFiles();
            }
        } else {
            initCSVFiles();
        }
    }

    private static void initCSVFiles() {
        try {
            new File(CROPS_FILE).createNewFile();
            new File(FARMERS_FILE).createNewFile();
            new File(ADVISORIES_FILE).createNewFile();
        } catch (IOException e) {
            System.err.println("Error initializing CSV files: " + e.getMessage());
        }
    }

    public static void saveData(List<Crop> crops, List<Farmer> farmers) {
        if (jdbcAvailable) {
            saveDataJDBC(crops, farmers);
        } else {
            saveDataCSV(crops, farmers);
        }
    }

    private static void saveDataJDBC(List<Crop> crops, List<Farmer> farmers) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM crops");
                stmt.execute("DELETE FROM farmers");
                stmt.execute("DELETE FROM advisories");
            }

            String cropSql = "INSERT INTO crops VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(cropSql)) {
                for (Crop c : crops) {
                    pstmt.setString(1, c.getName());
                    pstmt.setString(2, (c instanceof CashCrop) ? "CashCrop" : "FoodCrop");
                    pstmt.setString(3, c.getSuitableSoilsString());
                    pstmt.setString(4, c.getSuitableSeason().name());
                    pstmt.setString(5, c.getRequiredWaterLevel().name());
                    pstmt.setDouble(6, c.getYieldPerAcre());
                    pstmt.setDouble(7, c.getFertilizerNeeds().getUreaPerAcre());
                    pstmt.setDouble(8, c.getFertilizerNeeds().getDapPerAcre());
                    pstmt.setDouble(9, c.getFertilizerNeeds().getMopPerAcre());
                    pstmt.setString(10, (c instanceof CashCrop) ? ((CashCrop) c).getTargetIndustry() : ((FoodCrop) c).getFoodCategory());
                    pstmt.setDouble(11, c.getMarketPricePerUnit());
                    pstmt.setDouble(12, c.getSeedCostPerAcre());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            String farmerSql = "INSERT INTO farmers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(farmerSql)) {
                for (Farmer f : farmers) {
                    pstmt.setString(1, f.getFarmerId());
                    pstmt.setString(2, f.getName());
                    pstmt.setString(3, (f instanceof SmallFarmer) ? "Small" : "Large");
                    pstmt.setDouble(4, f.getFarmSize());
                    pstmt.setString(5, f.getSoilType().name());
                    pstmt.setString(6, f.getWaterAccess().name());
                    pstmt.setString(7, f.getLocation());
                    pstmt.setDouble(8, f.getLatitude());
                    pstmt.setDouble(9, f.getLongitude());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            String advSql = "INSERT INTO advisories VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(advSql)) {
                for (Farmer f : farmers) {
                    for (Farmer.FarmRecord r : f.getHistory()) {
                        pstmt.setString(1, f.getFarmerId());
                        pstmt.setString(2, r.getCropName());
                        pstmt.setInt(3, r.getMatchScore());
                        pstmt.setDouble(4, r.getEstimatedYield());
                        pstmt.setLong(5, r.getRecommendationDate().getTime());
                        pstmt.addBatch();
                    }
                }
                pstmt.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            System.err.println("JDBC Save Error: " + e.getMessage() + ". Defaulting to CSV.");
            saveDataCSV(crops, farmers);
        }
    }

    private static void saveDataCSV(List<Crop> crops, List<Farmer> farmers) {
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(CROPS_FILE)))) {
            for (Crop c : crops) {
                String type = (c instanceof CashCrop) ? "CashCrop" : "FoodCrop";
                String soils = c.getSuitableSoilsString();
                String special = (c instanceof CashCrop) ? ((CashCrop) c).getTargetIndustry() : ((FoodCrop) c).getFoodCategory();
                pw.printf(Locale.US, "%s|%s|%s|%s|%s|%.2f|%.2f|%.2f|%.2f|%s|%.2f|%.2f\n",
                        c.getName(), type, soils, c.getSuitableSeason().name(), c.getRequiredWaterLevel().name(),
                        c.getYieldPerAcre(), c.getFertilizerNeeds().getUreaPerAcre(),
                        c.getFertilizerNeeds().getDapPerAcre(), c.getFertilizerNeeds().getMopPerAcre(), special,
                        c.getMarketPricePerUnit(), c.getSeedCostPerAcre());
            }
        } catch (IOException e) {
            System.err.println("Error saving crops CSV: " + e.getMessage());
        }

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(FARMERS_FILE)))) {
            for (Farmer f : farmers) {
                String type = (f instanceof SmallFarmer) ? "Small" : "Large";
                pw.printf(Locale.US, "%s|%s|%s|%.2f|%s|%s|%s|%.4f|%.4f\n",
                        f.getFarmerId(), f.getName(), type, f.getFarmSize(), f.getSoilType().name(), f.getWaterAccess().name(),
                        f.getLocation(), f.getLatitude(), f.getLongitude());
            }
        } catch (IOException e) {
            System.err.println("Error saving farmers CSV: " + e.getMessage());
        }

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(ADVISORIES_FILE)))) {
            for (Farmer f : farmers) {
                for (Farmer.FarmRecord r : f.getHistory()) {
                    pw.printf(Locale.US, "%s|%s|%d|%.2f|%d\n",
                            f.getFarmerId(), r.getCropName(), r.getMatchScore(), r.getEstimatedYield(), r.getRecommendationDate().getTime());
                }
            }
        } catch (IOException e) {
            System.err.println("Error saving advisories CSV: " + e.getMessage());
        }
    }

    public static Map<String, Crop> loadCrops() {
        if (jdbcAvailable) {
            return loadCropsJDBC();
        } else {
            return loadCropsCSV();
        }
    }

    private static Map<String, Crop> loadCropsJDBC() {
        Map<String, Crop> crops = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM crops")) {

            while (rs.next()) {
                String name = rs.getString("name");
                String type = rs.getString("type");
                String soilsStr = rs.getString("soils");
                String seasonStr = rs.getString("season");
                String waterStr = rs.getString("water");
                double yield = rs.getDouble("yield");
                double urea = rs.getDouble("urea");
                double dap = rs.getDouble("dap");
                double mop = rs.getDouble("mop");
                String special = rs.getString("special");
                double marketPrice = 20000.0;
                double seedCost = 3000.0;
                try { marketPrice = rs.getDouble("market_price"); } catch (Exception ignored) {}
                try { seedCost = rs.getDouble("seed_cost"); } catch (Exception ignored) {}

                List<Cultivable.SoilType> soils = parseSoils(soilsStr);
                Cultivable.Season season = Cultivable.Season.fromString(seasonStr);
                Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(waterStr);

                Crop c;
                if ("CashCrop".equalsIgnoreCase(type)) {
                    c = new CashCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost);
                } else {
                    c = new FoodCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost);
                }
                if (name != null) crops.put(name, c);
            }
        } catch (SQLException e) {
            System.err.println("JDBC Load Crops Error: " + e.getMessage() + ". Fallback to CSV.");
            return loadCropsCSV();
        }
        return crops;
    }

    private static Map<String, Crop> loadCropsCSV() {
        Map<String, Crop> crops = new HashMap<>();
        File file = new File(CROPS_FILE);
        if (!file.exists()) return crops;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 10) continue;

                String name = parts[0];
                String type = parts[1];
                String soilsStr = parts[2];
                Cultivable.Season season = Cultivable.Season.fromString(parts[3]);
                Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(parts[4]);
                double yield = Double.parseDouble(parts[5]);
                double urea = Double.parseDouble(parts[6]);
                double dap = Double.parseDouble(parts[7]);
                double mop = Double.parseDouble(parts[8]);
                String special = parts[9];
                double marketPrice = (parts.length >= 11) ? Double.parseDouble(parts[10]) : 20000.0;
                double seedCost = (parts.length >= 12) ? Double.parseDouble(parts[11]) : 3000.0;

                List<Cultivable.SoilType> soils = parseSoils(soilsStr);

                Crop c;
                if ("CashCrop".equalsIgnoreCase(type)) {
                    c = new CashCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost);
                } else {
                    c = new FoodCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost);
                }
                if (name != null) crops.put(name, c);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("CSV Load Crops Error: " + e.getMessage());
        }
        return crops;
    }

    public static Map<String, Farmer> loadFarmers() {
        if (jdbcAvailable) {
            return loadFarmersJDBC();
        } else {
            return loadFarmersCSV();
        }
    }

    private static Map<String, Farmer> loadFarmersJDBC() {
        Map<String, Farmer> farmers = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM farmers")) {

            while (rs.next()) {
                String id = rs.getString("id");
                String name = rs.getString("name");
                String type = rs.getString("type");
                double size = rs.getDouble("size");
                Cultivable.SoilType soil = Cultivable.SoilType.valueOf(rs.getString("soil"));
                Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(rs.getString("water"));
                String location = "Punjab, India";
                double latitude = 30.90;
                double longitude = 75.85;
                try { location = rs.getString("location"); } catch (Exception ignored) {}
                try { latitude = rs.getDouble("latitude"); } catch (Exception ignored) {}
                try { longitude = rs.getDouble("longitude"); } catch (Exception ignored) {}

                Farmer f;
                if ("Small".equalsIgnoreCase(type)) {
                    f = new SmallFarmer(id, name, size, soil, water, location, latitude, longitude);
                } else {
                    f = new LargeFarmer(id, name, size, soil, water, location, latitude, longitude);
                }
                if (id != null) farmers.put(id, f);
            }

            try (Statement histStmt = conn.createStatement();
                 ResultSet hrs = histStmt.executeQuery("SELECT * FROM advisories")) {
                while (hrs.next()) {
                    String fid = hrs.getString("farmer_id");
                    String cname = hrs.getString("crop_name");
                    int score = hrs.getInt("score");
                    double yield = hrs.getDouble("yield");
                    long rDate = hrs.getLong("rec_date");

                    Farmer f = farmers.get(fid);
                    if (f != null) {
                        f.getHistory().add(new Farmer.FarmRecord(cname, score, yield, new Date(rDate)));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("JDBC Load Farmers Error: " + e.getMessage() + ". Loading from CSV.");
            return loadFarmersCSV();
        }
        return farmers;
    }

    private static Map<String, Farmer> loadFarmersCSV() {
        Map<String, Farmer> farmers = new HashMap<>();
        File file = new File(FARMERS_FILE);
        if (!file.exists()) return farmers;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 6) continue;

                String id = parts[0];
                String name = parts[1];
                String type = parts[2];
                double size = Double.parseDouble(parts[3]);
                Cultivable.SoilType soil = Cultivable.SoilType.valueOf(parts[4]);
                Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(parts[5]);
                String location = (parts.length >= 7) ? parts[6] : "Punjab, India";
                double latitude = (parts.length >= 8) ? Double.parseDouble(parts[7]) : 30.90;
                double longitude = (parts.length >= 9) ? Double.parseDouble(parts[8]) : 75.85;

                Farmer f;
                if ("Small".equalsIgnoreCase(type)) {
                    f = new SmallFarmer(id, name, size, soil, water, location, latitude, longitude);
                } else {
                    f = new LargeFarmer(id, name, size, soil, water, location, latitude, longitude);
                }
                if (id != null) farmers.put(id, f);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("CSV Load Farmers Error: " + e.getMessage());
        }

        File hFile = new File(ADVISORIES_FILE);
        if (!hFile.exists()) return farmers;

        try (BufferedReader br = new BufferedReader(new FileReader(hFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 5) continue;

                String fId = parts[0];
                String cname = parts[1];
                int score = Integer.parseInt(parts[2]);
                double yield = Double.parseDouble(parts[3]);
                long rDate = Long.parseLong(parts[4]);

                Farmer f = farmers.get(fId);
                if (f != null) {
                    f.getHistory().add(new Farmer.FarmRecord(cname, score, yield, new Date(rDate)));
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("CSV Load Advisories Error: " + e.getMessage());
        }

        return farmers;
    }

    private static List<Cultivable.SoilType> parseSoils(String soilsStr) {
        List<Cultivable.SoilType> list = new ArrayList<>();
        String[] parts = soilsStr.split(";");
        for (String p : parts) {
            try {
                list.add(Cultivable.SoilType.valueOf(p.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Ignore invalid values
            }
        }
        return list;
    }
}

// =========================================================================
// SERVICES LAYER
// =========================================================================

class CropService {
    private final Map<String, Crop> cropCatalog;
    private final Map<String, Farmer> farmers;

    public static class AdvisoryResult {
        private final Crop crop;
        private final int score;

        public AdvisoryResult(Crop crop, int score) {
            this.crop = crop;
            this.score = score;
        }

        public Crop getCrop() { return crop; }
        public int getScore() { return score; }
    }

    public CropService() {
        this.cropCatalog = new ConcurrentHashMap<>();
        this.farmers = new ConcurrentHashMap<>();
    }

    public void loadData() {
        DatabaseManager.initialize();
        Map<String, Crop> loadedCrops = DatabaseManager.loadCrops();
        Map<String, Farmer> loadedFarmers = DatabaseManager.loadFarmers();

        this.cropCatalog.clear();
        this.cropCatalog.putAll(loadedCrops);

        this.farmers.clear();
        this.farmers.putAll(loadedFarmers);

        if (this.cropCatalog.isEmpty()) {
            addCrop(new FoodCrop("Paddy", Arrays.asList(Cultivable.SoilType.CLAY, Cultivable.SoilType.LOAMY), 
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.HIGH, 22.0, 50.0, 30.0, 20.0, "Cereal Grain", 23000.0, 3000.0));
            addCrop(new FoodCrop("Wheat", Arrays.asList(Cultivable.SoilType.LOAMY, Cultivable.SoilType.CLAY), 
                    Cultivable.Season.WINTER, Cultivable.WaterLevel.MEDIUM, 18.0, 60.0, 40.0, 25.0, "Cereal Grain", 22750.0, 2500.0));
            addCrop(new CashCrop("Cotton", Arrays.asList(Cultivable.SoilType.BLACK, Cultivable.SoilType.LOAMY), 
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.MEDIUM, 10.0, 45.0, 25.0, 15.0, "Textile Fiber", 71210.0, 4500.0));
            addCrop(new CashCrop("Groundnut", Arrays.asList(Cultivable.SoilType.SANDY, Cultivable.SoilType.LOAMY), 
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.LOW, 8.0, 15.0, 30.0, 20.0, "Oilseed", 67800.0, 5000.0));
            addCrop(new FoodCrop("Watermelon", Arrays.asList(Cultivable.SoilType.SANDY), 
                    Cultivable.Season.SPRING, Cultivable.WaterLevel.LOW, 150.0, 40.0, 20.0, 30.0, "Fruit", 12000.0, 6000.0));
            addCrop(new FoodCrop("Maize", Arrays.asList(Cultivable.SoilType.LOAMY, Cultivable.SoilType.BLACK), 
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.MEDIUM, 20.0, 55.0, 35.0, 20.0, "Coarse Cereal", 22250.0, 2800.0));
        }

        if (this.farmers.isEmpty()) {
            registerFarmer(new SmallFarmer("FM-101", "Ramesh Kumar", 3.5, Cultivable.SoilType.CLAY, Cultivable.WaterLevel.HIGH, "Ludhiana, Punjab", 30.90, 75.85));
            registerFarmer(new SmallFarmer("FM-102", "Sita Devi", 1.8, Cultivable.SoilType.SANDY, Cultivable.WaterLevel.LOW, "Jaipur, Rajasthan", 26.91, 75.78));
            registerFarmer(new LargeFarmer("FM-201", "Sardar Baldev", 12.5, Cultivable.SoilType.LOAMY, Cultivable.WaterLevel.MEDIUM, "Karnal, Haryana", 29.68, 76.99));
        }
    }

    public synchronized void saveData() {
        DatabaseManager.saveData(
            new ArrayList<>(cropCatalog.values()),
            new ArrayList<>(farmers.values())
        );
    }

    public synchronized void addCrop(Crop crop) {
        cropCatalog.put(crop.getName(), crop);
        saveData();
    }

    public synchronized void registerFarmer(Farmer farmer) {
        farmers.put(farmer.getFarmerId(), farmer);
        saveData();
    }

    public Map<String, Crop> getCropCatalog() { return cropCatalog; }
    public Map<String, Farmer> getFarmers() { return farmers; }

    public List<String> getApplicableSchemes(Farmer farmer, Crop crop) {
        List<String> list = new ArrayList<>();
        if (farmer.getFarmSize() <= 5.0) {
            list.add("pmkisan");
            list.add("smam");
        }
        list.add("pmfby");
        if (crop instanceof FoodCrop) {
            list.add("nfsm");
        } else {
            list.add("pkvy");
        }
        return list;
    }

    public synchronized List<AdvisoryResult> generateAdvisory(String farmerId, Cultivable.Season currentSeason) 
            throws CropExceptions.FarmerNotFoundException {
        
        Farmer farmer = farmers.get(farmerId);
        if (farmer == null) {
            throw new CropExceptions.FarmerNotFoundException(farmerId);
        }

        List<AdvisoryResult> results = new ArrayList<>();
        for (Crop crop : cropCatalog.values()) {
            int score = crop.calculateSuitabilityScore(farmer.getSoilType(), currentSeason, farmer.getWaterAccess());
            results.add(new AdvisoryResult(crop, score));
        }

        results.sort(Comparator.comparingInt(AdvisoryResult::getScore).reversed());

        if (!results.isEmpty()) {
            AdvisoryResult top = results.get(0);
            double estimatedYield = top.getCrop().estimateYield(farmer.getFarmSize());
            farmer.addFarmRecord(top.getCrop().getName(), top.getScore(), estimatedYield);
            saveData();
        }

        return results;
    }

    public String generateAdvisoryReport(Farmer farmer, List<AdvisoryResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================================================================\n");
        sb.append("                          AGRICULTURAL ADVISORY RECOMMENDATIONS\n");
        sb.append("========================================================================================\n");
        sb.append("Farmer ID  : ").append(farmer.getFarmerId()).append("\n");
        sb.append("Name       : ").append(farmer.getName()).append(" (").append(farmer.getFarmerType()).append(")\n");
        sb.append("Farm Area  : ").append(String.format("%.2f Acres", farmer.getFarmSize())).append("\n");
        sb.append("Conditions : Soil: ").append(farmer.getSoilType().getDisplayName())
                  .append(" | Water Access: ").append(farmer.getWaterAccess().getDisplayName()).append("\n");
        sb.append("----------------------------------------------------------------------------------------\n");
        sb.append(String.format("%-4s | %-12s | %-32s | %-12s | %-12s\n", "Rank", "Crop Name", "Classification", "Season Match", "Suitability"));
        sb.append("----------------------------------------------------------------------------------------\n");

        int rank = 1;
        for (AdvisoryResult res : results) {
            Crop c = res.getCrop();
            sb.append(String.format("%-4d | %-12s | %-32s | %-12s | %6d%%\n",
                    rank++,
                    c.getName(),
                    truncate(c.getClassification(), 32),
                    c.getSuitableSeason().getDisplayName(),
                    res.getScore()));
        }
        sb.append("----------------------------------------------------------------------------------------\n");

        if (!results.isEmpty()) {
            AdvisoryResult topResult = results.get(0);
            Crop topCrop = topResult.getCrop();
            double size = farmer.getFarmSize();

            double estYield = topCrop.estimateYield(size);
            double reqUrea = topCrop.getFertilizerNeeds().getUreaPerAcre() * size;
            double reqDap = topCrop.getFertilizerNeeds().getDapPerAcre() * size;
            double reqMop = topCrop.getFertilizerNeeds().getMopPerAcre() * size;

            double priceUrea = 18.50; 
            double priceDap = 32.00;  
            double priceMop = 22.00;  

            double totalUreaCost = reqUrea * priceUrea;
            double totalDapCost = reqDap * priceDap;
            double totalMopCost = reqMop * priceMop;
            double grossCost = totalUreaCost + totalDapCost + totalMopCost;
            
            double subsidyRate = farmer.getFertilizerSubsidyRate();
            double subsidySavings = grossCost * subsidyRate;
            double netCost = grossCost - subsidySavings;

            sb.append("\n========================================================================================\n");
            sb.append("              DETAILED FARMING SPECIFICATIONS FOR TOP MATCH: ").append(topCrop.getName().toUpperCase()).append("\n");
            sb.append("========================================================================================\n");
            sb.append(String.format("Expected Total Yield    : %.2f Quintals (Avg %.1f Qt/Ac)\n\n", estYield, topCrop.getYieldPerAcre()));
            sb.append(String.format("Fertilizer Quantities required (for %.2f Acres):\n", size));
            sb.append(String.format("  - Urea (Nitrogen source) : %8.2f Kg (Cost: INR %8.2f @ INR %.1f/Kg)\n", reqUrea, totalUreaCost, priceUrea));
            sb.append(String.format("  - DAP (Phosphorus source): %8.2f Kg (Cost: INR %8.2f @ INR %.1f/Kg)\n", reqDap, totalDapCost, priceDap));
            sb.append(String.format("  - MOP (Potash source)    : %8.2f Kg (Cost: INR %8.2f @ INR %.1f/Kg)\n", reqMop, totalMopCost, priceMop));
            sb.append("----------------------------------------------------------------------------------------\n");
            sb.append(String.format("Gross Fertilizer Cost    : INR %8.2f\n", grossCost));
            if (subsidyRate > 0) {
                sb.append(String.format("Subsidy Applied          : -INR %8.2f (Subsidy rate: %.0f%%)\n", subsidySavings, subsidyRate * 100));
            } else {
                sb.append("Subsidy Applied          : INR 0.00 (Standard commercial rates)\n");
            }
            sb.append(String.format("NET ESTIMATED COST       : INR %8.2f\n", netCost));
            sb.append("========================================================================================\n");
        }

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}

// =========================================================================
// THREADS & CONCURRENCY LAYER
// =========================================================================

class WeatherAlertDaemon extends Thread {
    private final long monitorIntervalMs;
    private final Random random;

    private static final String[] WEATHER_ALERTS = {
        "HEATWAVE WARNING: High evapotranspiration risk. Small farmers should check drip irrigation lines.",
        "HEAVY PRECIPITATION ALERT: Flooding risk in clay/black soil profiles. Clear drainage networks.",
        "PEST ACTIVITY REPORT: Rising localized aphid counts. Inspect cash crops (especially Cotton).",
        "UNSEASONAL FROST ALERT: Cover young watermelon saplings in sandy soil beds.",
        "OPTIMAL WEATHER DETECTED: Ideal solar radiation and moisture levels for ongoing harvest."
    };

    public WeatherAlertDaemon(long monitorIntervalMs) {
        this.monitorIntervalMs = monitorIntervalMs;
        this.random = new Random();
        this.setDaemon(true); 
        this.setName("Agri-Weather-Alert-Daemon");
    }

    @Override
    public void run() {
        try {
            while (true) {
                Thread.sleep(monitorIntervalMs);
                String alertMessage = WEATHER_ALERTS[random.nextInt(WEATHER_ALERTS.length)];
                CropAdvisoryApp.addWeatherAlert(alertMessage);
            }
        } catch (InterruptedException e) {
            // Exit silently
        }
    }
}

class WaterAllocationTest {
    private static int availableReservoirWaterKL = 50;

    public static synchronized boolean allocateIrrigation(String farmerName, int requestedKL, List<String> logs) {
        if (availableReservoirWaterKL >= requestedKL) {
            availableReservoirWaterKL -= requestedKL;
            logs.add(String.format(" [GRANTED] %-10s allocated %d KL. Reservoir level: %d KL remaining.",
                    farmerName, requestedKL, availableReservoirWaterKL));
            return true;
        } else {
            logs.add(String.format(" [DENIED]  %-10s requested %d KL. Insufficient supply (Remaining: %d KL).",
                    farmerName, requestedKL, availableReservoirWaterKL));
            return false;
        }
    }

    public static String runTestAndGetLogs() {
        availableReservoirWaterKL = 50;
        List<String> logs = new CopyOnWriteArrayList<>();

        logs.add("========================================================================");
        logs.add("                 CONCURRENT WATER ALLOCATION SIMULATION");
        logs.add("========================================================================");
        logs.add("Scenario: 5 farmers request 15 KL of irrigation water simultaneously.");
        logs.add("Reservoir Limit: 50 KL. Only 3 requests can be fully satisfied (total 45 KL).");
        logs.add("------------------------------------------------------------------------");
        logs.add("Opening irrigation threads...");

        int threadCount = 5;
        Thread[] threads = new Thread[threadCount];
        CountDownLatch startGate = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            final String farmerName = "Farmer-" + (i + 1);
            threads[i] = new Thread(() -> {
                try {
                    startGate.await(); 
                    allocateIrrigation(farmerName, 15, logs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Irrigation-Channel-" + (i + 1));
        }

        for (Thread t : threads) {
            t.start();
        }

        try {
            Thread.sleep(300); 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logs.add("Opening canal sluice gates... SIMULATING CONCURRENT DEMAND:");
        logs.add("------------------------------------------------------------------------");
        startGate.countDown(); // Fire them simultaneously

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logs.add("------------------------------------------------------------------------");
        logs.add("Water allocation simulation complete.");
        logs.add("========================================================================");

        // Join logs with newline characters
        StringBuilder sb = new StringBuilder();
        for (String log : logs) {
            sb.append(log).append("\n");
        }
        return sb.toString();
    }
}
