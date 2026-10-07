# Smart Crop Advisory & Resource Management System (Tamil Nadu Edition)

A full-stack, personalized agricultural decision support and farm resource management web application built with **Core Java (JDK 17+)**, `com.sun.net.httpserver.HttpServer`, SQLite via JDBC (with automatic flat-file CSV fallback), and a glassmorphic single-page web interface.

---

## 🌟 Architecture & Technology Stack

- **Backend**: Single-file Core Java backend (`CropAdvisoryApp.java`) with zero framework dependencies.
- **Networking & Server**: `com.sun.net.httpserver.HttpServer` with a fixed thread pool (`Executors.newFixedThreadPool(12)`).
- **Authentication**: Salted SHA-256 password hashing with UUID session token management held in `ConcurrentHashMap`.
- **Database Engine**: Dual-mode storage using SQLite via JDBC (`crop_advisory.db`) with automatic flat-file CSV fallback (`users.txt`, `farmers.txt`, `crops.txt`, `advisories.txt`).
- **Telemetry**: Open-Meteo REST API integration for real-time Tamil Nadu district weather and background daemon alerts (`WeatherAlertDaemon extends Thread`).
- **Frontend**: Vanilla HTML5, CSS3 (glassmorphic dark/light mode), and ES6 JavaScript SPA with Leaflet.js interactive maps and full English/Tamil/Hindi multilingual i18n support.

---

## 🚀 Quick Start & Running

### System Requirements
- JDK 17 or higher (`javac` and `java` binaries in path).
- Windows (PowerShell) or Linux/macOS (Bash).

### Launch Command

