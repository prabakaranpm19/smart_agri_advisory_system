# Farmer Crop Advisory Web Application

A Core Java web application that assists farmers by analyzing agricultural factors (soil profile, growth season, water access), calculating suitability ratings for multiple crops, scaling yield and NPK fertilizer requirements, applying small-holder subsidies, and simulating canal water demands.

The system replaces the console CLI with an embedded HTTP API backend and a premium Single Page Application (SPA) web frontend.

---

## Package and Directory Structure

The project has been cleaned up and consolidated into a highly structured, minimal footprint:

```
d:\pbl\java\
  ├── README.md                 (This Documentation File)
  ├── CropAdvisoryApp.java      (Single Core Java file containing models, services, exceptions, threads, and HttpServer)
  ├── crop_advisory.db          (SQLite database accessed via JDBC)
  ├── download_db_driver.ps1    (PowerShell script to fetch SQLite JDBC and logging dependencies)
  ├── run.ps1                   (PowerShell script to compile into bin/ and launch the web server)
  ├── crops.txt                 (Flat file database backup for crops)
  ├── farmers.txt               (Flat file database backup for farmers)
  ├── advisories.txt            (Flat file database backup for recommendation histories)
  ├── bin/                      (Compiled Java .class files directory)
  ├── lib/                      (Dependency jar files: sqlite-jdbc, slf4j-api, slf4j-simple)
  └── web/                      (Web application frontend assets)
        ├── index.html          (SPA Dashboard layout)
        ├── style.css           (Premium glassmorphic dashboard styling)
        └── app.js              (Frontend API connector and rendering engine)
```

---

## Prerequisites
- Standard JDK (e.g., OpenJDK 11 or higher) must be installed.
- PowerShell (to run automation scripts).

---

## Installation & Running

### Step 1: Download Database Dependencies
Create the `lib/` directory and download the SQLite JDBC driver and logging dependencies:
```powershell
powershell -ExecutionPolicy Bypass -File .\download_db_driver.ps1
```

### Step 2: Compile & Start Web Server
Compile `CropAdvisoryApp.java` into the `bin/` directory and start the web server on port `8080`:
```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

### Step 3: Open in Browser
Open your web browser and navigate to:
👉 **http://localhost:8080**

---

## Web API Endpoints

The backend server exposes the following REST API routes for frontend communication:
- `GET /api/status` - Checks database engine type (JDBC vs CSV) and loaded record counts.
- `GET /api/crops` - Retrieves all crops cataloged.
- `POST /api/crops` - Inserts a new crop profile.
- `GET /api/farmers` - Retrieves all registered farmers.
- `POST /api/farmers` - Registers a new farmer.
- `GET /api/farmers/{id}/history` - Fetches advisory logs for a specific farmer.
- `POST /api/advisory` - Evaluates suitability ratings, calculates gross/net costs, and applies subsidies.
- `POST /api/simulate-water` - Performs concurrent lock simulation of canal water allocations.
- `GET /api/weather-alerts` - Retrieves real-time weather reports emitted by the background daemon thread.
