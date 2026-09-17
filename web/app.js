// API Base URL
const API_BASE = '/api';

// Global application state
let cropsCache = [];
let farmersCache = [];
let activeSection = 'dashboard';
let weatherPollTimer = null;
let leafletMap = null;
let leafletMarker = null;

// =========================================================================
// I18N ENGINE
// =========================================================================
const i18n = {
    currentLang: 'en',
    translations: {},

    async init() {
        const savedLang = localStorage.getItem('selectedLang') || 'en';
        await this.setLanguage(savedLang, false);
    },

    async loadLanguage(lang) {
        if (this.translations[lang]) return true;
        try {
            const res = await fetch(`/i18n/${lang}.json`);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            this.translations[lang] = await res.json();
            return true;
        } catch (err) {
            console.error(`Failed to load translation file for '${lang}':`, err);
            if (lang !== 'en') return this.loadLanguage('en');
            return false;
        }
    },

    t(key, params = {}) {
        const keys = key.split('.');
        let val = this.translations[this.currentLang];
        
        for (const k of keys) {
            if (val && val[k] !== undefined) {
                val = val[k];
            } else {
                let fallback = this.translations['en'];
                for (const fk of keys) {
                    if (fallback && fallback[fk] !== undefined) {
                        fallback = fallback[fk];
                    } else {
                        fallback = null;
                        break;
                    }
                }
                val = fallback;
                break;
            }
        }

        if (typeof val !== 'string') {
            return params.default !== undefined ? params.default : key;
        }

        return val.replace(/\{(\w+)\}/g, (match, paramKey) => {
            return params[paramKey] !== undefined ? params[paramKey] : match;
        });
    },

    async setLanguage(lang, updateDOM = true) {
        const success = await this.loadLanguage(lang);
        if (success) {
            this.currentLang = lang;
            localStorage.setItem('selectedLang', lang);
            
            const selectEl = document.getElementById('languageSelect');
            if (selectEl) selectEl.value = lang;

            if (updateDOM) {
                this.applyDOMTranslations();
                refreshAllData();
            }
        }
    },

    applyDOMTranslations() {
        document.querySelectorAll('[data-i18n]').forEach(el => {
            const key = el.getAttribute('data-i18n');
            const translation = this.t(key);
            if (translation) {
                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                    el.placeholder = translation;
                } else {
                    el.textContent = translation;
                }
            }
        });

        document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
            const key = el.getAttribute('data-i18n-placeholder');
            const translation = this.t(key);
            if (translation) {
                el.placeholder = translation;
            }
        });
    },

    lookup(category, code) {
        if (!code) return '';
        const cleanCode = String(code).trim().toUpperCase();
        
        const catObj = this.translations[this.currentLang]?.[category] || this.translations['en']?.[category] || {};
        if (catObj[cleanCode]) return catObj[cleanCode];
        if (catObj[code]) return catObj[code];

        for (const k in catObj) {
            if (k.toUpperCase() === cleanCode) return catObj[k];
        }

        return code;
    }
};

// =========================================================================
// THEME SWITCHER MODULE
// =========================================================================
const theme = {
    currentMode: 'dark',

    init() {
        const savedTheme = localStorage.getItem('selectedTheme') || 'dark';
        this.setTheme(savedTheme);
    },

    setTheme(mode) {
        this.currentMode = mode;
        document.documentElement.setAttribute('data-theme', mode);
        localStorage.setItem('selectedTheme', mode);

        const iconEl = document.getElementById('themeIcon');
        const labelEl = document.getElementById('themeLabel');

        if (iconEl && labelEl) {
            if (mode === 'light') {
                iconEl.className = 'fa-solid fa-sun';
                labelEl.textContent = i18n.t('theme.light');
            } else {
                iconEl.className = 'fa-solid fa-moon';
                labelEl.textContent = i18n.t('theme.dark');
            }
        }
    },

    toggle() {
        const nextMode = this.currentMode === 'dark' ? 'light' : 'dark';
        this.setTheme(nextMode);
    }
};

// =========================================================================
// APPLICATION INITIALIZATION & ROUTING
// =========================================================================
document.addEventListener('DOMContentLoaded', async () => {
    theme.init();
    await i18n.init();
    
    setupNavigation();
    i18n.applyDOMTranslations();
    refreshAllData();
});