#### Windows (PowerShell):
```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

#### Linux / macOS (Bash):
```bash
chmod +x run.sh
./run.sh
```

Navigate in your browser to:
👉 **http://localhost:8080**

---

## 🔑 Demo Credentials

| Role | Username / Mobile | Security PIN | Access Privileges |
|---|---|---|---|
| **Demo Farmer** | `demo` | `1234` | Full personalized farmer dashboard, advisory, history, weather map, water simulation |
| **Admin** | `admin` | `9999` | Admin panel (Crop catalog editing, all registered farmers view, system stats) |

---

## 🔄 End-to-End User Flow

1. **Landing Page**:
   - Displays application introduction, system feature grid, demo credential shortcuts, and language switcher (English / தமிழ் / हिंदी).
   - No farmer data or advisories are exposed prior to authentication.
2. **Sign Up**:
   - Register with Full Name, Username / Mobile Number, 4-6 digit security PIN, and language preference.
   - Security PIN is salted and hashed using SHA-256 before persistence.
3. **Sign In**:
   - Authenticate via Username + PIN. Generates an in-memory session token (UUID) with a 24-hour expiration window.
   - Sent via `Authorization: Bearer <token>` header with every API request.
4. **First-Time Farm Profile Setup**:
   - Shown automatically if profile details are incomplete.
   - Select Tamil Nadu district (dropdown of 15 districts with coordinates auto-fill), soil type (Red, Alluvial, Clay, Black, Loamy, Sandy), farm area in acres, water access level (Low / Medium / High), and primary irrigation source (Canal / Borewell / Rain-fed).
   - Auto-categorizes land size: `acres <= 5.0` => **SmallFarmer** (30% NPK Subsidy), `acres > 5.0` => **LargeFarmer** (0% Subsidy).
5. **Personal Dashboard**:
   - Greeting by farmer's name, profile summary card, live Open-Meteo weather telemetry for farmer's district, district risk alerts, and quick action shortcuts.
6. **Get Advisory**:
   - Select cultivation season: Kuruvai (Summer / Kharif), Samba (Winter / Rabi), or Navarai (Spring / Zaid).
   - Uses farmer's saved profile automatically and generates:
     - Ranked crop list with suitability score out of 100 and compatibility reason per crop.
     - Deep dive report for top recommended crop: expected yield, scaled urea/DAP/MOP requirements, gross cost, subsidy savings, net fertilizer cost, seed cost, labor cost, total input cost, gross revenue, net profit, and ROI %.
     - Weather-aware advice notes (rain warnings, heatwave alerts, spraying advisories).
     - Applicable Tamil Nadu and Central government subsidies/schemes.
     - Automatically logs report to farmer's advisory history.
7. **Advisory History**:
   - View past advisory reports for the logged-in farmer. Click "View Report" to inspect full report card modal.
8. **Weather Map**:
   - Leaflet map centered on Tamil Nadu featuring 15 districts. Farmer's district is highlighted with a distinct marker pin.
   - Click any district marker to inspect live Open-Meteo telemetry and 5-day forecast.
9. **Alerts Board**:
   - Live weather alert ticker and dedicated risk telemetry board prioritizing farmer's district.
10. **Shared Water Allocation**:
    - Interactive 5-thread Java concurrency simulation. 5 regional canal channels request 15 KL each from a 50 KL reservoir using `CountDownLatch` and `synchronized allocateIrrigation`. Exactly 3 succeed (45 KL) and 2 are denied.
    - Output streamed into developer-console terminal panel.
11. **Admin Panel**:
    - Manage crop catalog, view list of all registered farmers, and view storage engine status.

---

## 📊 Database Schema

### `users` Table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | User unique ID (`USR-...`) |
| `username` | TEXT | UNIQUE, NOT NULL | Account login username or mobile |
| `pin_hash` | TEXT | NOT NULL | Salted SHA-256 hash of PIN |
| `salt` | TEXT | NOT NULL | Hex-encoded random salt |
| `role` | TEXT | NOT NULL | Role (`FARMER` or `ADMIN`) |
| `language` | TEXT | NOT NULL | Preferred UI language (`en`, `ta`, `hi`) |
| `created_at` | INTEGER | NOT NULL | Epoch timestamp |

### `farmers` Table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Farmer profile ID (`FM-...`) |
| `user_id` | TEXT | FOREIGN KEY | Linked `users.id` |
| `name` | TEXT | NOT NULL | Farmer full name |
| `type` | TEXT | NOT NULL | Farmer category (`Small` / `Large`) |
| `size` | DOUBLE | NOT NULL | Land holdings in acres |
| `soil` | TEXT | NOT NULL | Soil type (`RED`, `ALLUVIAL`, `CLAY`, `BLACK`, `LOAMY`, `SANDY`) |
| `water` | TEXT | NOT NULL | Water access (`LOW`, `MEDIUM`, `HIGH`) |
| `district` | TEXT | NOT NULL | Tamil Nadu district name |
| `irrigation_source` | TEXT | NOT NULL | Canal, Borewell, or Rain-fed |
| `location` | TEXT | NOT NULL | Location string |
| `latitude` | DOUBLE | NOT NULL | Latitude coordinate |
| `longitude` | DOUBLE | NOT NULL | Longitude coordinate |

### `crops` Table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `name` | TEXT | PRIMARY KEY | Crop name |
| `type` | TEXT | NOT NULL | Crop classification (`FoodCrop` / `CashCrop`) |
| `soils` | TEXT | NOT NULL | Suitable soil types (semicolon-separated) |
| `season` | TEXT | NOT NULL | Ideal season (`SUMMER`, `WINTER`, `SPRING`) |
| `water` | TEXT | NOT NULL | Required water level (`LOW`, `MEDIUM`, `HIGH`) |
| `yield` | DOUBLE | NOT NULL | Expected yield per acre (Quintals/Tons) |
| `urea` | DOUBLE | NOT NULL | Urea requirement per acre (Kg) |
| `dap` | DOUBLE | NOT NULL | DAP requirement per acre (Kg) |
| `mop` | DOUBLE | NOT NULL | MOP requirement per acre (Kg) |
| `special` | TEXT | - | Food category or industry type |
| `market_price` | DOUBLE | NOT NULL | Market price per unit (₹) |
| `seed_cost` | DOUBLE | NOT NULL | Seed cost per acre (₹) |
| `labor_cost` | DOUBLE | NOT NULL | Labor cost per acre (₹) |

### `advisories` Table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Recommendation ID (`ADV-...`) |
| `farmer_id` | TEXT | NOT NULL | Target farmer ID |
| `crop_name` | TEXT | NOT NULL | Recommended top crop |
| `season` | TEXT | NOT NULL | Cultivation season |
| `score` | INTEGER | NOT NULL | Suitability score (0-100) |
| `yield` | DOUBLE | NOT NULL | Estimated total yield |
| `gross_cost` | DOUBLE | NOT NULL | Gross fertilizer cost (₹) |
| `net_cost` | DOUBLE | NOT NULL | Net fertilizer cost (₹) |
| `revenue` | DOUBLE | NOT NULL | Projected gross revenue (₹) |
| `profit` | DOUBLE | NOT NULL | Projected net profit (₹) |
| `roi` | DOUBLE | NOT NULL | Projected ROI % |
| `advice_notes` | TEXT | - | Weather and soil advice notes |
| `rec_date` | INTEGER | NOT NULL | Recommendation timestamp |

---

## ⚙️ REST API Endpoints

| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| `GET` | `/api/status` | Public | System status and storage engine info |
| `GET` | `/api/districts` | Public | 15 Tamil Nadu districts metadata |
| `POST` | `/api/auth/register` | Public | Register new user account |
| `POST` | `/api/auth/login` | Public | Authenticate user and issue token |
| `POST` | `/api/auth/logout` | Protected | Invalidate session token |
| `GET` | `/api/me` | Protected | Fetch current user and farm profile |
| `PUT` | `/api/me` | Protected | Save or update farm profile |
| `POST` | `/api/advisory` | Protected | Generate advisory for logged-in farmer |
| `GET` | `/api/advisories` | Protected | Advisory history for logged-in farmer |
| `GET` | `/api/advisories/{id}` | Protected | Fetch single advisory report detail |
| `GET` | `/api/weather` | Public | Fetch Open-Meteo telemetry for location/district |
| `GET` | `/api/weather/grid` | Public | Multi-location batched 72h grid weather API (`south,west,north,east,rows,cols,hour`) |
| `GET` | `/api/weather/point` | Public | Point telemetry API (current, 24h hourly, 5-day daily forecast for any lat/lon) |
| `GET` | `/api/geocode` | Public | Geocoding search proxy to Open-Meteo Geocoding API |
| `GET` | `/api/alerts` | Public | Fetch risk telemetry and weather alerts |
| `POST` | `/api/water/simulate` | Protected | Run 5-thread canal water concurrency test |
| `GET` | `/api/crops` | Protected | Retrieve crop catalog |
| `POST` / `PUT` | `/api/crops` | Admin Only | Create or update crop entry |
| `GET` | `/api/farmers` | Admin Only | List all registered farmers |

Protected endpoints return HTTP status `401 Unauthorized` (`{"error":"..."}`) if `Authorization` header is missing or invalid.

---

## 🧮 Suitability Scoring & Financial Formulas

### Suitability Score (0 to 100 Points)

1. **Soil Compatibility (Max 40 Pts)**:
   - Suitable Soil Match: **40 Pts**
   - Moderately Compatible Soil: **20 Pts**
   - Incompatible: **0 Pts**
   - *Compatibility Matrix*: `LOAMY` soil is moderately compatible with all soils. `ALLUVIAL` & `CLAY` are compatible. `BLACK` & `CLAY` are compatible. `RED` & `SANDY` are compatible.
2. **Season Matching (Max 30 Pts)**:
   - Season Match: **30 Pts**, else **0 Pts**.
3. **Water Access (Max 30 Pts)**:
   - Exact Match: **30 Pts**
   - Surplus Water Access: **20 Pts**
   - 1 Level Short: **10 Pts**
   - 2 Levels Short: **0 Pts**
4. **Weather Telemetry Bounded Adjustment (Max ±5 Pts)**:
   - Applied dynamically based on live rainfall/temperature telemetry.
5. **Tie-Breaking Rule**:
   - Equal suitability scores are tie-broken by higher projected **ROI %**.

### Financial ROI Formulas (for Farm Size `acres`)

- **Fertilizer Costs**:
  - `Urea Cost` = `Urea (Kg/Ac) * acres * ₹18.50`
  - `DAP Cost` = `DAP (Kg/Ac) * acres * ₹32.00`
  - `MOP Cost` = `MOP (Kg/Ac) * acres * ₹22.00`
  - `Gross Fertilizer Cost` = `Urea Cost + DAP Cost + MOP Cost`
  - `Subsidy Savings` = `Gross Fertilizer Cost * Subsidy Rate` (30% for Small, 0% for Large)
  - `Net Fertilizer Cost` = `Gross Fertilizer Cost - Subsidy Savings`
- **Total Input Cost**:
  - `Seed Cost` = `Seed Cost Per Acre * acres`
  - `Labor Cost` = `Labor Cost Per Acre * acres`
  - `Total Input Cost` = `Net Fertilizer Cost + Seed Cost + Labor Cost`
- **Profitability**:
  - `Expected Total Yield` = `Yield Per Acre * acres`
  - `Gross Revenue` = `Expected Total Yield * Market Price Per Unit`
  - `Net Profit` = `Gross Revenue - Total Input Cost`
  - `ROI %` = `(Net Profit / Total Input Cost) * 100.0`

---

## 🌊 Interactive Windy-Style Weather Map & Grid System

### Architecture & Endpoints
1. **`/api/weather/grid?south=&west=&north=&east=&rows=&cols=&hour=`**:
   - Constructs a 2D lattice of grid points spanning the current viewport bounds.
   - Sends batched multi-location HTTP requests to Open-Meteo (`latitude=lat1,lat2...&longitude=lon1,lon2...`) in chunks of <= 80 locations per batch to respect API limits.
   - Calculates zonal ($u = -\text{speed} \times \sin(\text{direction})$) and meridional ($v = -\text{speed} \times \cos(\text{direction})$) wind components for vector field calculation.
   - Caches response in a 15-minute TTL `ConcurrentHashMap` (`GridCacheEntry`) keyed by rounded bounds (0.05° precision).
   - Serves an offline/fallback mathematical grid model if external Open-Meteo network calls fail.

2. **`/api/weather/point?lat=&lon=`**:
   - Fetches current weather telemetry, 24-hour hourly forecast, and 5-day daily forecast for any global geographical coordinate clicked on the map.

3. **`/api/geocode?q=`**:
   - Proxies Open-Meteo Geocoding API to search any village, city, or district worldwide.

### Grid Construction & Bilinear Interpolation
- The frontend samples values across continuous lat/lon space by executing **Bilinear Interpolation** across the 4 surrounding grid nodes $(i, j), (i+1, j), (i, j+1), (i+1, j+1)$:
  $$\text{Value}(u, v) = (1-u)(1-v)Q_{00} + u(1-v)Q_{10} + (1-u)v Q_{01} + uv Q_{11}$$
- **Heatmap Overlay Canvas**: Renders continuous color-gradient fields (Temperature, Wind, Rain, Clouds, Pressure, Humidity) using dynamic screen-space pixel interpolation.
- **Wind Particle Engine**: Canvas overlay animating ~4,000 streamline particles. Particles move according to interpolated $(u, v)$ velocity vectors, fade using translucent trails, and re-seed upon map pan/zoom.

---

## 🧪 Comprehensive Testing Checklist

- [x] **Sign Up**: Register a new user account with 4-digit PIN. Verify salt & SHA-256 hash in DB.
- [x] **Sign In**: Login with registered username & PIN. Verify UUID token generation.
- [x] **First-Time Farm Setup**: Complete district selection (Thanjavur), soil (Alluvial), size (3.5 acres). Verify auto-classification as `SmallFarmer` (30% NPK Subsidy).
- [x] **Get Advisory**: Generate Kuruvai season advisory. Confirm Paddy ranks #1, scaled NPK is calculated, 30% subsidy discount is applied, and weather advice notes are generated.
- [x] **Advisory History**: Verify report is saved to history. Reopen full report card modal.
- [x] **Weather Map - Wind Particles**: Animated wind particles move along wind vectors, adapt smoothly to pan/zoom, and re-seed upon map boundary shifts.
- [x] **Weather Map - Layer Switcher**: Dynamic layer toggling (Wind, Temp, Rain, Clouds, Pressure, Humidity) updates heatmap canvas colors and legend bar dynamically.
- [x] **Weather Map - Time Slider**: Scrubbing 72-hour timeline player updates grid forecast values across the entire map frame.
- [x] **Weather Map - Click Anywhere Popup**: Clicking any coordinate globally drops a pin and opens a side drawer with current telemetry, 24-hour hourly chart, and 5-day mini forecast.
- [x] **Weather Map - Search & Geolocation**: Open-Meteo geocoding search flies to searched locations; "My Location" button uses browser HTML5 Geolocation API.
- [x] **Weather Map - Farm Marker & Advisory**: Displays "My Farm" marker at logged-in farmer's saved coordinates with instant popup advisory notes button.
- [x] **Weather Map - API Offline Resilience**: Displays a non-blocking toast warning ("Weather data unavailable, showing cached telemetry") and falls back to cached/generated grid data without crashing.
- [x] **Weather Map - Mobile Responsiveness**: Touch pan/zoom enabled, glass panels and drawer layout adapt gracefully to smaller viewports.
- [x] **401 Handling**: Verify protected API routes return `401 Unauthorized` without a valid `Authorization` token.
- [x] **Water Allocation Concurrency**: Run 5-thread canal sluice gate simulation. Verify exactly 3 threads are granted (45 KL) and 2 denied from 50 KL reservoir.
