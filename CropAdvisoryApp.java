import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.Date;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;

/**
 * Smart Crop Advisory & Resource Management System (Tamil Nadu Edition)
 * Single-file Core Java Backend using com.sun.net.httpserver.HttpServer,
 * SQLite JDBC with automatic CSV fallback, and Session-based Authentication.
 */
public class CropAdvisoryApp {

    private static final int PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
    private static CropService cropService;
    private static final Queue<String> weatherAlerts = new ConcurrentLinkedQueue<>();
    private static final int MAX_WEATHER_ALERTS = 30;

    // In-memory active session tokens (token -> SessionInfo)
    private static final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();
    private static final long SESSION_EXPIRY_MS = 24 * 60 * 60 * 1000L; // 24 Hours

    // Tamil Nadu 15 Districts Data
    public static class TNDistrict {
        public final String name;
        public final double lat;
        public final double lon;
        public final Cultivable.SoilType defaultSoil;

        public TNDistrict(String name, double lat, double lon, Cultivable.SoilType defaultSoil) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.defaultSoil = defaultSoil;
        }
    }

    public static final Map<String, TNDistrict> TN_DISTRICTS = new LinkedHashMap<>();
    static {
        TN_DISTRICTS.put("Thanjavur", new TNDistrict("Thanjavur", 10.7870, 79.1378, Cultivable.SoilType.ALLUVIAL));
        TN_DISTRICTS.put("Coimbatore", new TNDistrict("Coimbatore", 11.0168, 76.9558, Cultivable.SoilType.RED));
        TN_DISTRICTS.put("Madurai", new TNDistrict("Madurai", 9.9252, 78.1198, Cultivable.SoilType.BLACK));
        TN_DISTRICTS.put("Salem", new TNDistrict("Salem", 11.6643, 78.1460, Cultivable.SoilType.LOAMY));
        TN_DISTRICTS.put("Tiruchirappalli", new TNDistrict("Tiruchirappalli", 10.7905, 78.7047, Cultivable.SoilType.ALLUVIAL));
        TN_DISTRICTS.put("Tirunelveli", new TNDistrict("Tirunelveli", 8.7139, 77.7567, Cultivable.SoilType.RED));
        TN_DISTRICTS.put("Erode", new TNDistrict("Erode", 11.3410, 77.7172, Cultivable.SoilType.RED));
        TN_DISTRICTS.put("Vellore", new TNDistrict("Vellore", 12.9165, 79.1325, Cultivable.SoilType.LOAMY));
        TN_DISTRICTS.put("Cuddalore", new TNDistrict("Cuddalore", 11.7480, 79.7714, Cultivable.SoilType.ALLUVIAL));
        TN_DISTRICTS.put("Kanchipuram", new TNDistrict("Kanchipuram", 12.8342, 79.7036, Cultivable.SoilType.CLAY));
        TN_DISTRICTS.put("Dindigul", new TNDistrict("Dindigul", 10.3673, 77.9803, Cultivable.SoilType.RED));
        TN_DISTRICTS.put("Nagapattinam", new TNDistrict("Nagapattinam", 10.7656, 79.8424, Cultivable.SoilType.ALLUVIAL));
        TN_DISTRICTS.put("Ramanathapuram", new TNDistrict("Ramanathapuram", 9.3639, 78.8395, Cultivable.SoilType.SANDY));
        TN_DISTRICTS.put("Dharmapuri", new TNDistrict("Dharmapuri", 12.1211, 78.1582, Cultivable.SoilType.RED));
        TN_DISTRICTS.put("Theni", new TNDistrict("Theni", 10.0104, 77.4768, Cultivable.SoilType.LOAMY));
    }

    public static void main(String[] args) {
        System.out.println("Starting Tamil Nadu Smart Crop Advisory Backend Server...");

        // 1. Initialize Database & Seed Data
        cropService = new CropService();
        cropService.loadData();

        // 2. Start Weather Daemon Thread
        WeatherAlertDaemon weatherDaemon = new WeatherAlertDaemon(120000); // Check weather every 2 minutes
        weatherDaemon.start();

        // 3. Start HTTP Server
        int activePort = PORT;
        HttpServer server = null;
        try {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", activePort), 0);
            } catch (java.net.BindException be) {
                activePort = (activePort == 8080) ? 8081 : activePort + 1;
                System.out.println("Port occupied. Falling back to port " + activePort + "...");
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", activePort), 0);
            }
            
            // Serve API endpoints
            server.createContext("/api", new ApiHandler());
            
            // Serve static frontend files
            server.createContext("/", new StaticFileHandler("web"));

            server.setExecutor(Executors.newFixedThreadPool(12));
            server.start();

            System.out.println("==========================================================");
            System.out.println("   TAMIL NADU SMART CROP ADVISORY APP IS LIVE!");
            System.out.println("   Navigate in browser to: http://localhost:" + activePort);
            System.out.println("   Database Engine: " + (DatabaseManager.isJdbcAvailable() ? "SQLite (JDBC)" : "Flat File (CSV Backup)"));
            System.out.println("==========================================================");

            try {
                Thread.currentThread().join();
            } catch (InterruptedException ignored) {}

        } catch (IOException e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Auth Session Management
    public static SessionInfo createSession(User user) {
        String token = UUID.randomUUID().toString();
        SessionInfo info = new SessionInfo(token, user.getId(), user.getUsername(), user.getRole(), System.currentTimeMillis() + SESSION_EXPIRY_MS);
        activeSessions.put(token, info);
        return info;
    }

    public static SessionInfo getSession(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        SessionInfo info = activeSessions.get(token.trim());
        if (info != null) {
            if (System.currentTimeMillis() > info.getExpiryTime()) {
                activeSessions.remove(token);
                return null;
            }
        }
        return info;
    }

    public static void removeSession(String token) {
        if (token != null) activeSessions.remove(token.trim());
    }

    public static void addWeatherAlert(String alert) {
        if (weatherAlerts.size() >= MAX_WEATHER_ALERTS) {
            weatherAlerts.poll();
        }
        weatherAlerts.add(alert);
    }

    public static Queue<String> getWeatherAlerts() {
        return weatherAlerts;
    }

    // Password Hashing (Salted SHA-256)
    public static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return bytesToHex(salt);
    }

    public static String hashPin(String pin, String saltHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(hexToBytes(saltHex));
            byte[] hashed = md.digest(pin.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashed);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 Hashing failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    // Open-Meteo Weather Fetcher
    public static String fetchOpenMeteoWeather(double lat, double lon) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .build();
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto",
                    lat, lon);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception ignored) {
        }
        // Graceful fallback weather telemetry if offline or network error
        return String.format(Locale.US,
                "{\"current\":{\"temperature_2m\":31.5,\"relative_humidity_2m\":68,\"weather_code\":2,\"wind_speed_10m\":14.5},\"daily\":{\"time\":[\"2026-10-04\",\"2026-10-05\",\"2026-10-06\",\"2026-10-07\",\"2026-10-08\"],\"weather_code\":[1,2,3,61,2],\"temperature_2m_max\":[33.0,34.2,32.5,29.8,31.0],\"temperature_2m_min\":[24.0,24.5,23.8,22.0,23.5],\"precipitation_probability_max\":[20,15,45,75,30]}}");
    }

    // =========================================================================
    // HTTP SERVER HANDLERS
    // =========================================================================

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
                file = new File("web", path);
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
            if (path.endsWith(".html")) mimeType = "text/html; charset=utf-8";
            else if (path.endsWith(".css")) mimeType = "text/css; charset=utf-8";
            else if (path.endsWith(".js")) mimeType = "application/javascript; charset=utf-8";
            else if (path.endsWith(".json")) mimeType = "application/json; charset=utf-8";
            else if (path.endsWith(".png")) mimeType = "image/png";
            else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) mimeType = "image/jpeg";

            exchange.getResponseHeaders().set("Content-Type", mimeType);
            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase();

            try {
                // Public Endpoints
                if (path.equals("/api/status") && method.equals("GET")) {
                    handleStatus(exchange);
                } else if (path.equals("/api/auth/register") && method.equals("POST")) {
                    handleRegister(exchange);
                } else if (path.equals("/api/auth/login") && method.equals("POST")) {
                    handleLogin(exchange);
                } else if (path.equals("/api/districts") && method.equals("GET")) {
                    handleGetDistricts(exchange);
                } else if (path.equals("/api/alerts") && method.equals("GET")) {
                    handleGetAlerts(exchange);
                } else if (path.equals("/api/weather") && method.equals("GET")) {
                    handleGetWeather(exchange);
                } else {
                    // Protected Endpoints requiring Token Verification
                    SessionInfo session = authenticate(exchange);
                    if (session == null) {
                        sendResponse(exchange, 401, "{\"error\":\"Unauthorized. Please sign in to access personalized features.\"}");
                        return;
                    }

                    if (path.equals("/api/auth/logout") && method.equals("POST")) {
                        handleLogout(exchange, session);
                    } else if (path.equals("/api/me") && method.equals("GET")) {
                        handleGetMe(exchange, session);
                    } else if (path.equals("/api/me") && method.equals("PUT")) {
                        handleUpdateMe(exchange, session);
                    } else if (path.equals("/api/advisory") && method.equals("POST")) {
                        handleRunAdvisory(exchange, session);
                    } else if (path.equals("/api/advisories") && method.equals("GET")) {
                        handleGetAdvisoryHistory(exchange, session);
                    } else if (path.startsWith("/api/advisories/") && method.equals("GET")) {
                        handleGetAdvisoryById(exchange, session);
                    } else if (path.equals("/api/water/simulate") && method.equals("POST")) {
                        handleSimulateWater(exchange);
                    } else if (path.equals("/api/crops") && method.equals("GET")) {
                        handleGetCrops(exchange);
                    } else if ((path.equals("/api/crops") || path.equals("/api/crops/save")) && (method.equals("POST") || method.equals("PUT"))) {
                        requireAdmin(session);
                        handleSaveCrop(exchange);
                    } else if (path.equals("/api/farmers") && method.equals("GET")) {
                        requireAdmin(session);
                        handleGetFarmers(exchange);
                    } else {
                        sendResponse(exchange, 404, "{\"error\":\"API Route Not Found\"}");
                    }
                }
            } catch (CropExceptions.UnauthorizedException e) {
                sendResponse(exchange, 403, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error: " + escapeJson(e.getMessage()) + "\"}");
            }
        }

        private SessionInfo authenticate(HttpExchange exchange) {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();
                return CropAdvisoryApp.getSession(token);
            }
            // Check query param fallback
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] p = param.split("=");
                    if (p.length == 2 && p[0].equals("token")) {
                        return CropAdvisoryApp.getSession(p[1]);
                    }
                }
            }
            return null;
        }

        private void requireAdmin(SessionInfo session) throws CropExceptions.UnauthorizedException {
            if (session == null || !"ADMIN".equalsIgnoreCase(session.getRole())) {
                throw new CropExceptions.UnauthorizedException("Admin privilege required for this action.");
            }
        }

        // Endpoint Handlers
        private void handleStatus(HttpExchange exchange) throws IOException {
            String json = String.format("{\"jdbcActive\":%b,\"cropsCount\":%d,\"farmersCount\":%d,\"usersCount\":%d}",
                    DatabaseManager.isJdbcAvailable(),
                    cropService.getCropCatalog().size(),
                    cropService.getFarmers().size(),
                    cropService.getUsers().size());
            sendResponse(exchange, 200, json);
        }

        private void handleGetDistricts(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (TNDistrict d : TN_DISTRICTS.values()) {
                sb.append(String.format(Locale.US, "{\"name\":\"%s\",\"lat\":%.4f,\"lon\":%.4f,\"defaultSoil\":\"%s\"}",
                        escapeJson(d.name), d.lat, d.lon, d.defaultSoil.name()));
                if (++i < TN_DISTRICTS.size()) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleRegister(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            String username = params.get("username");
            if (username == null || username.trim().isEmpty()) username = params.get("mobile");
            String pin = params.get("pin");
            String name = params.get("name");
            String lang = params.getOrDefault("language", "en");

            if (username == null || username.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Username or Mobile Number is required.\"}");
                return;
            }
            if (pin == null || pin.trim().length() < 4 || pin.trim().length() > 6 || !pin.trim().matches("\\d+")) {
                sendResponse(exchange, 400, "{\"error\":\"PIN must be a 4 to 6 digit number.\"}");
                return;
            }
            if (name == null || name.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Farmer Full Name is required.\"}");
                return;
            }

            username = username.trim().toLowerCase();
            if (cropService.getUserByUsername(username) != null) {
                sendResponse(exchange, 400, "{\"error\":\"Username or Mobile Number is already registered. Please sign in.\"}");
                return;
            }

            String userId = "USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String salt = generateSalt();
            String pinHash = hashPin(pin.trim(), salt);
            String role = "FARMER";

            User user = new User(userId, username, pinHash, salt, role, lang, System.currentTimeMillis());
            cropService.registerUser(user);

            // Create initial placeholder Farmer linked to user
            String farmerId = "FM-" + (1000 + (int)(Math.random() * 9000));
            Farmer farmer = new SmallFarmer(farmerId, userId, name.trim(), 0.0, Cultivable.SoilType.ALLUVIAL,
                    Cultivable.WaterLevel.MEDIUM, "Thanjavur", "Canal", "Thanjavur, Tamil Nadu", 10.7870, 79.1378);
            cropService.registerFarmer(farmer);

            SessionInfo session = createSession(user);

            String json = String.format(Locale.US, "{\"success\":true,\"token\":\"%s\",\"user\":%s,\"farmer\":%s}",
                    session.getToken(), user.toJson(), farmer.toJson());
            sendResponse(exchange, 200, json);
        }

        private void handleLogin(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            String username = params.get("username");
            String pin = params.get("pin");

            if (username == null || username.trim().isEmpty() || pin == null || pin.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Username/Mobile and PIN are required.\"}");
                return;
            }

            username = username.trim().toLowerCase();
            User user = cropService.getUserByUsername(username);
            if (user == null) {
                sendResponse(exchange, 400, "{\"error\":\"Account not found. Please check your username or Sign Up.\"}");
                return;
            }

            String expectedHash = hashPin(pin.trim(), user.getSalt());
            if (!expectedHash.equalsIgnoreCase(user.getPinHash())) {
                sendResponse(exchange, 401, "{\"error\":\"Invalid PIN. Please try again.\"}");
                return;
            }

            SessionInfo session = createSession(user);
            Farmer farmer = cropService.getFarmerByUserId(user.getId());

            String json = String.format(Locale.US, "{\"success\":true,\"token\":\"%s\",\"user\":%s,\"farmer\":%s}",
                    session.getToken(), user.toJson(), (farmer != null ? farmer.toJson() : "null"));
            sendResponse(exchange, 200, json);
        }

        private void handleLogout(HttpExchange exchange, SessionInfo session) throws IOException {
            removeSession(session.getToken());
            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"Logged out successfully.\"}");
        }

        private void handleGetMe(HttpExchange exchange, SessionInfo session) throws IOException {
            User user = cropService.getUserById(session.getUserId());
            if (user == null) {
                sendResponse(exchange, 404, "{\"error\":\"User profile not found.\"}");
                return;
            }
            Farmer farmer = cropService.getFarmerByUserId(user.getId());

            String json = String.format(Locale.US, "{\"user\":%s,\"farmer\":%s}",
                    user.toJson(), (farmer != null ? farmer.toJson() : "null"));
            sendResponse(exchange, 200, json);
        }

        private void handleUpdateMe(HttpExchange exchange, SessionInfo session) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            User user = cropService.getUserById(session.getUserId());
            if (user == null) {
                sendResponse(exchange, 404, "{\"error\":\"User not found.\"}");
                return;
            }

            if (params.containsKey("language")) {
                user.setLanguage(params.get("language"));
                cropService.saveData();
            }

            String name = params.getOrDefault("name", user.getUsername());
            String district = params.getOrDefault("district", "Thanjavur");
            String soilStr = params.getOrDefault("soil", "ALLUVIAL");
            String waterStr = params.getOrDefault("water", "MEDIUM");
            String source = params.getOrDefault("irrigationSource", "Canal");
            double acres = 3.5;
            try { if (params.get("acres") != null) acres = Double.parseDouble(params.get("acres")); } catch (Exception ignored) {}

            Cultivable.SoilType soil = Cultivable.SoilType.ALLUVIAL;
            try { soil = Cultivable.SoilType.valueOf(soilStr.trim().toUpperCase()); } catch (Exception ignored) {}

            Cultivable.WaterLevel water = Cultivable.WaterLevel.MEDIUM;
            try { water = Cultivable.WaterLevel.valueOf(waterStr.trim().toUpperCase()); } catch (Exception ignored) {}

            TNDistrict tnDist = TN_DISTRICTS.getOrDefault(district, TN_DISTRICTS.get("Thanjavur"));

            Farmer existing = cropService.getFarmerByUserId(user.getId());
            String farmerId = (existing != null) ? existing.getFarmerId() : "FM-" + (1000 + (int)(Math.random() * 9000));

            Farmer updatedFarmer;
            if (acres <= 5.0) {
                updatedFarmer = new SmallFarmer(farmerId, user.getId(), name, acres, soil, water, district, source,
                        district + ", Tamil Nadu", tnDist.lat, tnDist.lon);
            } else {
                updatedFarmer = new LargeFarmer(farmerId, user.getId(), name, acres, soil, water, district, source,
                        district + ", Tamil Nadu", tnDist.lat, tnDist.lon);
            }

            if (existing != null) {
                updatedFarmer.getHistory().addAll(existing.getHistory());
            }

            cropService.registerFarmer(updatedFarmer);

            String json = String.format(Locale.US, "{\"success\":true,\"user\":%s,\"farmer\":%s}",
                    user.toJson(), updatedFarmer.toJson());
            sendResponse(exchange, 200, json);
        }

        private void handleRunAdvisory(HttpExchange exchange, SessionInfo session) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            Farmer farmer = cropService.getFarmerByUserId(session.getUserId());
            if (farmer == null || farmer.getFarmSize() <= 0) {
                sendResponse(exchange, 400, "{\"error\":\"Please complete your Farm Profile Setup first before getting an advisory.\"}");
                return;
            }

            String seasonStr = params.getOrDefault("season", "SUMMER");
            Cultivable.Season season = Cultivable.Season.fromString(seasonStr);

            // Fetch Live Weather for farmer's district to feed into advisory engine
            TNDistrict dist = TN_DISTRICTS.getOrDefault(farmer.getDistrict(), TN_DISTRICTS.get("Thanjavur"));
            String weatherJson = fetchOpenMeteoWeather(dist.lat, dist.lon);

            AdvisoryReportFull report = cropService.generateFullAdvisory(farmer, season, weatherJson);

            sendResponse(exchange, 200, report.toJson());
        }

        private void handleGetAdvisoryHistory(HttpExchange exchange, SessionInfo session) throws IOException {
            Farmer farmer = cropService.getFarmerByUserId(session.getUserId());
            if (farmer == null) {
                sendResponse(exchange, 200, "[]");
                return;
            }

            List<Farmer.FarmRecord> records = farmer.getHistory();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < records.size(); i++) {
                sb.append(records.get(i).toJson());
                if (i < records.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleGetAdvisoryById(HttpExchange exchange, SessionInfo session) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String recordId = path.substring("/api/advisories/".length()).trim();

            Farmer farmer = cropService.getFarmerByUserId(session.getUserId());
            if (farmer == null) {
                sendResponse(exchange, 404, "{\"error\":\"Farmer record not found.\"}");
                return;
            }

            Farmer.FarmRecord record = null;
            for (Farmer.FarmRecord r : farmer.getHistory()) {
                if (r.getId().equals(recordId)) {
                    record = r;
                    break;
                }
            }

            if (record == null) {
                sendResponse(exchange, 404, "{\"error\":\"Advisory record not found.\"}");
                return;
            }

            sendResponse(exchange, 200, record.toJson());
        }

        private void handleGetWeather(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            double lat = 10.7870; // Thanjavur default
            double lon = 79.1378;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        try {
                            if (pair[0].equals("lat")) lat = Double.parseDouble(pair[1]);
                            if (pair[0].equals("lon")) lon = Double.parseDouble(pair[1]);
                            if (pair[0].equals("district") && TN_DISTRICTS.containsKey(pair[1])) {
                                TNDistrict d = TN_DISTRICTS.get(pair[1]);
                                lat = d.lat;
                                lon = d.lon;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            String json = fetchOpenMeteoWeather(lat, lon);
            sendResponse(exchange, 200, json);
        }

        private void handleGetAlerts(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String district = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && pair[0].equals("district")) {
                        district = pair[1].trim();
                    }
                }
            }

            List<String> allAlerts = new ArrayList<>(weatherAlerts);
            if (allAlerts.isEmpty()) {
                allAlerts.add("Thanjavur: OPTIMAL WEATHER - Ideal conditions for Paddy Kuruvai season.");
                allAlerts.add("Coimbatore: TEMPERATURE ALERT - Temps exceeding 36C in western corridor.");
                allAlerts.add("Madurai: RAIN FORECAST - Moderate convective showers expected in 48h.");
            }

            if (district != null && !district.isEmpty()) {
                final String targetDist = district.toLowerCase();
                allAlerts.sort((a, b) -> {
                    boolean aMatches = a.toLowerCase().contains(targetDist);
                    boolean bMatches = b.toLowerCase().contains(targetDist);
                    if (aMatches && !bMatches) return -1;
                    if (!aMatches && bMatches) return 1;
                    return 0;
                });
            }

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < allAlerts.size(); i++) {
                sb.append("\"").append(escapeJson(allAlerts.get(i))).append("\"");
                if (i < allAlerts.size() - 1) sb.append(",");
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleSimulateWater(HttpExchange exchange) throws IOException {
            String logs = WaterAllocationTest.runTestAndGetLogs();
            String json = String.format("{\"logs\":\"%s\"}", escapeJson(logs));
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

        private void handleSaveCrop(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            Map<String, String> params = parseJsonMap(body);

            String name = params.get("name");
            String type = params.get("type");
            String soilsStr = params.get("soils");
            String seasonStr = params.get("season");
            String waterStr = params.get("water");
            double yield = Double.parseDouble(params.getOrDefault("yield", "20"));
            double urea = Double.parseDouble(params.getOrDefault("urea", "40"));
            double dap = Double.parseDouble(params.getOrDefault("dap", "25"));
            double mop = Double.parseDouble(params.getOrDefault("mop", "20"));
            double price = Double.parseDouble(params.getOrDefault("marketPrice", "2000"));
            double seed = Double.parseDouble(params.getOrDefault("seedCost", "3000"));
            double labor = Double.parseDouble(params.getOrDefault("laborCost", "6000"));
            String special = params.getOrDefault("special", "General Crop");

            if (name == null || name.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Crop name is required.\"}");
                return;
            }

            List<Cultivable.SoilType> soils = new ArrayList<>();
            for (String s : soilsStr.split(";")) {
                if (!s.trim().isEmpty()) {
                    try { soils.add(Cultivable.SoilType.valueOf(s.trim().toUpperCase())); } catch (Exception ignored) {}
                }
            }
            if (soils.isEmpty()) soils.add(Cultivable.SoilType.LOAMY);

            Cultivable.Season season = Cultivable.Season.fromString(seasonStr);
            Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(waterStr.toUpperCase());

            Crop crop;
            if ("CashCrop".equalsIgnoreCase(type)) {
                crop = new CashCrop(name, soils, season, water, yield, urea, dap, mop, special, price, seed, labor);
            } else {
                crop = new FoodCrop(name, soils, season, water, yield, urea, dap, mop, special, price, seed, labor);
            }

            cropService.addCrop(crop);
            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"Crop saved successfully.\"}");
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

        // Helper Methods
        private void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
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
// SESSION & USER MODELS
// =========================================================================

class User {
    private final String id;
    private final String username;
    private final String pinHash;
    private final String salt;
    private final String role; // FARMER, ADMIN
    private String language;
    private final long createdAt;

    public User(String id, String username, String pinHash, String salt, String role, String language, long createdAt) {
        this.id = id;
        this.username = username;
        this.pinHash = pinHash;
        this.salt = salt;
        this.role = role;
        this.language = language;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPinHash() { return pinHash; }
    public String getSalt() { return salt; }
    public String getRole() { return role; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public long getCreatedAt() { return createdAt; }

    public String toJson() {
        return String.format("{\"id\":\"%s\",\"username\":\"%s\",\"role\":\"%s\",\"language\":\"%s\",\"createdAt\":%d}",
                id, username.replace("\"", "\\\""), role, language, createdAt);
    }
}

class SessionInfo {
    private final String token;
    private final String userId;
    private final String username;
    private final String role;
    private final long expiryTime;

    public SessionInfo(String token, String userId, String username, String role, long expiryTime) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.expiryTime = expiryTime;
    }

    public String getToken() { return token; }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public long getExpiryTime() { return expiryTime; }
}

// =========================================================================
// DOMAIN MODELS (CULTIVABLE, CROP, FARMER, ENUMS)
// =========================================================================

interface Cultivable {
    enum SoilType {
        CLAY("Clay Soil"), BLACK("Black Soil"), SANDY("Sandy Soil"), LOAMY("Loamy Soil"), RED("Red Soil"), ALLUVIAL("Alluvial Soil");
        private final String displayName;
        SoilType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    enum Season {
        SUMMER("Kuruvai (Summer / Kharif)"), WINTER("Samba (Winter / Rabi)"), SPRING("Navarai (Spring / Zaid)");
        private final String displayName;
        Season(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }

        public static Season fromString(String str) {
            if (str == null) return SUMMER;
            String clean = str.trim().toUpperCase();
            if (clean.contains("KURUVAI") || clean.contains("SUMMER") || clean.contains("KHARIF")) return SUMMER;
            if (clean.contains("SAMBA") || clean.contains("WINTER") || clean.contains("RABI")) return WINTER;
            if (clean.contains("NAVARAI") || clean.contains("SPRING") || clean.contains("ZAID")) return SPRING;
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
    private final double laborCostPerAcre;

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
                double marketPricePerUnit, double seedCostPerAcre, double laborCostPerAcre) {
        this.name = name;
        this.suitableSoils = suitableSoils;
        this.suitableSeason = suitableSeason;
        this.requiredWaterLevel = requiredWater;
        this.yieldPerAcre = yield;
        this.fertilizerNeeds = new FertilizerDetails(urea, dap, mop);
        this.marketPricePerUnit = marketPricePerUnit;
        this.seedCostPerAcre = seedCostPerAcre;
        this.laborCostPerAcre = laborCostPerAcre;
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
    public double getLaborCostPerAcre() { return laborCostPerAcre; }
    public abstract String getClassification();

    @Override
    public int calculateSuitabilityScore(SoilType userSoil, Season userSeason, WaterLevel userWater) {
        int score = 0;
        // Soil Scoring (Max 40 points)
        if (suitableSoils.contains(userSoil)) {
            score += 40;
        } else if (isSoilModeratelyCompatible(userSoil)) {
            score += 20;
        }

        // Season Scoring (Max 30 points)
        if (suitableSeason == userSeason) {
            score += 30;
        }

        // Water Scoring (Max 30 points)
        if (requiredWaterLevel == userWater) {
            score += 30;
        } else {
            int cropWaterVal = requiredWaterLevel.ordinal();
            int userWaterVal = userWater.ordinal();
            if (userWaterVal > cropWaterVal) {
                score += 20; // Surplus water access
            } else if (userWaterVal == cropWaterVal - 1) {
                score += 10; // 1 level short
            } else {
                score += 0;  // 2 levels short
            }
        }
        return score;
    }

    public boolean isSoilModeratelyCompatible(SoilType userSoil) {
        if (suitableSoils.contains(SoilType.LOAMY)) {
            return true; // Loamy is versatile and compatible with all soil types
        }
        if (userSoil == SoilType.LOAMY) return true;
        if (userSoil == SoilType.ALLUVIAL && (suitableSoils.contains(SoilType.CLAY) || suitableSoils.contains(SoilType.LOAMY))) return true;
        if (userSoil == SoilType.CLAY && (suitableSoils.contains(SoilType.ALLUVIAL) || suitableSoils.contains(SoilType.BLACK))) return true;
        if (userSoil == SoilType.BLACK && suitableSoils.contains(SoilType.CLAY)) return true;
        if (userSoil == SoilType.RED && (suitableSoils.contains(SoilType.SANDY) || suitableSoils.contains(SoilType.LOAMY))) return true;
        if (userSoil == SoilType.SANDY && suitableSoils.contains(SoilType.RED)) return true;
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
            soilsJson.append("\"").append(suitableSoils.get(i).name()).append("\"");
            if (i < suitableSoils.size() - 1) soilsJson.append(",");
        }
        soilsJson.append("]");

        return String.format(Locale.US, "{\"name\":\"%s\",\"type\":\"%s\",\"soils\":%s,\"season\":\"%s\",\"water\":\"%s\",\"yield\":%.2f,\"urea\":%.2f,\"dap\":%.2f,\"mop\":%.2f,\"special\":\"%s\",\"classification\":\"%s\",\"marketPrice\":%.2f,\"seedCost\":%.2f,\"laborCost\":%.2f}",
                name.replace("\"", "\\\""),
                (this instanceof CashCrop) ? "CashCrop" : "FoodCrop",
                soilsJson.toString(),
                suitableSeason.name(),
                requiredWaterLevel.name(),
                yieldPerAcre,
                fertilizerNeeds.getUreaPerAcre(),
                fertilizerNeeds.getDapPerAcre(),
                fertilizerNeeds.getMopPerAcre(),
                jsonSpecial,
                getClassification().replace("\"", "\\\""),
                marketPricePerUnit,
                seedCostPerAcre,
                laborCostPerAcre);
    }
}

class FoodCrop extends Crop {
    private final String foodCategory;

    public FoodCrop(String name, List<SoilType> suitableSoils, Season suitableSeason,
                    WaterLevel requiredWater, double yield, double urea, double dap, double mop,
                    String foodCategory, double marketPrice, double seedCost, double laborCost) {
        super(name, suitableSoils, suitableSeason, requiredWater, yield, urea, dap, mop, marketPrice, seedCost, laborCost);
        this.foodCategory = foodCategory;
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
                    String targetIndustry, double marketPrice, double seedCost, double laborCost) {
        super(name, suitableSoils, suitableSeason, requiredWater, yield, urea, dap, mop, marketPrice, seedCost, laborCost);
        this.targetIndustry = targetIndustry;
    }

    public String getTargetIndustry() { return targetIndustry; }

    @Override
    public String getClassification() {
        return "Cash Crop (Industry: " + targetIndustry + ")";
    }
}

abstract class Farmer {
    private final String farmerId;
    private final String userId;
    private final String name;
    private final double farmSize;
    private final Cultivable.SoilType soilType;
    private final Cultivable.WaterLevel waterAccess;
    private final String district;
    private final String irrigationSource;
    private final String location;
    private final double latitude;
    private final double longitude;
    private final List<FarmRecord> history;

    public static class FarmRecord {
        private final String id;
        private final String cropName;
        private final String season;
        private final int matchScore;
        private final double estimatedYield;
        private final double grossCost;
        private final double netCost;
        private final double revenue;
        private final double profit;
        private final double roi;
        private final String adviceNotes;
        private final Date recommendationDate;

        public FarmRecord(String id, String cropName, String season, int matchScore, double estimatedYield,
                          double grossCost, double netCost, double revenue, double profit, double roi,
                          String adviceNotes, Date recommendationDate) {
            this.id = (id != null) ? id : "ADV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            this.cropName = cropName;
            this.season = season;
            this.matchScore = matchScore;
            this.estimatedYield = estimatedYield;
            this.grossCost = grossCost;
            this.netCost = netCost;
            this.revenue = revenue;
            this.profit = profit;
            this.roi = roi;
            this.adviceNotes = adviceNotes;
            this.recommendationDate = (recommendationDate != null) ? recommendationDate : new Date();
        }

        public String getId() { return id; }
        public String getCropName() { return cropName; }
        public String getSeason() { return season; }
        public int getMatchScore() { return matchScore; }
        public double getEstimatedYield() { return estimatedYield; }
        public double getGrossCost() { return grossCost; }
        public double getNetCost() { return netCost; }
        public double getRevenue() { return revenue; }
        public double getProfit() { return profit; }
        public double getRoi() { return roi; }
        public String getAdviceNotes() { return adviceNotes; }
        public Date getRecommendationDate() { return recommendationDate; }

        public String toJson() {
            return String.format(Locale.US, "{\"id\":\"%s\",\"cropName\":\"%s\",\"season\":\"%s\",\"score\":%d,\"yield\":%.2f,\"grossCost\":%.2f,\"netCost\":%.2f,\"revenue\":%.2f,\"profit\":%.2f,\"roi\":%.2f,\"notes\":\"%s\",\"date\":%d}",
                    id,
                    cropName.replace("\"", "\\\""),
                    season.replace("\"", "\\\""),
                    matchScore,
                    estimatedYield,
                    grossCost, netCost, revenue, profit, roi,
                    (adviceNotes != null ? adviceNotes.replace("\"", "\\\"").replace("\n", "\\n") : ""),
                    recommendationDate.getTime());
        }
    }

    public Farmer(String farmerId, String userId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess,
                  String district, String irrigationSource, String location, double latitude, double longitude) {
        this.farmerId = farmerId;
        this.userId = userId;
        this.name = name;
        this.farmSize = farmSize;
        this.soilType = soilType;
        this.waterAccess = waterAccess;
        this.district = (district != null) ? district : "Thanjavur";
        this.irrigationSource = (irrigationSource != null) ? irrigationSource : "Canal";
        this.location = (location != null) ? location : this.district + ", Tamil Nadu";
        this.latitude = latitude;
        this.longitude = longitude;
        this.history = new ArrayList<>();
    }

    public String getFarmerId() { return farmerId; }
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public double getFarmSize() { return farmSize; }
    public Cultivable.SoilType getSoilType() { return soilType; }
    public Cultivable.WaterLevel getWaterAccess() { return waterAccess; }
    public String getDistrict() { return district; }
    public String getIrrigationSource() { return irrigationSource; }
    public String getLocation() { return location; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public List<FarmRecord> getHistory() { return history; }
    public abstract double getFertilizerSubsidyRate();
    public abstract String getFarmerType();

    public synchronized void addFarmRecord(FarmRecord record) {
        this.history.add(0, record); // Add latest first
    }

    public String toJson() {
        return String.format(Locale.US, "{\"id\":\"%s\",\"userId\":\"%s\",\"name\":\"%s\",\"acres\":%.2f,\"soil\":\"%s\",\"water\":\"%s\",\"district\":\"%s\",\"irrigationSource\":\"%s\",\"type\":\"%s\",\"subsidyRate\":%.2f,\"location\":\"%s\",\"latitude\":%.4f,\"longitude\":%.4f}",
                farmerId.replace("\"", "\\\""),
                (userId != null ? userId : "").replace("\"", "\\\""),
                name.replace("\"", "\\\""),
                farmSize,
                soilType.name(),
                waterAccess.name(),
                district.replace("\"", "\\\""),
                irrigationSource.replace("\"", "\\\""),
                getFarmerType().replace("\"", "\\\""),
                getFertilizerSubsidyRate(),
                location.replace("\"", "\\\""),
                latitude,
                longitude);
    }
}

class SmallFarmer extends Farmer {
    public SmallFarmer(String farmerId, String userId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess,
                        String district, String irrigationSource, String location, double latitude, double longitude) {
        super(farmerId, userId, name, farmSize, soilType, waterAccess, district, irrigationSource, location, latitude, longitude);
    }

    @Override
    public double getFertilizerSubsidyRate() { return 0.30; } // 30% NPK Subsidy
    @Override
    public String getFarmerType() { return "Small Farmer (<= 5 Acres, 30% Subsidy)"; }
}

class LargeFarmer extends Farmer {
    public LargeFarmer(String farmerId, String userId, String name, double farmSize, Cultivable.SoilType soilType, Cultivable.WaterLevel waterAccess,
                        String district, String irrigationSource, String location, double latitude, double longitude) {
        super(farmerId, userId, name, farmSize, soilType, waterAccess, district, irrigationSource, location, latitude, longitude);
    }

    @Override
    public double getFertilizerSubsidyRate() { return 0.0; } // 0% Subsidy
    @Override
    public String getFarmerType() { return "Commercial Farmer (> 5 Acres, Standard Rates)"; }
}

// =========================================================================
// EXCEPTIONS
// =========================================================================

class CropExceptions extends Exception {
    public CropExceptions(String message) { super(message); }

    public static class UnauthorizedException extends CropExceptions {
        public UnauthorizedException(String msg) { super(msg); }
    }

    public static class CropNotFoundException extends CropExceptions {
        public CropNotFoundException(String cropName) {
            super("Crop '" + cropName + "' is not registered in the system catalog.");
        }
    }

    public static class FarmerNotFoundException extends CropExceptions {
        public FarmerNotFoundException(String farmerId) {
            super("Farmer with ID '" + farmerId + "' was not found.");
        }
    }
}

// =========================================================================
// DATABASE LAYER (JDBC / CSV BACKUP)
// =========================================================================

class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:crop_advisory.db";
    private static final String USERS_FILE = "users.txt";
    private static final String FARMERS_FILE = "farmers.txt";
    private static final String CROPS_FILE = "crops.txt";
    private static final String ADVISORIES_FILE = "advisories.txt";
    private static boolean jdbcAvailable = false;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
            jdbcAvailable = true;
        } catch (ClassNotFoundException e) {
            System.err.println("JDBC SQLite driver class not found. Using CSV files.");
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

                stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                             "id TEXT PRIMARY KEY, " +
                             "username TEXT UNIQUE, " +
                             "pin_hash TEXT, " +
                             "salt TEXT, " +
                             "role TEXT, " +
                             "language TEXT, " +
                             "created_at INTEGER)");

                stmt.execute("CREATE TABLE IF NOT EXISTS farmers (" +
                             "id TEXT PRIMARY KEY, " +
                             "user_id TEXT, " +
                             "name TEXT, " +
                             "type TEXT, " +
                             "size DOUBLE, " +
                             "soil TEXT, " +
                             "water TEXT, " +
                             "district TEXT, " +
                             "irrigation_source TEXT, " +
                             "location TEXT, " +
                             "latitude DOUBLE, " +
                             "longitude DOUBLE)");

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
                             "seed_cost DOUBLE, " +
                             "labor_cost DOUBLE)");

                stmt.execute("CREATE TABLE IF NOT EXISTS advisories (" +
                             "id TEXT PRIMARY KEY, " +
                             "farmer_id TEXT, " +
                             "crop_name TEXT, " +
                             "season TEXT, " +
                             "score INTEGER, " +
                             "yield DOUBLE, " +
                             "gross_cost DOUBLE, " +
                             "net_cost DOUBLE, " +
                             "revenue DOUBLE, " +
                             "profit DOUBLE, " +
                             "roi DOUBLE, " +
                             "advice_notes TEXT, " +
                             "rec_date INTEGER)");

                // Migration checks for any missing columns in older database schemas
                safeAddColumn(conn, "farmers", "user_id", "TEXT");
                safeAddColumn(conn, "farmers", "district", "TEXT");
                safeAddColumn(conn, "farmers", "irrigation_source", "TEXT");
                safeAddColumn(conn, "crops", "labor_cost", "DOUBLE");
                safeAddColumn(conn, "advisories", "id", "TEXT");
                safeAddColumn(conn, "advisories", "season", "TEXT");
                safeAddColumn(conn, "advisories", "gross_cost", "DOUBLE");
                safeAddColumn(conn, "advisories", "net_cost", "DOUBLE");
                safeAddColumn(conn, "advisories", "revenue", "DOUBLE");
                safeAddColumn(conn, "advisories", "profit", "DOUBLE");
                safeAddColumn(conn, "advisories", "roi", "DOUBLE");
                safeAddColumn(conn, "advisories", "advice_notes", "TEXT");

            } catch (SQLException e) {
                System.err.println("JDBC Init Error: " + e.getMessage() + ". Switching to CSV.");
                jdbcAvailable = false;
                initCSVFiles();
            }
        } else {
            initCSVFiles();
        }
    }

    private static void safeAddColumn(Connection conn, String table, String column, String type) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException ignored) {
            // Column already exists or error ignored
        }
    }

    private static void initCSVFiles() {
        try {
            new File(USERS_FILE).createNewFile();
            new File(FARMERS_FILE).createNewFile();
            new File(CROPS_FILE).createNewFile();
            new File(ADVISORIES_FILE).createNewFile();
        } catch (IOException e) {
            System.err.println("Error creating CSV files: " + e.getMessage());
        }
    }

    public static void saveData(List<User> users, List<Crop> crops, List<Farmer> farmers) {
        if (jdbcAvailable) {
            saveDataJDBC(users, crops, farmers);
        } else {
            saveDataCSV(users, crops, farmers);
        }
    }

    private static void saveDataJDBC(List<User> users, List<Crop> crops, List<Farmer> farmers) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM users");
                stmt.execute("DELETE FROM crops");
                stmt.execute("DELETE FROM farmers");
                stmt.execute("DELETE FROM advisories");
            }

            String userSql = "INSERT INTO users VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(userSql)) {
                for (User u : users) {
                    pstmt.setString(1, u.getId());
                    pstmt.setString(2, u.getUsername());
                    pstmt.setString(3, u.getPinHash());
                    pstmt.setString(4, u.getSalt());
                    pstmt.setString(5, u.getRole());
                    pstmt.setString(6, u.getLanguage());
                    pstmt.setLong(7, u.getCreatedAt());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            String cropSql = "INSERT INTO crops VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
                    pstmt.setDouble(13, c.getLaborCostPerAcre());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            String farmerSql = "INSERT INTO farmers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(farmerSql)) {
                for (Farmer f : farmers) {
                    pstmt.setString(1, f.getFarmerId());
                    pstmt.setString(2, f.getUserId());
                    pstmt.setString(3, f.getName());
                    pstmt.setString(4, (f instanceof SmallFarmer) ? "Small" : "Large");
                    pstmt.setDouble(5, f.getFarmSize());
                    pstmt.setString(6, f.getSoilType().name());
                    pstmt.setString(7, f.getWaterAccess().name());
                    pstmt.setString(8, f.getDistrict());
                    pstmt.setString(9, f.getIrrigationSource());
                    pstmt.setString(10, f.getLocation());
                    pstmt.setDouble(11, f.getLatitude());
                    pstmt.setDouble(12, f.getLongitude());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            String advSql = "INSERT INTO advisories VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(advSql)) {
                for (Farmer f : farmers) {
                    for (Farmer.FarmRecord r : f.getHistory()) {
                        pstmt.setString(1, r.getId());
                        pstmt.setString(2, f.getFarmerId());
                        pstmt.setString(3, r.getCropName());
                        pstmt.setString(4, r.getSeason());
                        pstmt.setInt(5, r.getMatchScore());
                        pstmt.setDouble(6, r.getEstimatedYield());
                        pstmt.setDouble(7, r.getGrossCost());
                        pstmt.setDouble(8, r.getNetCost());
                        pstmt.setDouble(9, r.getRevenue());
                        pstmt.setDouble(10, r.getProfit());
                        pstmt.setDouble(11, r.getRoi());
                        pstmt.setString(12, r.getAdviceNotes());
                        pstmt.setLong(13, r.getRecommendationDate().getTime());
                        pstmt.addBatch();
                    }
                }
                pstmt.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            System.err.println("JDBC Save Error: " + e.getMessage() + ". Defaulting to CSV.");
            saveDataCSV(users, crops, farmers);
        }
    }

    private static void saveDataCSV(List<User> users, List<Crop> crops, List<Farmer> farmers) {
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(USERS_FILE)))) {
            for (User u : users) {
                pw.printf(Locale.US, "%s|%s|%s|%s|%s|%s|%d\n",
                        u.getId(), u.getUsername(), u.getPinHash(), u.getSalt(), u.getRole(), u.getLanguage(), u.getCreatedAt());
            }
        } catch (IOException e) {
            System.err.println("Error saving users CSV: " + e.getMessage());
        }

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(CROPS_FILE)))) {
            for (Crop c : crops) {
                String type = (c instanceof CashCrop) ? "CashCrop" : "FoodCrop";
                String special = (c instanceof CashCrop) ? ((CashCrop) c).getTargetIndustry() : ((FoodCrop) c).getFoodCategory();
                pw.printf(Locale.US, "%s|%s|%s|%s|%s|%.2f|%.2f|%.2f|%.2f|%s|%.2f|%.2f|%.2f\n",
                        c.getName(), type, c.getSuitableSoilsString(), c.getSuitableSeason().name(), c.getRequiredWaterLevel().name(),
                        c.getYieldPerAcre(), c.getFertilizerNeeds().getUreaPerAcre(),
                        c.getFertilizerNeeds().getDapPerAcre(), c.getFertilizerNeeds().getMopPerAcre(), special,
                        c.getMarketPricePerUnit(), c.getSeedCostPerAcre(), c.getLaborCostPerAcre());
            }
        } catch (IOException e) {
            System.err.println("Error saving crops CSV: " + e.getMessage());
        }

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(FARMERS_FILE)))) {
            for (Farmer f : farmers) {
                String type = (f instanceof SmallFarmer) ? "Small" : "Large";
                pw.printf(Locale.US, "%s|%s|%s|%s|%.2f|%s|%s|%s|%s|%s|%.4f|%.4f\n",
                        f.getFarmerId(), f.getUserId(), f.getName(), type, f.getFarmSize(), f.getSoilType().name(), f.getWaterAccess().name(),
                        f.getDistrict(), f.getIrrigationSource(), f.getLocation(), f.getLatitude(), f.getLongitude());
            }
        } catch (IOException e) {
            System.err.println("Error saving farmers CSV: " + e.getMessage());
        }

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(ADVISORIES_FILE)))) {
            for (Farmer f : farmers) {
                for (Farmer.FarmRecord r : f.getHistory()) {
                    String cleanNotes = (r.getAdviceNotes() != null) ? r.getAdviceNotes().replace("\n", " ") : "";
                    pw.printf(Locale.US, "%s|%s|%s|%s|%d|%.2f|%.2f|%.2f|%.2f|%.2f|%.2f|%s|%d\n",
                            r.getId(), f.getFarmerId(), r.getCropName(), r.getSeason(), r.getMatchScore(), r.getEstimatedYield(),
                            r.getGrossCost(), r.getNetCost(), r.getRevenue(), r.getProfit(), r.getRoi(), cleanNotes, r.getRecommendationDate().getTime());
                }
            }
        } catch (IOException e) {
            System.err.println("Error saving advisories CSV: " + e.getMessage());
        }
    }

    public static Map<String, User> loadUsers() {
        Map<String, User> users = new HashMap<>();
        if (jdbcAvailable) {
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
                while (rs.next()) {
                    User u = new User(rs.getString("id"), rs.getString("username"), rs.getString("pin_hash"),
                            rs.getString("salt"), rs.getString("role"), rs.getString("language"), rs.getLong("created_at"));
                    users.put(u.getId(), u);
                }
                return users;
            } catch (SQLException e) {
                System.err.println("JDBC Load Users Error: " + e.getMessage() + ". Fallback to CSV.");
            }
        }
        File file = new File(USERS_FILE);
        if (!file.exists()) return users;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|");
                if (parts.length >= 7) {
                    User u = new User(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], Long.parseLong(parts[6]));
                    users.put(u.getId(), u);
                }
            }
        } catch (Exception ignored) {}
        return users;
    }

    public static Map<String, Crop> loadCrops() {
        Map<String, Crop> crops = new HashMap<>();
        if (jdbcAvailable) {
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
                    double marketPrice = rs.getDouble("market_price");
                    double seedCost = rs.getDouble("seed_cost");
                    double laborCost = 6000.0;
                    try { laborCost = rs.getDouble("labor_cost"); } catch (Exception ignored) {}

                    List<Cultivable.SoilType> soils = parseSoils(soilsStr);
                    Cultivable.Season season = Cultivable.Season.fromString(seasonStr);
                    Cultivable.WaterLevel water = Cultivable.WaterLevel.valueOf(waterStr);

                    Crop c;
                    if ("CashCrop".equalsIgnoreCase(type)) {
                        c = new CashCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost, laborCost);
                    } else {
                        c = new FoodCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost, laborCost);
                    }
                    crops.put(name, c);
                }
                return crops;
            } catch (SQLException e) {
                System.err.println("JDBC Load Crops Error: " + e.getMessage() + ". Fallback to CSV.");
            }
        }

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
                double marketPrice = (parts.length >= 11) ? Double.parseDouble(parts[10]) : 2000.0;
                double seedCost = (parts.length >= 12) ? Double.parseDouble(parts[11]) : 3000.0;
                double laborCost = (parts.length >= 13) ? Double.parseDouble(parts[12]) : 6000.0;

                List<Cultivable.SoilType> soils = parseSoils(soilsStr);

                Crop c;
                if ("CashCrop".equalsIgnoreCase(type)) {
                    c = new CashCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost, laborCost);
                } else {
                    c = new FoodCrop(name, soils, season, water, yield, urea, dap, mop, special, marketPrice, seedCost, laborCost);
                }
                crops.put(name, c);
            }
        } catch (Exception ignored) {}
        return crops;
    }

    public static Map<String, Farmer> loadFarmers() {
        Map<String, Farmer> farmers = new HashMap<>();
        if (jdbcAvailable) {
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM farmers")) {

                while (rs.next()) {
                    String id = rs.getString("id");
                    String userId = rs.getString("user_id");
                    String name = rs.getString("name");
                    String type = rs.getString("type");
                    double size = rs.getDouble("size");
                    Cultivable.SoilType soil = Cultivable.SoilType.ALLUVIAL;
                    try { soil = Cultivable.SoilType.valueOf(rs.getString("soil").trim().toUpperCase()); } catch (Exception ignored) {}

                    Cultivable.WaterLevel water = Cultivable.WaterLevel.MEDIUM;
                    try { water = Cultivable.WaterLevel.valueOf(rs.getString("water").trim().toUpperCase()); } catch (Exception ignored) {}
                    String district = rs.getString("district");
                    String source = rs.getString("irrigation_source");
                    String location = rs.getString("location");
                    double lat = rs.getDouble("latitude");
                    double lon = rs.getDouble("longitude");

                    Farmer f;
                    if ("Small".equalsIgnoreCase(type)) {
                        f = new SmallFarmer(id, userId, name, size, soil, water, district, source, location, lat, lon);
                    } else {
                        f = new LargeFarmer(id, userId, name, size, soil, water, district, source, location, lat, lon);
                    }
                    farmers.put(id, f);
                }

                try (Statement histStmt = conn.createStatement();
                     ResultSet hrs = histStmt.executeQuery("SELECT * FROM advisories")) {
                    while (hrs.next()) {
                        String recId = hrs.getString("id");
                        String fid = hrs.getString("farmer_id");
                        String cname = hrs.getString("crop_name");
                        String season = hrs.getString("season");
                        int score = hrs.getInt("score");
                        double yield = hrs.getDouble("yield");
                        double gross = hrs.getDouble("gross_cost");
                        double net = hrs.getDouble("net_cost");
                        double rev = hrs.getDouble("revenue");
                        double prof = hrs.getDouble("profit");
                        double roi = hrs.getDouble("roi");
                        String notes = hrs.getString("advice_notes");
                        long rDate = hrs.getLong("rec_date");

                        Farmer f = farmers.get(fid);
                        if (f != null) {
                            f.getHistory().add(new Farmer.FarmRecord(recId, cname, season, score, yield, gross, net, rev, prof, roi, notes, new Date(rDate)));
                        }
                    }
                }
                return farmers;
            } catch (SQLException e) {
                System.err.println("JDBC Load Farmers Error: " + e.getMessage() + ". Loading from CSV.");
            }
        }

        File file = new File(FARMERS_FILE);
        if (!file.exists()) return farmers;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 7) continue;

                String id = parts[0];
                String userId = parts[1];
                String name = parts[2];
                String type = parts[3];
                double size = Double.parseDouble(parts[4]);
                Cultivable.SoilType soil = Cultivable.SoilType.ALLUVIAL;
                try { soil = Cultivable.SoilType.valueOf(parts[5].trim().toUpperCase()); } catch (Exception ignored) {}

                Cultivable.WaterLevel water = Cultivable.WaterLevel.MEDIUM;
                try { water = Cultivable.WaterLevel.valueOf(parts[6].trim().toUpperCase()); } catch (Exception ignored) {}
                String district = (parts.length >= 8) ? parts[7] : "Thanjavur";
                String source = (parts.length >= 9) ? parts[8] : "Canal";
                String location = (parts.length >= 10) ? parts[9] : district + ", Tamil Nadu";
                double lat = (parts.length >= 11) ? Double.parseDouble(parts[10]) : 10.7870;
                double lon = (parts.length >= 12) ? Double.parseDouble(parts[11]) : 79.1378;

                Farmer f;
                if ("Small".equalsIgnoreCase(type)) {
                    f = new SmallFarmer(id, userId, name, size, soil, water, district, source, location, lat, lon);
                } else {
                    f = new LargeFarmer(id, userId, name, size, soil, water, district, source, location, lat, lon);
                }
                farmers.put(id, f);
            }
        } catch (Exception ignored) {}

        File hFile = new File(ADVISORIES_FILE);
        if (hFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(hFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\|");
                    if (parts.length >= 13) {
                        String recId = parts[0];
                        String fId = parts[1];
                        String cname = parts[2];
                        String season = parts[3];
                        int score = Integer.parseInt(parts[4]);
                        double yield = Double.parseDouble(parts[5]);
                        double gross = Double.parseDouble(parts[6]);
                        double net = Double.parseDouble(parts[7]);
                        double rev = Double.parseDouble(parts[8]);
                        double prof = Double.parseDouble(parts[9]);
                        double roi = Double.parseDouble(parts[10]);
                        String notes = parts[11];
                        long rDate = Long.parseLong(parts[12]);

                        Farmer f = farmers.get(fId);
                        if (f != null) {
                            f.getHistory().add(new Farmer.FarmRecord(recId, cname, season, score, yield, gross, net, rev, prof, roi, notes, new Date(rDate)));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return farmers;
    }

    private static List<Cultivable.SoilType> parseSoils(String soilsStr) {
        List<Cultivable.SoilType> list = new ArrayList<>();
        if (soilsStr == null) return list;
        String[] parts = soilsStr.split(";");
        for (String p : parts) {
            try {
                list.add(Cultivable.SoilType.valueOf(p.trim().toUpperCase()));
            } catch (Exception ignored) {}
        }
        return list;
    }
}

// =========================================================================
// SERVICES LAYER
// =========================================================================

class AdvisoryReportFull {
    public final Farmer farmer;
    public final List<CropService.AdvisoryResult> rankedCrops;
    public final Crop topCrop;
    public final double expectedYield;
    public final double reqUrea;
    public final double reqDap;
    public final double reqMop;
    public final double grossFertilizerCost;
    public final double subsidySavings;
    public final double netFertilizerCost;
    public final double seedCost;
    public final double laborCost;
    public final double totalInputCost;
    public final double grossRevenue;
    public final double netProfit;
    public final double roiPercent;
    public final List<String> adviceNotes;
    public final List<String> applicableSchemes;
    public final String recordId;

    public AdvisoryReportFull(Farmer farmer, List<CropService.AdvisoryResult> rankedCrops, Crop topCrop,
                              double expectedYield, double reqUrea, double reqDap, double reqMop,
                              double grossFertilizerCost, double subsidySavings, double netFertilizerCost,
                              double seedCost, double laborCost, double totalInputCost, double grossRevenue,
                              double netProfit, double roiPercent, List<String> adviceNotes,
                              List<String> applicableSchemes, String recordId) {
        this.farmer = farmer;
        this.rankedCrops = rankedCrops;
        this.topCrop = topCrop;
        this.expectedYield = expectedYield;
        this.reqUrea = reqUrea;
        this.reqDap = reqDap;
        this.reqMop = reqMop;
        this.grossFertilizerCost = grossFertilizerCost;
        this.subsidySavings = subsidySavings;
        this.netFertilizerCost = netFertilizerCost;
        this.seedCost = seedCost;
        this.laborCost = laborCost;
        this.totalInputCost = totalInputCost;
        this.grossRevenue = grossRevenue;
        this.netProfit = netProfit;
        this.roiPercent = roiPercent;
        this.adviceNotes = adviceNotes;
        this.applicableSchemes = applicableSchemes;
        this.recordId = recordId;
    }

    public String toJson() {
        StringBuilder cropsJson = new StringBuilder("[");
        for (int i = 0; i < rankedCrops.size(); i++) {
            CropService.AdvisoryResult r = rankedCrops.get(i);
            cropsJson.append(String.format(Locale.US, "{\"cropName\":\"%s\",\"type\":\"%s\",\"score\":%d,\"yield\":%.2f,\"reason\":\"%s\"}",
                    r.getCrop().getName().replace("\"", "\\\""),
                    r.getCrop().getClassification().replace("\"", "\\\""),
                    r.getScore(),
                    r.getCrop().estimateYield(farmer.getFarmSize()),
                    r.getReason().replace("\"", "\\\"")));
            if (i < rankedCrops.size() - 1) cropsJson.append(",");
        }
        cropsJson.append("]");

        StringBuilder notesJson = new StringBuilder("[");
        for (int i = 0; i < adviceNotes.size(); i++) {
            notesJson.append("\"").append(adviceNotes.get(i).replace("\"", "\\\"")).append("\"");
            if (i < adviceNotes.size() - 1) notesJson.append(",");
        }
        notesJson.append("]");

        StringBuilder schemesJson = new StringBuilder("[");
        for (int i = 0; i < applicableSchemes.size(); i++) {
            schemesJson.append("\"").append(applicableSchemes.get(i).replace("\"", "\\\"")).append("\"");
            if (i < applicableSchemes.size() - 1) schemesJson.append(",");
        }
        schemesJson.append("]");

        return String.format(Locale.US,
                "{\"recordId\":\"%s\",\"farmer\":%s,\"rankedCrops\":%s,\"topCrop\":%s,\"expectedYield\":%.2f," +
                "\"ureaKg\":%.2f,\"dapKg\":%.2f,\"mopKg\":%.2f,\"grossFertilizerCost\":%.2f,\"subsidySavings\":%.2f," +
                "\"netFertilizerCost\":%.2f,\"seedCost\":%.2f,\"laborCost\":%.2f,\"totalInputCost\":%.2f," +
                "\"grossRevenue\":%.2f,\"netProfit\":%.2f,\"roiPercent\":%.2f,\"adviceNotes\":%s,\"applicableSchemes\":%s}",
                recordId,
                farmer.toJson(),
                cropsJson.toString(),
                (topCrop != null ? topCrop.toJson() : "null"),
                expectedYield, reqUrea, reqDap, reqMop, grossFertilizerCost, subsidySavings,
                netFertilizerCost, seedCost, laborCost, totalInputCost, grossRevenue, netProfit,
                roiPercent, notesJson.toString(), schemesJson.toString());
    }
}

class CropService {
    private final Map<String, User> users;
    private final Map<String, Crop> cropCatalog;
    private final Map<String, Farmer> farmers;

    public static class AdvisoryResult {
        private final Crop crop;
        private int score;
        private String reason;

        public AdvisoryResult(Crop crop, int score, String reason) {
            this.crop = crop;
            this.score = score;
            this.reason = reason;
        }

        public Crop getCrop() { return crop; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public String getReason() { return reason; }
    }

    public CropService() {
        this.users = new ConcurrentHashMap<>();
        this.cropCatalog = new ConcurrentHashMap<>();
        this.farmers = new ConcurrentHashMap<>();
    }

    public void loadData() {
        DatabaseManager.initialize();
        Map<String, User> loadedUsers = DatabaseManager.loadUsers();
        Map<String, Crop> loadedCrops = DatabaseManager.loadCrops();
        Map<String, Farmer> loadedFarmers = DatabaseManager.loadFarmers();

        this.users.clear();
        this.users.putAll(loadedUsers);

        this.cropCatalog.clear();
        this.cropCatalog.putAll(loadedCrops);

        this.farmers.clear();
        this.farmers.putAll(loadedFarmers);

        seedDefaultsIfEmpty();
    }

    private void seedDefaultsIfEmpty() {
        // Seed Admin & Demo Accounts if empty
        if (users.isEmpty()) {
            String adminSalt = CropAdvisoryApp.generateSalt();
            User admin = new User("USR-ADMIN", "admin", CropAdvisoryApp.hashPin("9999", adminSalt), adminSalt, "ADMIN", "en", System.currentTimeMillis());
            users.put(admin.getId(), admin);

            String demoSalt = CropAdvisoryApp.generateSalt();
            User demo = new User("USR-DEMO", "demo", CropAdvisoryApp.hashPin("1234", demoSalt), demoSalt, "FARMER", "en", System.currentTimeMillis());
            users.put(demo.getId(), demo);
        }

        // Seed 10 Tamil Nadu Crops
        if (cropCatalog.isEmpty()) {
            addCropInternal(new FoodCrop("Paddy", Arrays.asList(Cultivable.SoilType.CLAY, Cultivable.SoilType.ALLUVIAL, Cultivable.SoilType.LOAMY),
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.HIGH, 24.0, 50.0, 30.0, 20.0, "Cereal Grain", 2300.0, 3000.0, 8000.0));

            addCropInternal(new CashCrop("Sugarcane", Arrays.asList(Cultivable.SoilType.ALLUVIAL, Cultivable.SoilType.LOAMY, Cultivable.SoilType.CLAY),
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.HIGH, 400.0, 70.0, 35.0, 30.0, "Sugar Industry", 315.0, 5500.0, 12000.0));

            addCropInternal(new CashCrop("Cotton", Arrays.asList(Cultivable.SoilType.BLACK, Cultivable.SoilType.RED, Cultivable.SoilType.LOAMY),
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.MEDIUM, 11.0, 45.0, 25.0, 15.0, "Textile Fiber", 7120.0, 4500.0, 7000.0));

            addCropInternal(new CashCrop("Groundnut", Arrays.asList(Cultivable.SoilType.RED, Cultivable.SoilType.SANDY, Cultivable.SoilType.LOAMY),
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.LOW, 10.0, 15.0, 30.0, 20.0, "Oilseed Industry", 6780.0, 5000.0, 5500.0));

            addCropInternal(new FoodCrop("Banana", Arrays.asList(Cultivable.SoilType.ALLUVIAL, Cultivable.SoilType.LOAMY),
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.HIGH, 350.0, 60.0, 40.0, 35.0, "Horticulture Fruit", 1800.0, 8000.0, 14000.0));

            addCropInternal(new CashCrop("Turmeric", Arrays.asList(Cultivable.SoilType.LOAMY, Cultivable.SoilType.RED, Cultivable.SoilType.ALLUVIAL),
                    Cultivable.Season.SPRING, Cultivable.WaterLevel.MEDIUM, 25.0, 40.0, 30.0, 25.0, "Spice Industry", 12500.0, 9000.0, 10000.0));

            addCropInternal(new FoodCrop("Ragi (Finger Millet)", Arrays.asList(Cultivable.SoilType.RED, Cultivable.SoilType.LOAMY, Cultivable.SoilType.SANDY),
                    Cultivable.Season.WINTER, Cultivable.WaterLevel.LOW, 12.0, 20.0, 15.0, 10.0, "Nutri-Millet", 3800.0, 1800.0, 4000.0));

            addCropInternal(new FoodCrop("Maize", Arrays.asList(Cultivable.SoilType.RED, Cultivable.SoilType.LOAMY, Cultivable.SoilType.BLACK),
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.MEDIUM, 22.0, 55.0, 35.0, 20.0, "Coarse Cereal", 2225.0, 2800.0, 5000.0));

            addCropInternal(new CashCrop("Coconut", Arrays.asList(Cultivable.SoilType.SANDY, Cultivable.SoilType.ALLUVIAL, Cultivable.SoilType.LOAMY),
                    Cultivable.Season.SUMMER, Cultivable.WaterLevel.MEDIUM, 80.0, 30.0, 20.0, 40.0, "Plantation Industry", 3000.0, 4000.0, 6000.0));

            addCropInternal(new FoodCrop("Kudiraivali (Millet)", Arrays.asList(Cultivable.SoilType.RED, Cultivable.SoilType.SANDY, Cultivable.SoilType.LOAMY),
                    Cultivable.Season.WINTER, Cultivable.WaterLevel.LOW, 8.0, 10.0, 10.0, 5.0, "Small Millet", 4500.0, 1500.0, 3500.0));
        }

        // Seed Demo Farmer linked to demo user
        if (farmers.isEmpty()) {
            User demo = getUserByUsername("demo");
            String userId = (demo != null) ? demo.getId() : "USR-DEMO";
            Farmer demoFarmer = new SmallFarmer("FM-101", userId, "M. Selvam", 3.5, Cultivable.SoilType.ALLUVIAL,
                    Cultivable.WaterLevel.HIGH, "Thanjavur", "Canal", "Thanjavur, Tamil Nadu", 10.7870, 79.1378);
            farmers.put(demoFarmer.getFarmerId(), demoFarmer);
        }

        saveData();
    }

    private void addCropInternal(Crop crop) {
        cropCatalog.put(crop.getName(), crop);
    }

    public synchronized void saveData() {
        DatabaseManager.saveData(
            new ArrayList<>(users.values()),
            new ArrayList<>(cropCatalog.values()),
            new ArrayList<>(farmers.values())
        );
    }

    public synchronized void registerUser(User user) {
        users.put(user.getId(), user);
        saveData();
    }

    public User getUserByUsername(String username) {
        if (username == null) return null;
        String clean = username.trim().toLowerCase();
        for (User u : users.values()) {
            if (u.getUsername().equalsIgnoreCase(clean)) return u;
        }
        return null;
    }

    public User getUserById(String id) {
        return (id != null) ? users.get(id) : null;
    }

    public synchronized void addCrop(Crop crop) {
        cropCatalog.put(crop.getName(), crop);
        saveData();
    }

    public synchronized void registerFarmer(Farmer farmer) {
        farmers.put(farmer.getFarmerId(), farmer);
        saveData();
    }

    public Farmer getFarmerByUserId(String userId) {
        if (userId == null) return null;
        for (Farmer f : farmers.values()) {
            if (userId.equals(f.getUserId())) return f;
        }
        return null;
    }

    public Map<String, User> getUsers() { return users; }
    public Map<String, Crop> getCropCatalog() { return cropCatalog; }
    public Map<String, Farmer> getFarmers() { return farmers; }

    // Generate Full Advisory with Weather telemetry & ROI calculations
    public AdvisoryReportFull generateFullAdvisory(Farmer farmer, Cultivable.Season currentSeason, String weatherJson) {
        List<AdvisoryResult> results = new ArrayList<>();
        List<String> adviceNotes = new ArrayList<>();

        // Parse weather metrics from Open-Meteo JSON
        double temp = 30.0;
        double rainProb = 20.0;
        double wind = 12.0;
        boolean weatherAvailable = false;

        try {
            if (weatherJson != null && weatherJson.contains("current")) {
                if (weatherJson.contains("\"temperature_2m\":")) {
                    String sub = weatherJson.substring(weatherJson.indexOf("\"temperature_2m\":") + 17);
                    temp = Double.parseDouble(sub.split("[,}]")[0]);
                }
                if (weatherJson.contains("\"wind_speed_10m\":")) {
                    String sub = weatherJson.substring(weatherJson.indexOf("\"wind_speed_10m\":") + 17);
                    wind = Double.parseDouble(sub.split("[,}]")[0]);
                }
                if (weatherJson.contains("\"precipitation_probability_max\":")) {
                    String sub = weatherJson.substring(weatherJson.indexOf("\"precipitation_probability_max\":[") + 32);
                    rainProb = Double.parseDouble(sub.split("[,\\}]")[0]);
                }
                weatherAvailable = true;
            }
        } catch (Exception ignored) {}

        // Add weather advice notes & calculate small bounded score adjustment
        int weatherScoreAdj = 0;
        if (weatherAvailable) {
            adviceNotes.add(String.format(Locale.US, "Live Telemetry (%s): Temp %.1f°C, Rain Risk %.0f%%, Wind %.1f km/h.",
                    farmer.getDistrict(), temp, rainProb, wind));

            if (rainProb > 70.0) {
                adviceNotes.add("WEATHER ALERT: High precipitation forecasted (>70%). Clear field drainage channels and delay fertilizer application/sowing.");
                weatherScoreAdj -= 2;
            } else if (rainProb < 20.0 && temp > 35.0) {
                adviceNotes.add("WEATHER NOTE: Low precipitation & high temperatures. Ensure sufficient irrigation frequency.");
            }

            if (temp > 38.0) {
                adviceNotes.add("HEATWAVE WARNING: Temperature above 38°C. Perform early morning or evening irrigation to minimize evapotranspiration loss.");
                weatherScoreAdj -= 3;
            }

            if (wind > 20.0) {
                adviceNotes.add("WIND WARNING: Wind speed exceeds 20 km/h. Avoid foliar pesticide or liquid fertilizer spraying today.");
            }
        } else {
            adviceNotes.add("WEATHER NOTICE: Live satellite telemetry currently unavailable. Recommendation generated using historical seasonal defaults.");
        }

        // Soil & Water specific notes
        if (farmer.getSoilType() == Cultivable.SoilType.CLAY) {
            adviceNotes.add("SOIL ADVICE: Clay soil has high water retention. Monitor root zone to prevent waterlogging.");
        } else if (farmer.getSoilType() == Cultivable.SoilType.SANDY) {
            adviceNotes.add("SOIL ADVICE: Sandy soil drains quickly. Split nitrogen fertilizer doses into 3-4 applications to prevent leaching.");
        }

        // Calculate crop scores
        for (Crop crop : cropCatalog.values()) {
            int baseScore = crop.calculateSuitabilityScore(farmer.getSoilType(), currentSeason, farmer.getWaterAccess());
            int finalScore = Math.max(0, Math.min(100, baseScore + weatherScoreAdj));

            String reason = String.format("Soil %s: %s | Season: %s | Water: %s",
                    farmer.getSoilType().getDisplayName(),
                    (crop.getSuitableSoils().contains(farmer.getSoilType()) ? "Optimal" : (crop.isSoilModeratelyCompatible(farmer.getSoilType()) ? "Compatible" : "Poor")),
                    (crop.getSuitableSeason() == currentSeason ? "Optimal" : "Off-season"),
                    crop.getRequiredWaterLevel().getDisplayName());

            results.add(new AdvisoryResult(crop, finalScore, reason));
        }

        // Sort by suitability score descending, tie-breaking by ROI %
        results.sort((a, b) -> {
            if (b.getScore() != a.getScore()) {
                return Integer.compare(b.getScore(), a.getScore());
            }
            double roiA = calculateRoi(farmer, a.getCrop());
            double roiB = calculateRoi(farmer, b.getCrop());
            return Double.compare(roiB, roiA);
        });

        // Top recommended crop details
        Crop topCrop = !results.isEmpty() ? results.get(0).getCrop() : null;
        double acres = farmer.getFarmSize();

        double expectedYield = 0.0, reqUrea = 0.0, reqDap = 0.0, reqMop = 0.0;
        double grossCost = 0.0, subsidySavings = 0.0, netCost = 0.0;
        double seedCost = 0.0, laborCost = 0.0, totalInputCost = 0.0;
        double grossRevenue = 0.0, netProfit = 0.0, roiPercent = 0.0;

        if (topCrop != null) {
            expectedYield = topCrop.estimateYield(acres);
            reqUrea = topCrop.getFertilizerNeeds().getUreaPerAcre() * acres;
            reqDap = topCrop.getFertilizerNeeds().getDapPerAcre() * acres;
            reqMop = topCrop.getFertilizerNeeds().getMopPerAcre() * acres;

            double priceUrea = 18.50; // ₹18.50 / kg
            double priceDap = 32.00;  // ₹32.00 / kg
            double priceMop = 22.00;  // ₹22.00 / kg

            grossCost = (reqUrea * priceUrea) + (reqDap * priceDap) + (reqMop * priceMop);
            subsidySavings = grossCost * farmer.getFertilizerSubsidyRate();
            netCost = grossCost - subsidySavings;

            seedCost = topCrop.getSeedCostPerAcre() * acres;
            laborCost = topCrop.getLaborCostPerAcre() * acres;
            totalInputCost = netCost + seedCost + laborCost;

            grossRevenue = expectedYield * topCrop.getMarketPricePerUnit();
            netProfit = grossRevenue - totalInputCost;
            roiPercent = (totalInputCost > 0) ? (netProfit / totalInputCost) * 100.0 : 0.0;
        }

        List<String> schemes = getApplicableSchemes(farmer, topCrop);

        // Save advisory report record to history
        String recId = "ADV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        StringBuilder notesJoined = new StringBuilder();
        for (String note : adviceNotes) {
            notesJoined.append("• ").append(note).append("\n");
        }

        Farmer.FarmRecord record = new Farmer.FarmRecord(
                recId,
                (topCrop != null ? topCrop.getName() : "None"),
                currentSeason.name(),
                (!results.isEmpty() ? results.get(0).getScore() : 0),
                expectedYield, grossCost, netCost, grossRevenue, netProfit, roiPercent,
                notesJoined.toString(), new Date());

        farmer.addFarmRecord(record);
        saveData();

        return new AdvisoryReportFull(farmer, results, topCrop, expectedYield, reqUrea, reqDap, reqMop,
                grossCost, subsidySavings, netCost, seedCost, laborCost, totalInputCost, grossRevenue,
                netProfit, roiPercent, adviceNotes, schemes, recId);
    }

    private double calculateRoi(Farmer farmer, Crop crop) {
        double acres = farmer.getFarmSize();
        double reqUrea = crop.getFertilizerNeeds().getUreaPerAcre() * acres;
        double reqDap = crop.getFertilizerNeeds().getDapPerAcre() * acres;
        double reqMop = crop.getFertilizerNeeds().getMopPerAcre() * acres;
        double grossCost = (reqUrea * 18.50) + (reqDap * 32.00) + (reqMop * 22.00);
        double netFertCost = grossCost * (1.0 - farmer.getFertilizerSubsidyRate());
        double totalInput = netFertCost + (crop.getSeedCostPerAcre() * acres) + (crop.getLaborCostPerAcre() * acres);
        double revenue = crop.estimateYield(acres) * crop.getMarketPricePerUnit();
        double profit = revenue - totalInput;
        return (totalInput > 0) ? (profit / totalInput) * 100.0 : 0.0;
    }

    public List<String> getApplicableSchemes(Farmer farmer, Crop crop) {
        List<String> list = new ArrayList<>();
        list.add("TN Micro Irrigation Scheme (TNMIS) - Up to 100% drip subsidy");
        list.add("Kalaignarin All Village Integrated Agriculture Development Scheme");
        if (farmer.getFarmSize() <= 5.0) {
            list.add("PM-KISAN Scheme (₹6,000 annual direct benefit transfer)");
            list.add("Sub-Mission on Agricultural Mechanization (SMAM - 50% equipment subsidy)");
        } else {
            list.add("Commercial Agriculture Infrastructure Development Scheme");
            list.add("NABARD Agri-Clinic & Agri-Business Center Support");
        }
        list.add("Pradhan Mantri Fasal Bima Yojana (PMFBY Crop Insurance)");
        if (crop instanceof FoodCrop) {
            list.add("National Food Security Mission (NFSM Pulse & Grain Scheme)");
        } else {
            list.add("Paramparagat Krishi Vikas Yojana (PKVY Organic Cash Crop Scheme)");
        }
        return list;
    }
}

// =========================================================================
// THREADS & CONCURRENCY LAYER
// =========================================================================

class WeatherAlertDaemon extends Thread {
    private final long monitorIntervalMs;
    private final Random random = new Random();

    public WeatherAlertDaemon(long monitorIntervalMs) {
        this.monitorIntervalMs = monitorIntervalMs;
        this.setDaemon(true); 
        this.setName("TN-Weather-Daemon-Thread");
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(monitorIntervalMs);
                for (CropAdvisoryApp.TNDistrict d : CropAdvisoryApp.TN_DISTRICTS.values()) {
                    try {
                        String json = CropAdvisoryApp.fetchOpenMeteoWeather(d.lat, d.lon);
                        if (json != null && json.contains("current")) {
                            if (json.contains("\"temperature_2m\":")) {
                                String sub = json.substring(json.indexOf("\"temperature_2m\":") + 17);
                                double temp = Double.parseDouble(sub.split("[,}]")[0]);
                                if (temp > 35.0) {
                                    CropAdvisoryApp.addWeatherAlert(String.format(Locale.US,
                                            "%s: HEAT ALERT - Temperature recorded at %.1f°C. Increase irrigation frequency.", d.name, temp));
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    Thread.sleep(500); // 500ms pause between district weather checks
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}

class WaterAllocationTest {
    private static int availableReservoirWaterKL = 50;

    public static synchronized boolean allocateIrrigation(String farmerName, int requestedKL, List<String> logs) {
        if (availableReservoirWaterKL >= requestedKL) {
            availableReservoirWaterKL -= requestedKL;
            logs.add(String.format(" [GRANTED] %-12s allocated %d KL. Reservoir status: %d KL remaining.",
                    farmerName, requestedKL, availableReservoirWaterKL));
            return true;
        } else {
            logs.add(String.format(" [DENIED]  %-12s requested %d KL. Insufficient supply (%d KL remaining).",
                    farmerName, requestedKL, availableReservoirWaterKL));
            return false;
        }
    }

    public static String runTestAndGetLogs() {
        availableReservoirWaterKL = 50;
        List<String> logs = new CopyOnWriteArrayList<>();

        logs.add("========================================================================");
        logs.add("             CONCURRENT WATER ALLOCATION SLUICE GATE SIMULATION");
        logs.add("========================================================================");
        logs.add("Scenario: 5 regional canal sluice gates open simultaneously demanding 15 KL each.");
        logs.add("Reservoir Capacity: 50 KL. Maximum allocation budget allows exactly 3 grants (45 KL).");
        logs.add("Note: Which 3 threads succeed varies per run due to thread scheduling concurrency.");
        logs.add("------------------------------------------------------------------------");
        logs.add("Initializing canal sluice threads & setting CountDownLatch...");

        int threadCount = 5;
        Thread[] threads = new Thread[threadCount];
        CountDownLatch startGate = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            final String farmerName = "Channel-Gate-" + (i + 1);
            threads[i] = new Thread(() -> {
                try {
                    startGate.await(); 
                    allocateIrrigation(farmerName, 15, logs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Canal-Thread-" + (i + 1));
        }

        for (Thread t : threads) {
            t.start();
        }

        try {
            Thread.sleep(200); 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logs.add("Firing simultaneous canal sluice gates (CountDownLatch.countDown())...");
        logs.add("------------------------------------------------------------------------");
        startGate.countDown();

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logs.add("------------------------------------------------------------------------");
        logs.add(String.format("Simulation complete. Total Allocated: 45 KL | Reservoir Remaining: %d KL.", availableReservoirWaterKL));
        logs.add("========================================================================");

        StringBuilder sb = new StringBuilder();
        for (String log : logs) {
            sb.append(log).append("\n");
        }
        return sb.toString();
    }
}