function setupNavigation() {
    const navLinks = document.querySelectorAll('.nav-item');
    navLinks.forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            const targetId = link.getAttribute('href').substring(1);
            showSection(targetId);
        });
    });
}

function showSection(sectionId) {
    activeSection = sectionId;
    
    document.querySelectorAll('.nav-item').forEach(item => {
        item.classList.remove('active');
    });
    
    let navId = 'nav-dashboard';
    if (sectionId === 'advisory') navId = 'nav-advisory';
    else if (sectionId === 'farmers') navId = 'nav-farmers';
    else if (sectionId === 'crops') navId = 'nav-crops';
    else if (sectionId === 'weather') navId = 'nav-weather';
    
    const targetLink = document.getElementById(navId);
    if (targetLink) targetLink.classList.add('active');

    const sections = {
        'dashboard': 'section-dashboard',
        'advisory': 'section-advisory',
        'farmers': 'section-farmers',
        'crops': 'section-crops',
        'weather': 'section-weather'
    };

    Object.keys(sections).forEach(key => {
        const el = document.getElementById(sections[key]);
        if (el) {
            if (key === sectionId) el.classList.remove('d-none');
            else el.classList.add('d-none');
        }
    });

    if (sectionId === 'farmers') loadFarmersList();
    if (sectionId === 'crops') loadCropsList();
    if (sectionId === 'weather') initOrUpdateMap();
}

// Global Data Refresh
async function refreshAllData() {
    try {
        await fetchStats();
        await fetchCropsDropdown();
        await fetchFarmersDropdown();
        if (activeSection === 'crops') loadCropsList();
        if (activeSection === 'farmers') loadFarmersList();
        
        // Auto-load weather for first farmer
        if (farmersCache.length > 0) {
            loadFarmerWeather(farmersCache[0].id);
        }
    } catch (err) {
        console.error("Error syncing data with backend: ", err);
    }
}

// Fetch Stats Count
async function fetchStats() {
    try {
        const res = await fetch(`${API_BASE}/status`);
        const status = await res.json();
        
        const cropsStat = document.getElementById('stat-crops');
        const farmersStat = document.getElementById('stat-farmers');

        if (cropsStat) cropsStat.textContent = status.cropsCount;
        if (farmersStat) farmersStat.textContent = status.farmersCount;
    } catch (err) {
        console.error("Failed to fetch status:", err);
    }
}

// Populate Farmer Dropdowns across Dashboard, Advisory, and Map
async function fetchFarmersDropdown() {
    try {
        const res = await fetch(`${API_BASE}/farmers`);
        const farmers = await res.json();
        farmersCache = farmers;

        const advisorySelect = document.getElementById('advisory-farmer');
        const dashWeatherSelect = document.getElementById('dashboard-farmer-select');
        const mapSelect = document.getElementById('map-farmer-select');

        if (advisorySelect) {
            const currentVal = advisorySelect.value;
            advisorySelect.innerHTML = `<option value="" disabled selected>${i18n.t('advisory.selectFarmerPlaceholder')}</option>`;
            farmers.forEach(f => {
                const soilName = i18n.lookup('soils', f.soil);
                advisorySelect.innerHTML += `<option value="${f.id}">${f.name} (${f.id}) - ${f.acres} Acres, ${f.location || soilName}</option>`;
            });
            if (currentVal) advisorySelect.value = currentVal;
        }

        [dashWeatherSelect, mapSelect].forEach(select => {
            if (select) {
                const currentVal = select.value;
                select.innerHTML = '';
                farmers.forEach(f => {
                    select.innerHTML += `<option value="${f.id}">${f.name} - ${f.location || 'Punjab'}</option>`;
                });
                if (currentVal) select.value = currentVal;
            }
        });
    } catch (err) {
        console.error("Error loading farmers dropdown:", err);
    }
}

// Populate Crops Cache
async function fetchCropsDropdown() {
    try {
        const res = await fetch(`${API_BASE}/crops`);
        const crops = await res.json();
        cropsCache = crops;
    } catch (err) {
        console.error("Error loading crops cache:", err);
    }
}

// =========================================================================
// REAL OPEN-METEO WEATHER INTEGRATION & LEAFLET MAP
// =========================================================================

async function loadFarmerWeather(farmerId) {
    const farmer = farmersCache.find(f => f.id === farmerId) || farmersCache[0];
    if (!farmer) return;

    const lat = farmer.latitude || 30.90;
    const lon = farmer.longitude || 75.85;

    try {
        const res = await fetch(`${API_BASE}/weather?lat=${lat}&lon=${lon}`);
        const data = await res.json();

        renderWeatherMetrics(data, farmer);
        if (activeSection === 'weather') {
            renderMapForFarmer(farmerId, data);
        }
    } catch (err) {
        console.error("Failed to load Open-Meteo weather data:", err);
    }
}

function renderWeatherMetrics(data, farmer) {
    if (!data || !data.current) return;

    const curr = data.current;
    const daily = data.daily || {};

    const tempEl = document.getElementById('dash-temp');
    const humEl = document.getElementById('dash-humidity');
    const rainEl = document.getElementById('dash-rain');
    const windEl = document.getElementById('dash-wind');
    const tickerText = document.getElementById('weather-ticker-text');

    if (tempEl) tempEl.textContent = `${curr.temperature_2m.toFixed(1)} °C`;
    if (humEl) humEl.textContent = `${curr.relative_humidity_2m} %`;
    
    const maxRainProb = daily.precipitation_probability_max ? daily.precipitation_probability_max[0] : 10;
    if (rainEl) rainEl.textContent = `${maxRainProb} %`;
    if (windEl) windEl.textContent = `${curr.wind_speed_10m.toFixed(1)} km/h`;

    // Ticker Text Update
    if (tickerText) {
        let alertMsg = `Location: ${farmer.location || 'Punjab'} | Temp: ${curr.temperature_2m}°C | Wind: ${curr.wind_speed_10m} km/h | Rain Chance: ${maxRainProb}%`;
        if (curr.temperature_2m > 38) alertMsg += " | ⚠️ HEATWAVE WARNING: Irrigate fields early morning!";
        if (maxRainProb > 60) alertMsg += " | 🌧️ HEAVY RAIN EXPECTED: Check field drainage!";
        tickerText.textContent = alertMsg;
    }

    // Render 5-Day Forecast Strip
    const forecastStrip = document.getElementById('dashboard-forecast-strip');
    if (forecastStrip && daily.time) {
        forecastStrip.innerHTML = '';
        for (let i = 0; i < Math.min(5, daily.time.length); i++) {
            const dateStr = new Date(daily.time[i]).toLocaleDateString(i18n.currentLang, { weekday: 'short', month: 'numeric', day: 'numeric' });
            const maxT = daily.temperature_2m_max[i].toFixed(0);
            const minT = daily.temperature_2m_min[i].toFixed(0);
            const rainP = daily.precipitation_probability_max[i];

            let iconClass = 'fa-sun text-warning';
            if (rainP > 50) iconClass = 'fa-cloud-showers-heavy text-blue';
            else if (rainP > 20) iconClass = 'fa-cloud-sun text-info';

            forecastStrip.innerHTML += `
                <div class="f-day-card">
                    <span class="f-day-title">${dateStr}</span>
                    <i class="fa-solid ${iconClass} f-icon"></i>
                    <span class="f-temp">${maxT}° / ${minT}°</span>
                    <span class="f-rain">💧 ${rainP}%</span>
                </div>
            `;
        }
    }
}

function initOrUpdateMap() {
    const select = document.getElementById('map-farmer-select');
    const farmerId = select ? select.value || (farmersCache[0] ? farmersCache[0].id : null) : null;
    if (farmerId) renderMapForFarmer(farmerId);
}

function renderMapForFarmer(farmerId, weatherData = null) {
    const farmer = farmersCache.find(f => f.id === farmerId) || farmersCache[0];
    if (!farmer) return;

    const lat = farmer.latitude || 30.90;
    const lon = farmer.longitude || 75.85;

    const mapContainer = document.getElementById('leaflet-map');
    if (!mapContainer) return;

    if (!leafletMap) {
        leafletMap = L.map('leaflet-map').setView([lat, lon], 10);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 18,
            attribution: '© OpenStreetMap contributors'
        }).addTo(leafletMap);
    } else {
        leafletMap.setView([lat, lon], 10);
    }

    if (leafletMarker) {
        leafletMap.removeLayer(leafletMarker);
    }

    const popupContent = `
        <div style="font-family:sans-serif; padding:4px;">
            <strong style="font-size:14px; color:#0f172a;">${farmer.name}</strong><br/>
            <span style="font-size:12px; color:#475569;">📍 ${farmer.location || 'Punjab'}</span><br/>
            <span style="font-size:12px; color:#059669;">🌾 ${farmer.acres} Acres (${farmer.soil} Soil)</span>
        </div>
    `;

    leafletMarker = L.marker([lat, lon]).addTo(leafletMap)
        .bindPopup(popupContent)
        .openPopup();
}

// Load Farmers Registry Table
async function loadFarmersList() {
    try {
        const res = await fetch(`${API_BASE}/farmers`);
        const farmers = await res.json();
        farmersCache = farmers;

        const tbody = document.getElementById('farmers-table-body');
        if (!tbody) return;
        tbody.innerHTML = '';

        if (farmers.length === 0) {
            tbody.innerHTML = `<tr><td colspan="9" class="text-muted text-center">No registered farmers found.</td></tr>`;
            return;
        }

        farmers.forEach(f => {
            const isSmall = f.acres <= 5.0;
            const typeLabel = isSmall ? i18n.t('farmers.typeSmall') : i18n.t('farmers.typeLarge');
            const subsidyPct = (f.subsidyRate * 100).toFixed(0);

            const soilLabel = i18n.lookup('soils', f.soil);
            const waterLabel = i18n.lookup('waterLevels', f.water);

            tbody.innerHTML += `
                <tr>
                    <td><strong>${f.id}</strong></td>
                    <td>${f.name}</td>
                    <td><span class="badge ${isSmall ? 'badge-pulse' : ''}">${typeLabel}</span></td>
                    <td>${f.acres} Acres</td>
                    <td>${f.location || 'Punjab'}</td>
                    <td>${soilLabel}</td>
                    <td>${waterLabel}</td>
                    <td><strong class="text-success">${subsidyPct}% Discount</strong></td>
                    <td>
                        <button class="btn btn-secondary btn-sm" onclick="viewHistory('${f.id}')">
                            <i class="fa-solid fa-clock-rotate-left"></i> ${i18n.t('farmers.historyBtn')}
                        </button>
                    </td>
                </tr>
            `;
        });
    } catch (err) {
        console.error("Error loading farmers table:", err);
    }
}

// Load Crop Catalog Table
async function loadCropsList() {
    try {
        const res = await fetch(`${API_BASE}/crops`);
        const crops = await res.json();
        cropsCache = crops;

        const tbody = document.getElementById('crops-table-body');
        if (!tbody) return;
        tbody.innerHTML = '';

        if (crops.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" class="text-muted text-center">No crops found in catalog.</td></tr>`;
            return;
        }

        crops.forEach(crop => {
            const isCash = crop.type === 'CashCrop';
            const typeLabel = isCash ? i18n.t('crops.typeCash') : i18n.t('crops.typeFood');
            const cropLocalizedName = i18n.lookup('cropNames', crop.name);
            const seasonLabel = i18n.lookup('seasons', crop.season);
            const waterLabel = i18n.lookup('waterLevels', crop.water);

            const soilsList = crop.soils.map(s => {
                const localizedSoil = i18n.lookup('soils', s);
                return `<span class="badge" style="background: rgba(255,255,255,0.08); margin-right:4px;">${localizedSoil}</span>`;
            }).join('');

            const marketPrice = crop.marketPrice ? `₹${crop.marketPrice.toFixed(0)}` : '₹20,000';

            tbody.innerHTML += `
                <tr>
                    <td><strong>${cropLocalizedName}</strong></td>
                    <td><span class="badge">${typeLabel}</span></td>
                    <td>${seasonLabel}</td>
                    <td>${waterLabel}</td>
                    <td>${crop.yield} Tons/Ac</td>
                    <td><strong class="text-success">${marketPrice}</strong></td>
                    <td>${soilsList}</td>
                    <td>U: ${crop.urea} | D: ${crop.dap} | M: ${crop.mop} Kg</td>
                </tr>
            `;
        });
    } catch (err) {
        console.error("Error loading crops table:", err);
    }
}

// Execute Suitability Analysis & Render Multilingual Receipt with ROI & Schemes
async function runAdvisory(event) {
    event.preventDefault();
    const farmerId = document.getElementById('advisory-farmer').value;
    const season = document.getElementById('advisory-season').value;

    if (!farmerId || !season) {
        alert(i18n.t('messages.fillAllFields'));
        return;
    }

    try {
        const res = await fetch(`${API_BASE}/advisory`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ farmerId, season })
        });

        if (!res.ok) {
            const errData = await res.json();
            alert(i18n.t('messages.errorOccurred') + (errData.error || 'Server Error'));
            return;
        }

        const data = await res.json();
        renderAdvisoryResults(data);
    } catch (err) {
        console.error("Advisory calculation error:", err);
        alert(i18n.t('messages.errorOccurred') + err.message);
    }
}

// Render Advisory Results + Financial ROI Economics + Government Schemes
function renderAdvisoryResults(data) {
    const emptyState = document.getElementById('advisory-empty-state');
    const resultsCard = document.getElementById('advisory-results-card');

    if (emptyState) emptyState.classList.add('d-none');
    if (resultsCard) resultsCard.classList.remove('d-none');

    const topBanner = document.getElementById('top-crop-banner');
    const rankingItems = document.getElementById('crops-ranking-items');
    const detailsPanel = document.getElementById('fertilizer-details-panel');
    const roiPanel = document.getElementById('roi-economics-panel');
    const schemesPanel = document.getElementById('schemes-panel');

    const selectedFarmerId = document.getElementById('advisory-farmer')?.value;
    const farmer = data.farmer || farmersCache.find(f => f.id === selectedFarmerId) || { acres: 1, subsidyRate: 0 };
    const topResult = data.results && data.results.length > 0 ? data.results[0] : null;

    if (!topResult) return;

    const grossCostVal = data.grossFertilizerCost !== undefined ? data.grossFertilizerCost : 0;
    const netCostVal = data.netFertilizerCost !== undefined ? data.netFertilizerCost : 0;
    const ureaKgVal = data.ureaKg !== undefined ? data.ureaKg : 0;
    const dapKgVal = data.dapKg !== undefined ? data.dapKg : 0;
    const mopKgVal = data.mopKg !== undefined ? data.mopKg : 0;

    const expectedRevenue = data.expectedRevenue !== undefined ? data.expectedRevenue : 0;
    const seedCost = data.seedCost !== undefined ? data.seedCost : 0;
    const laborCost = data.laborCost !== undefined ? data.laborCost : 0;
    const totalInputCost = data.totalInputCost !== undefined ? data.totalInputCost : 0;
    const netProfit = data.netProfit !== undefined ? data.netProfit : 0;
    const roiPct = data.roiPct !== undefined ? data.roiPct : 0;

    const cropName = i18n.lookup('cropNames', topResult.cropName);
    const summaryText = i18n.t('advisory.summaryText', {
        crop: cropName,
        score: topResult.score,
        acres: farmer.acres,
        yield: topResult.yield,
        netProfit: netProfit.toFixed(0),
        roi: roiPct.toFixed(1)
    });

    topBanner.innerHTML = `
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
            <h4 style="font-size:18px; margin:0;"><i class="fa-solid fa-trophy"></i> ${i18n.t('advisory.topMatch')}: <strong>${cropName}</strong></h4>
            <span style="font-size:24px; font-weight:800; background:rgba(255,255,255,0.2); padding:4px 14px; border-radius:12px;">${topResult.score}%</span>
        </div>
        <p style="font-size:14px; opacity:0.95; line-height:1.5;">${summaryText}</p>
    `;

    // Render Ranked List
    rankingItems.innerHTML = '';
    data.results.forEach((r, idx) => {
        const rCropName = i18n.lookup('cropNames', r.cropName);
        const isTop = idx === 0;
        rankingItems.innerHTML += `
            <div class="ranking-item ${isTop ? 'top-rank' : ''}">
                <div>
                    <strong>#${idx + 1} ${rCropName}</strong>
                    <div style="font-size:11px; color:var(--text-muted);">${r.classification}</div>
                </div>
                <div style="text-align:right;">
                    <strong style="color:${isTop ? 'var(--accent-green)' : 'var(--text-primary)'}; font-size:16px;">${r.score}%</strong>
                    <div style="font-size:11px; color:var(--text-muted);">${r.yield} ${i18n.t('advisory.tons')}</div>
                </div>
            </div>
        `;
    });

    // Render NPK Breakdown & Net Cost Receipt
    const subsidyDiscount = grossCostVal - netCostVal;
    detailsPanel.innerHTML = `
        <h4 style="font-size:15px; font-weight:700; color:var(--text-primary); margin-bottom:8px;">
            <i class="fa-solid fa-receipt"></i> ${i18n.t('advisory.resultTitle')}
        </h4>
        <div class="detail-row">
            <span>${i18n.t('advisory.yieldEst')}</span>
            <strong>${topResult.yield} ${i18n.t('advisory.tons')}</strong>
        </div>
        <div class="detail-row">
            <span>${i18n.t('advisory.npkReq')}</span>
            <strong>U: ${ureaKgVal.toFixed(1)} | D: ${dapKgVal.toFixed(1)} | M: ${mopKgVal.toFixed(1)} ${i18n.t('advisory.kg')}</strong>
        </div>
        <div class="detail-row">
            <span>${i18n.t('advisory.grossCost')}</span>
            <span>₹${grossCostVal.toFixed(2)}</span>
        </div>
        <div class="detail-row" style="color:var(--accent-green);">
            <span>${i18n.t('advisory.subsidy')} (${((farmer.subsidyRate || 0) * 100).toFixed(0)}%)</span>
            <span>- ₹${subsidyDiscount.toFixed(2)}</span>
        </div>
        <div class="detail-row net-payable">
            <span>${i18n.t('advisory.netCost')}</span>
            <span>₹${netCostVal.toFixed(2)}</span>
        </div>
    `;

    // Render ROI Economics Card
    if (roiPanel) {
        const isProfit = netProfit >= 0;
        roiPanel.innerHTML = `
            <h4 style="font-size:15px; font-weight:700; color:var(--text-primary); margin-bottom:10px;">
                <i class="fa-solid fa-chart-line text-success"></i> ${i18n.t('advisory.economicsTitle')}
            </h4>
            <div class="roi-grid">
                <div class="roi-box">
                    <span class="roi-box-val text-success">₹${expectedRevenue.toLocaleString('en-IN', {maximumFractionDigits: 0})}</span>
                    <span class="roi-box-lbl">${i18n.t('advisory.expectedRevenue')}</span>
                </div>
                <div class="roi-box">
                    <span class="roi-box-val text-warning">₹${totalInputCost.toLocaleString('en-IN', {maximumFractionDigits: 0})}</span>
                    <span class="roi-box-lbl">${i18n.t('advisory.totalInputCost')}</span>
                </div>
                <div class="roi-box">
                    <span class="roi-box-val ${isProfit ? 'text-success' : 'text-danger'}">₹${netProfit.toLocaleString('en-IN', {maximumFractionDigits: 0})}</span>
                    <span class="roi-box-lbl">${i18n.t('advisory.netProfit')}</span>
                </div>
                <div class="roi-box">
                    <span class="roi-box-val ${isProfit ? 'text-success' : 'text-danger'}">${roiPct.toFixed(1)}%</span>
                    <span class="roi-box-lbl">${i18n.t('advisory.roi')}</span>
                </div>
            </div>
        `;
    }

    // Render Government Schemes Card
    if (schemesPanel) {
        const schemesList = data.schemes || ['pmkisan', 'pmfby', 'smam'];
        const schemesHTML = schemesList.map(s => `
            <div class="scheme-item">
                <span class="scheme-title"><i class="fa-solid fa-award"></i> ${i18n.t('schemes.' + s + 'Name')}</span>
                <span class="scheme-desc">${i18n.t('schemes.' + s + 'Desc')}</span>
            </div>
        `).join('');

        schemesPanel.innerHTML = `
            <h4 style="font-size:15px; font-weight:700; color:var(--text-primary); margin-bottom:10px;">
                <i class="fa-solid fa-hand-holding-hand text-info"></i> ${i18n.t('advisory.schemesTitle')}
            </h4>
            <div class="schemes-list">${schemesHTML}</div>
        `;
    }
}

// Modal Handlers
function showModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) modal.classList.remove('d-none');
}

function hideModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) modal.classList.add('d-none');
}

// Submit Register Farmer Form
async function submitFarmer(event) {
    event.preventDefault();
    const id = document.getElementById('farmer-id').value;
    const name = document.getElementById('farmer-name').value;
    const acres = document.getElementById('farmer-acres').value;
    const classChoice = document.getElementById('farmer-class').value;
    const location = document.getElementById('farmer-location').value;
    const lat = document.getElementById('farmer-lat').value;
    const lon = document.getElementById('farmer-lon').value;
    const soil = document.getElementById('farmer-soil').value;
    const water = document.getElementById('farmer-water').value;

    try {
        const res = await fetch(`${API_BASE}/farmers`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id, name, acres, classChoice, soil, water, location, latitude: lat, longitude: lon })
        });

        if (res.ok) {
            alert(i18n.t('messages.farmerAdded'));
            hideModal('register-farmer-modal');
            document.getElementById('register-farmer-form').reset();
            refreshAllData();
        } else {
            const err = await res.json();
            alert(i18n.t('messages.errorOccurred') + (err.error || 'Failed'));
        }
    } catch (err) {
        alert(i18n.t('messages.errorOccurred') + err.message);
    }
}

// Submit Add Crop Form
async function submitCrop(event) {
    event.preventDefault();
    const name = document.getElementById('crop-name').value;
    const type = document.getElementById('crop-type').value;
    const season = document.getElementById('crop-season').value;
    const water = document.getElementById('crop-water').value;
    const yieldVal = document.getElementById('crop-yield').value;
    const marketPrice = document.getElementById('crop-price').value;
    const seedCost = document.getElementById('crop-seed-cost').value;
    const special = document.getElementById('crop-special').value;
    const urea = document.getElementById('crop-urea').value;
    const dap = document.getElementById('crop-dap').value;
    const mop = document.getElementById('crop-mop').value;

    const soilsChecked = Array.from(document.querySelectorAll('input[name="crop-soils"]:checked')).map(cb => cb.value);

    if (soilsChecked.length === 0) {
        alert("Please select at least one suitable soil profile.");
        return;
    }

    try {
        const res = await fetch(`${API_BASE}/crops`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name, type, season, water,
                soils: soilsChecked.join(';'),
                yield: yieldVal, special, urea, dap, mop,
                marketPrice, seedCost
            })
        });

        if (res.ok) {
            alert(i18n.t('messages.cropAdded'));
            hideModal('add-crop-modal');
            document.getElementById('add-crop-form').reset();
            refreshAllData();
        } else {
            const err = await res.json();
            alert(i18n.t('messages.errorOccurred') + (err.error || 'Failed'));
        }
    } catch (err) {
        alert(i18n.t('messages.errorOccurred') + err.message);
    }
}

// View Farmer Advisory History Modal
async function viewHistory(farmerId) {
    try {
        const res = await fetch(`${API_BASE}/farmers/${farmerId}/history`);
        const records = await res.json();

        const farmerName = farmersCache.find(f => f.id === farmerId)?.name || farmerId;
        document.getElementById('history-farmer-name').textContent = farmerName;

        const tbody = document.getElementById('history-table-body');
        tbody.innerHTML = '';

        if (records.length === 0) {
            tbody.innerHTML = `<tr><td colspan="4" class="text-muted text-center">${i18n.t('history.noHistory')}</td></tr>`;
        } else {
            records.forEach(r => {
                const cName = i18n.lookup('cropNames', r.cropName);
                tbody.innerHTML += `
                    <tr>
                        <td><strong>${cName}</strong></td>
                        <td><span class="badge badge-success">${r.score}%</span></td>
                        <td>${r.yield} ${i18n.t('advisory.tons')}</td>
                        <td>${r.date}</td>
                    </tr>
                `;
            });
        }

        showModal('view-history-modal');
    } catch (err) {
        console.error("Error fetching farmer history:", err);
    }
}

function toggleSpecialInput(val) {
    const label = document.getElementById('special-label');
    if (label) {
        label.textContent = val === 'CashCrop' ? 'Target Industry (e.g. Textile, Oil)' : 'Food Category (e.g. Cereal, Fruit)';
    }
}
