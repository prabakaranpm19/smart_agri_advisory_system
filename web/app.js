// API Base Endpoint
const API_BASE = '/api';

// Global Application State
let sessionToken = localStorage.getItem('authToken') || null;
let currentUser = null;
let currentFarmer = null;
let activeSection = 'landing';
let tnDistricts = {};
let cropsCache = [];
let farmersCache = [];
let leafletMap = null;
let leafletMarkers = {};
let currentMapDistrict = 'Thanjavur';

// =========================================================================
// I18N SYSTEM
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
                if (currentUser) {
                    // Update user language preference on backend
                    fetchApi('/me', { method: 'PUT', body: JSON.stringify({ language: lang }) }).catch(() => {});
                }
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
    }
};

// =========================================================================
// THEME SWITCHER
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
        
        const label = document.getElementById('themeLabel');
        const icon = document.getElementById('themeIcon');
        if (label && icon) {
            if (mode === 'light') {
                label.textContent = 'Light Mode';
                icon.className = 'fa-solid fa-sun';
            } else {
                label.textContent = 'Dark Mode';
                icon.className = 'fa-solid fa-moon';
            }
        }
    },

    toggle() {
        this.setTheme(this.currentMode === 'dark' ? 'light' : 'dark');
    }
};

// =========================================================================
// API HELPER FUNCTION (WITH TOKEN AUTHENTICATION)
// =========================================================================
async function fetchApi(endpoint, options = {}) {
    const url = API_BASE + endpoint;
    const headers = {
        'Content-Type': 'application/json',
        ...(options.headers || {})
    };

    if (sessionToken) {
        headers['Authorization'] = `Bearer ${sessionToken}`;
    }

    try {
        const response = await fetch(url, { ...options, headers });
        const data = await response.json();

        if (response.status === 401) {
            // Unauthorized - clear token and return to landing page
            handleUnauthorized();
            throw new Error(data.error || 'Session expired. Please sign in again.');
        }

        if (!response.ok) {
            throw new Error(data.error || `HTTP ${response.status}`);
        }

        return data;
    } catch (err) {
        console.error(`API Error [${endpoint}]:`, err.message);
        throw err;
    }
}

function handleUnauthorized() {
    sessionToken = null;
    currentUser = null;
    currentFarmer = null;
    localStorage.removeItem('authToken');
    updateAuthHeader();
    renderSidebarNav();
    showSection('landing');
}

// =========================================================================
// APP INITIALIZATION
// =========================================================================
document.addEventListener('DOMContentLoaded', async () => {
    theme.init();
    await i18n.init();

    await loadServerStatus();
    await loadDistricts();

    // Check if token exists
    if (sessionToken) {
        try {
            const meData = await fetchApi('/me');
            currentUser = meData.user;
            currentFarmer = meData.farmer;

            if (currentUser.language) {
                await i18n.setLanguage(currentUser.language, true);
            }

            updateAuthHeader();
            renderSidebarNav();

            if (!currentFarmer || currentFarmer.acres <= 0) {
                showSection('profile-setup');
            } else {
                showSection('dashboard');
                loadDashboardData();
            }
        } catch (err) {
            handleUnauthorized();
        }
    } else {
        updateAuthHeader();
        renderSidebarNav();
        showSection('landing');
    }

    // Refresh live weather alerts every 30s
    setInterval(loadLiveAlerts, 30000);
    loadLiveAlerts();
});

async function loadServerStatus() {
    try {
        const res = await fetchApi('/status');
        const badge = document.getElementById('dbStatusText');
        if (badge) {
            badge.textContent = res.jdbcActive ? 'SQLite (JDBC) Active' : 'Flat File (CSV Backup)';
        }
    } catch (e) {}
}

async function loadDistricts() {
    try {
        const data = await fetchApi('/districts');
        tnDistricts = {};
        data.forEach(d => {
            tnDistricts[d.name] = d;
        });
        populateDistrictDropdowns();
    } catch (e) {}
}

function populateDistrictDropdowns() {
    const setupSelect = document.getElementById('setupDistrict');
    const profileSelect = document.getElementById('profileDistrict');
    const mapSelect = document.getElementById('mapDistrictSelector');

    let html = '';
    Object.keys(tnDistricts).forEach(name => {
        html += `<option value="${name}">${name}</option>`;
    });

    if (setupSelect) setupSelect.innerHTML = html;
    if (profileSelect) profileSelect.innerHTML = html;
    if (mapSelect) mapSelect.innerHTML = `<option value="Global" selected>🌍 World View (Windy)</option>` + html;
}

// =========================================================================
// NAVIGATION & SECTION SWITCHING
// =========================================================================
function renderSidebarNav() {
    const navMenu = document.getElementById('navMenu');
    if (!navMenu) return;

    if (!currentUser) {
        // Landing Page / Unauthenticated Nav
        navMenu.innerHTML = `
            <a href="#landing" class="nav-item active" onclick="showSection('landing')">
                <i class="fa-solid fa-house"></i> <span>Home / App Intro</span>
            </a>
            <a href="#weather" class="nav-item" onclick="showSection('weather')">
                <i class="fa-solid fa-cloud-sun-rain"></i> <span>Weather Map</span>
            </a>
            <a href="#auth" class="nav-item nav-highlight" onclick="openAuthModal('login')">
                <i class="fa-solid fa-right-to-bracket"></i> <span>Sign In / Register</span>
            </a>
        `;
        return;
    }

    // Logged In Farmer / Admin Nav
    let html = `
        <a href="#dashboard" class="nav-item ${activeSection === 'dashboard' ? 'active' : ''}" onclick="showSection('dashboard')">
            <i class="fa-solid fa-chart-line"></i> <span data-i18n="nav.dashboard">Dashboard</span>
        </a>
        <a href="#advisory" class="nav-item ${activeSection === 'advisory' ? 'active' : ''}" onclick="showSection('advisory')">
            <i class="fa-solid fa-wand-magic-sparkles"></i> <span data-i18n="nav.advisory">Get Advisory</span>
        </a>
        <a href="#history" class="nav-item ${activeSection === 'history' ? 'active' : ''}" onclick="showSection('history')">
            <i class="fa-solid fa-clock-rotate-left"></i> <span data-i18n="nav.history">Advisory History</span>
        </a>
        <a href="#weather" class="nav-item ${activeSection === 'weather' ? 'active' : ''}" onclick="showSection('weather')">
            <i class="fa-solid fa-map-location-dot"></i> <span data-i18n="nav.weather">Weather Map</span>
        </a>
        <a href="#alerts" class="nav-item ${activeSection === 'alerts' ? 'active' : ''}" onclick="showSection('alerts')">
            <i class="fa-solid fa-triangle-exclamation"></i> <span data-i18n="nav.alerts">Risk Alerts</span>
        </a>
        <a href="#water" class="nav-item ${activeSection === 'water' ? 'active' : ''}" onclick="showSection('water')">
            <i class="fa-solid fa-faucet-drip"></i> <span data-i18n="nav.water">Water Allocation</span>
        </a>
        <a href="#profile" class="nav-item ${activeSection === 'profile' ? 'active' : ''}" onclick="showSection('profile')">
            <i class="fa-solid fa-user-gear"></i> <span data-i18n="nav.profile">Farm Profile</span>
        </a>
    `;

    if (currentUser.role === 'ADMIN') {
        html += `
            <div class="sidebar-divider" style="border-top:1px solid var(--border-glass); margin: 12px 0;"></div>
            <a href="#admin-crops" class="nav-item ${activeSection === 'admin-crops' ? 'active' : ''}" onclick="showSection('admin-crops')">
                <i class="fa-solid fa-wheat-awn"></i> <span data-i18n="nav.adminCrops">Manage Crops (Admin)</span>
            </a>
            <a href="#admin-farmers" class="nav-item ${activeSection === 'admin-farmers' ? 'active' : ''}" onclick="showSection('admin-farmers')">
                <i class="fa-solid fa-users"></i> <span data-i18n="nav.adminFarmers">All Farmers (Admin)</span>
            </a>
        `;
    }

    html += `
        <div class="sidebar-divider" style="border-top:1px solid var(--border-glass); margin: 12px 0;"></div>
        <a href="#logout" class="nav-item text-danger" onclick="handleLogout()">
            <i class="fa-solid fa-right-from-bracket"></i> <span data-i18n="nav.logout">Sign Out</span>
        </a>
    `;

    navMenu.innerHTML = html;
    i18n.applyDOMTranslations();
}

function showSection(sectionId) {
    if (!currentUser && sectionId !== 'landing' && sectionId !== 'weather') {
        openAuthModal('login');
        return;
    }

    activeSection = sectionId;

    document.querySelectorAll('.content-section').forEach(sec => {
        sec.classList.remove('active');
    });

    const target = document.getElementById(`section-${sectionId}`);
    if (target) {
        target.classList.add('active');
    }

    renderSidebarNav();

    // Trigger specific page initializations
    if (sectionId === 'dashboard') loadDashboardData();
    if (sectionId === 'history') loadAdvisoryHistory();
    if (sectionId === 'weather') {
        initWeatherMap();
        setTimeout(() => {
            if (leafletMap) leafletMap.invalidateSize();
            resetWindyCanvasSize();
        }, 150);
    }
    if (sectionId === 'alerts') loadLiveAlerts();
    if (sectionId === 'profile') fillProfileForm();
    if (sectionId === 'admin-crops') loadAdminCrops();
    if (sectionId === 'admin-farmers') loadAdminFarmers();
}

function updateAuthHeader() {
    const container = document.getElementById('authHeaderContainer');
    if (!container) return;

    if (currentUser) {
        const name = currentFarmer ? currentFarmer.name : currentUser.username;
        container.innerHTML = `
            <div class="btn-auth-pill logged-in" onclick="showSection('profile')">
                <i class="fa-solid fa-user-check"></i>
                <span>${escapeHtml(name)} (${currentUser.role})</span>
            </div>
        `;
    } else {
        container.innerHTML = `
            <button class="btn-auth-pill" onclick="openAuthModal('login')">
                <i class="fa-solid fa-circle-user"></i>
                <span data-i18n="landing.signInBtn">Sign In / Account</span>
            </button>
        `;
        i18n.applyDOMTranslations();
    }
}

// =========================================================================
// AUTHENTICATION MODAL & LOGIC
// =========================================================================
function openAuthModal(tab = 'login') {
    switchAuthTab(tab);
    document.getElementById('authModal').classList.add('active');
}

function closeAuthModal() {
    document.getElementById('authModal').classList.remove('active');
}

function switchAuthTab(tab) {
    const loginBtn = document.getElementById('loginTabBtn');
    const signupBtn = document.getElementById('signupTabBtn');
    const loginForm = document.getElementById('loginForm');
    const signupForm = document.getElementById('signupForm');
    const title = document.getElementById('authModalTitle');

    if (tab === 'login') {
        loginBtn.classList.add('active');
        signupBtn.classList.remove('active');
        loginForm.classList.remove('hidden');
        signupForm.classList.add('hidden');
        if (title) title.textContent = i18n.t('auth.signInTitle');
    } else {
        signupBtn.classList.add('active');
        loginBtn.classList.remove('active');
        signupForm.classList.remove('hidden');
        loginForm.classList.add('hidden');
        if (title) title.textContent = i18n.t('auth.signUpTitle');
    }
}

function quickFillDemo(username, pin) {
    openAuthModal('login');
    document.getElementById('loginUsername').value = username;
    document.getElementById('loginPin').value = pin;
}

async function handleLogin(e) {
    e.preventDefault();
    const username = document.getElementById('loginUsername').value.trim();
    const pin = document.getElementById('loginPin').value.trim();

    try {
        const res = await fetchApi('/auth/login', {
            method: 'POST',
            body: JSON.stringify({ username, pin })
        });

        sessionToken = res.token;
        currentUser = res.user;
        currentFarmer = res.farmer;
        localStorage.setItem('authToken', sessionToken);

        if (currentUser.language) {
            await i18n.setLanguage(currentUser.language, true);
        }

        closeAuthModal();
        updateAuthHeader();
        renderSidebarNav();

        if (!currentFarmer || currentFarmer.acres <= 0) {
            showSection('profile-setup');
        } else {
            showSection('dashboard');
        }
    } catch (err) {
        alert(err.message || 'Login failed.');
    }
}

async function handleSignup(e) {
    e.preventDefault();
    const name = document.getElementById('signupName').value.trim();
    const username = document.getElementById('signupUsername').value.trim();
    const pin = document.getElementById('signupPin').value.trim();
    const lang = i18n.currentLang;

    try {
        const res = await fetchApi('/auth/register', {
            method: 'POST',
            body: JSON.stringify({ name, username, pin, language: lang })
        });

        sessionToken = res.token;
        currentUser = res.user;
        currentFarmer = res.farmer;
        localStorage.setItem('authToken', sessionToken);

        closeAuthModal();
        updateAuthHeader();
        renderSidebarNav();

        // Direct first-time user to Farm Profile Setup
        showSection('profile-setup');
    } catch (err) {
        alert(err.message || 'Registration failed.');
    }
}

async function handleLogout() {
    try {
        if (sessionToken) {
            await fetchApi('/auth/logout', { method: 'POST' });
        }
    } catch (e) {}

    sessionToken = null;
    currentUser = null;
    currentFarmer = null;
    localStorage.removeItem('authToken');
    updateAuthHeader();
    renderSidebarNav();
    showSection('landing');
}

// =========================================================================
// FARM PROFILE SETUP & EDIT
// =========================================================================
function onSetupDistrictChange(districtName) {
    const distInfo = tnDistricts[districtName];
    if (distInfo) {
        const setupSoil = document.getElementById('setupSoil');
        const profileSoil = document.getElementById('profileSoil');
        if (setupSoil) setupSoil.value = distInfo.defaultSoil;
        if (profileSoil) profileSoil.value = distInfo.defaultSoil;
    }
}

function onSetupAcresChange(acresVal) {
    const val = parseFloat(acresVal) || 0;
    const banner = document.getElementById('categoryPreviewText');
    if (!banner) return;

    if (val <= 5.0) {
        banner.textContent = i18n.t('profileSetup.smallBadge');
    } else {
        banner.textContent = i18n.t('profileSetup.largeBadge');
    }
}

async function saveFarmProfile(e) {
    e.preventDefault();
    const isEdit = activeSection === 'profile';
    const name = document.getElementById(isEdit ? 'profileName' : 'setupDistrict').form ?
                 (isEdit ? document.getElementById('profileName').value : (currentFarmer ? currentFarmer.name : currentUser.username)) : '';
    const district = document.getElementById(isEdit ? 'profileDistrict' : 'setupDistrict').value;
    const soil = document.getElementById(isEdit ? 'profileSoil' : 'setupSoil').value;
    const acres = parseFloat(document.getElementById(isEdit ? 'profileAcres' : 'setupAcres').value) || 3.5;
    const water = document.getElementById(isEdit ? 'profileWater' : 'setupWater').value;
    const source = document.getElementById(isEdit ? 'profileSource' : 'setupSource').value;

    try {
        const res = await fetchApi('/me', {
            method: 'PUT',
            body: JSON.stringify({
                name: name.trim(),
                district,
                soil,
                acres,
                water,
                irrigationSource: source,
                language: i18n.currentLang
            })
        });

        currentUser = res.user;
        currentFarmer = res.farmer;
        updateAuthHeader();

        alert(i18n.t('messages.profileSaved'));
        showSection('dashboard');
    } catch (err) {
        alert(err.message || 'Failed to save profile.');
    }
}

function fillProfileForm() {
    if (!currentFarmer) return;
    const nameEl = document.getElementById('profileName');
    const distEl = document.getElementById('profileDistrict');
    const soilEl = document.getElementById('profileSoil');
    const acresEl = document.getElementById('profileAcres');
    const waterEl = document.getElementById('profileWater');
    const sourceEl = document.getElementById('profileSource');

    if (nameEl) nameEl.value = currentFarmer.name;
    if (distEl) distEl.value = currentFarmer.district;
    if (soilEl) soilEl.value = currentFarmer.soil;
    if (acresEl) acresEl.value = currentFarmer.acres;
    if (waterEl) waterEl.value = currentFarmer.water;
    if (sourceEl) sourceEl.value = currentFarmer.irrigationSource || 'Canal';
}

// =========================================================================
// DASHBOARD RENDERING
// =========================================================================
async function loadDashboardData() {
    if (!currentFarmer) return;

    // Render Farmer Profile Summary
    document.getElementById('dashFarmerName').textContent = currentFarmer.name;
    document.getElementById('dashDistrict').textContent = currentFarmer.district;
    document.getElementById('dashSoil').textContent = i18n.t(`soils.${currentFarmer.soil}`, { default: currentFarmer.soil });
    document.getElementById('dashAcres').textContent = `${currentFarmer.acres} Acres`;
    document.getElementById('dashWater').textContent = i18n.t(`waterLevels.${currentFarmer.water}`, { default: currentFarmer.water });
    document.getElementById('dashCategory').textContent = currentFarmer.type;
    document.getElementById('dashSubsidyRate').textContent = `${Math.round(currentFarmer.subsidyRate * 100)}% NPK Subsidy`;

    document.getElementById('weatherDistBadge').textContent = currentFarmer.district;

    // Load Live Weather for Farmer's District or fallback mock
    try {
        const weather = await fetchApi(`/weather?district=${encodeURIComponent(currentFarmer.district || 'Thanjavur')}`);
        if (weather && weather.current) {
            const tempVal = typeof weather.current.temperature_2m === 'number' ? weather.current.temperature_2m.toFixed(1) : '31.1';
            const humidityVal = weather.current.relative_humidity_2m || 68;
            const windKm = weather.current.wind_speed_10m || 25.9;
            const windKt = (windKm * 0.539957).toFixed(1);

            document.getElementById('dashTemp').textContent = `${tempVal} °C`;
            document.getElementById('dashHumidity').textContent = `${humidityVal} %`;
            document.getElementById('dashWind').textContent = `${windKt} kt (${windKm} km/h)`;
            if (weather.daily && weather.daily.precipitation_probability_max) {
                document.getElementById('dashRain').textContent = `${weather.daily.precipitation_probability_max[0]} %`;
            } else {
                document.getElementById('dashRain').textContent = '15 %';
            }
        } else {
            document.getElementById('dashTemp').textContent = '31.1 °C';
            document.getElementById('dashHumidity').textContent = '68 %';
            document.getElementById('dashWind').textContent = '14.0 kt (25.9 km/h)';
            document.getElementById('dashRain').textContent = '15 %';
        }
    } catch (e) {
        document.getElementById('dashTemp').textContent = '31.1 °C';
        document.getElementById('dashHumidity').textContent = '68 %';
        document.getElementById('dashWind').textContent = '14.0 kt (25.9 km/h)';
        document.getElementById('dashRain').textContent = '15 %';
    }
}

// =========================================================================
// GET ADVISORY ENGINE
// =========================================================================
async function runPersonalAdvisory() {
    if (!currentFarmer || currentFarmer.acres <= 0) {
        alert('Please complete your Farm Profile Setup first.');
        showSection('profile-setup');
        return;
    }

    const season = document.getElementById('advisorySeason').value;
    const resultsWrapper = document.getElementById('advisoryResultsContainer');

    try {
        const report = await fetchApi('/advisory', {
            method: 'POST',
            body: JSON.stringify({ season })
        });

        // Show Results Wrapper
        resultsWrapper.classList.remove('hidden');

        // Render Top Recommended Crop Hero
        const top = report.topCrop;
        if (top) {
            document.getElementById('topCropName').textContent = top.name;
            document.getElementById('topCropClassification').textContent = top.classification;
            document.getElementById('topCropScore').textContent = report.rankedCrops[0] ? report.rankedCrops[0].score : 90;

            document.getElementById('topYield').textContent = `${report.expectedYield.toFixed(2)} Qt (for ${currentFarmer.acres} Acres)`;
            document.getElementById('topUrea').textContent = `${report.ureaKg.toFixed(1)} Kg`;
            document.getElementById('topDap').textContent = `${report.dapKg.toFixed(1)} Kg`;
            document.getElementById('topMop').textContent = `${report.mopKg.toFixed(1)} Kg`;

            document.getElementById('finGrossCost').textContent = `₹${report.grossFertilizerCost.toLocaleString('en-IN', {minimumFractionDigits: 2})}`;
            document.getElementById('finSubsidy').textContent = `-₹${report.subsidySavings.toLocaleString('en-IN', {minimumFractionDigits: 2})} (${Math.round(currentFarmer.subsidyRate * 100)}%)`;
            document.getElementById('finNetCost').textContent = `₹${report.netFertilizerCost.toLocaleString('en-IN', {minimumFractionDigits: 2})}`;
            document.getElementById('finSeedCost').textContent = `₹${report.seedCost.toLocaleString('en-IN', {minimumFractionDigits: 2})}`;
            document.getElementById('finLaborCost').textContent = `₹${report.laborCost.toLocaleString('en-IN', {minimumFractionDigits: 2})}`;
            document.getElementById('finTotalCost').textContent = `₹${report.totalInputCost.toLocaleString('en-IN', {minimumFractionDigits: 2})}`;
            document.getElementById('finRevenue').textContent = `₹${report.grossRevenue.toLocaleString('en-IN', {minimumFractionDigits: 2})}`;
            document.getElementById('finProfit').textContent = `₹${report.netProfit.toLocaleString('en-IN', {minimumFractionDigits: 2})}`;
            document.getElementById('finRoi').textContent = `${report.roiPercent.toFixed(2)}%`;
        }

        // Render Advice Notes
        const notesList = document.getElementById('adviceNotesList');
        if (notesList) {
            notesList.innerHTML = report.adviceNotes.map(n => `<li><i class="fa-solid fa-angle-right text-success"></i> ${escapeHtml(n)}</li>`).join('');
        }

        // Render Schemes List
        const schemesList = document.getElementById('schemesList');
        if (schemesList) {
            schemesList.innerHTML = report.applicableSchemes.map(s => `<li><i class="fa-solid fa-check text-primary"></i> ${escapeHtml(s)}</li>`).join('');
        }

        // Render Ranked Crops Table
        const tbody = document.getElementById('rankedCropsTableBody');
        if (tbody) {
            tbody.innerHTML = report.rankedCrops.map((c, i) => `
                <tr>
                    <td><strong>#${i + 1}</strong></td>
                    <td><strong class="text-primary">${escapeHtml(c.cropName)}</strong></td>
                    <td>${escapeHtml(c.type)}</td>
                    <td><span class="badge ${c.score >= 80 ? 'badge-success' : 'badge-warning'}">${c.score}%</span></td>
                    <td>${c.yield.toFixed(1)} Qt</td>
                    <td><small class="text-secondary">${escapeHtml(c.reason)}</small></td>
                </tr>
            `).join('');
        }
    } catch (err) {
        alert(err.message || 'Failed to generate advisory.');
    }
}

// =========================================================================
// ADVISORY HISTORY
// =========================================================================
async function loadAdvisoryHistory() {
    const tbody = document.getElementById('historyTableBody');
    if (!tbody) return;

    try {
        const history = await fetchApi('/advisories');
        if (!history || history.length === 0) {
            tbody.innerHTML = `<tr><td colspan="7" class="text-center text-secondary py-4" data-i18n="history.empty">No past advisory reports found. Click 'Get Advisory' to generate your first report!</td></tr>`;
            i18n.applyDOMTranslations();
            return;
        }

        tbody.innerHTML = history.map(item => `
            <tr>
                <td>${new Date(item.date).toLocaleDateString()}</td>
                <td><strong class="text-success">${escapeHtml(item.cropName)}</strong></td>
                <td>${escapeHtml(item.season)}</td>
                <td><span class="badge badge-success">${item.score}%</span></td>
                <td class="text-success">₹${item.profit ? item.profit.toLocaleString('en-IN', {minimumFractionDigits: 2}) : '--'}</td>
                <td class="text-warning">${item.roi ? item.roi.toFixed(1) : '--'}%</td>
                <td>
                    <button class="btn btn-secondary btn-sm" onclick="viewAdvisoryDetail('${item.id}')">
                        <i class="fa-solid fa-eye"></i> <span data-i18n="history.viewReport">View Report</span>
                    </button>
                </td>
            </tr>
        `).join('');

        i18n.applyDOMTranslations();
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="7" class="text-center text-danger">Failed to load advisory history.</td></tr>`;
    }
}

async function viewAdvisoryDetail(recordId) {
    try {
        const item = await fetchApi(`/advisories/${recordId}`);
        const modalBody = document.getElementById('reportModalBody');

        modalBody.innerHTML = `
            <div class="report-detail-card">
                <div class="flex-between border-bottom pb-3 mb-3">
                    <div>
                        <h3>Report ID: ${item.id}</h3>
                        <p class="text-secondary">Date: ${new Date(item.date).toLocaleString()}</p>
                    </div>
                    <span class="badge badge-success btn-lg">${item.score}% Match Score</span>
                </div>

                <div class="form-grid mb-3">
                    <div class="detail-item"><span>Recommended Crop:</span> <strong>${escapeHtml(item.cropName)}</strong></div>
                    <div class="detail-item"><span>Season:</span> <strong>${escapeHtml(item.season)}</strong></div>
                    <div class="detail-item"><span>Estimated Yield:</span> <strong>${item.yield.toFixed(2)} Qt</strong></div>
                    <div class="detail-item"><span>Net Fertilizer Cost:</span> <strong>₹${item.netCost.toLocaleString('en-IN')}</strong></div>
                    <div class="detail-item"><span>Gross Revenue:</span> <strong>₹${item.revenue.toLocaleString('en-IN')}</strong></div>
                    <div class="detail-item"><span>Estimated Net Profit:</span> <strong class="text-success">₹${item.profit.toLocaleString('en-IN')}</strong></div>
                    <div class="detail-item span-2"><span>Return on Investment (ROI):</span> <strong class="text-warning">${item.roi.toFixed(2)}%</strong></div>
                </div>

                ${item.notes ? `
                    <div class="card inner-card mt-3">
                        <h4>Advice Notes & Telemetry</h4>
                        <pre style="white-space:pre-wrap; font-family:inherit; color:var(--text-secondary);">${escapeHtml(item.notes)}</pre>
                    </div>
                ` : ''}
            </div>
        `;

        document.getElementById('reportModal').classList.add('active');
    } catch (err) {
        alert('Failed to load advisory detail.');
    }
}

function closeReportModal() {
    document.getElementById('reportModal').classList.remove('active');
}

// =========================================================================
// =========================================================================
// WINDY INTERACTIVE WEATHER STUDIO ENGINE
// Grid Interpolation, Particle Vector Streamlines, Layer Switcher, Time Scrubber
// =========================================================================
// Grid Interpolation, Streamline Particles, Layer Switcher, Unit Converter, Time Scrubber
// =========================================================================
let windyActiveLayer = 'wind';
let windyHour = 0;
let windyGridData = null;
let windyParticles = [];
let windyParticlesVisible = true;
let windyAnimFrameId = null;
let windyTimelineTimer = null;
let gridFetchDebounceTimer = null;
let searchDebounceTimer = null;
let clickPinMarker = null;
let currentTileLayer = null;
let currentTileProviderIdx = 0;
let lastClickedPointData = null;

let activeUnits = {
    wind: 'kt',
    temp: '°C',
    rain: 'mm',
    clouds: '%',
    pressure: 'hPa'
};

const UNIT_OPTIONS = {
    wind: ['kt', 'km/h', 'm/s', 'mph'],
    temp: ['°C', '°F'],
    rain: ['mm', 'in'],
    clouds: ['%'],
    pressure: ['hPa', 'inHg']
};

const TILE_PROVIDERS = [
    {
        name: 'Esri Dark Gray',
        url: 'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
        options: { maxZoom: 16, attribution: 'Tiles &copy; Esri &mdash; Esri, DeLorme, NAVTEQ' }
    },
    {
        name: 'OSM Darkened',
        url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
        options: { maxZoom: 18, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>', className: 'dark-map-tiles' }
    },
    {
        name: 'Esri Imagery',
        url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        options: { maxZoom: 17, attribution: 'Tiles &copy; Esri' }
    }
];

// Color Scales for Layers
const LAYER_CONFIGS = {
    wind: {
        title: 'Wind Speed',
        baseUnit: 'km/h',
        baseTicks: [0, 9.26, 18.52, 37.04, 55.56, 74.08, 111.12], // 0, 5, 10, 20, 30, 40, 60 kt in km/h
        gradient: 'linear-gradient(to right, #475569, #0ea5e9, #10b981, #eab308, #f97316, #ef4444, #a855f7)',
        getValueColor(val) { // val in km/h
            const kt = val * 0.539957;
            if (kt < 5) return [71, 85, 105, 140];
            if (kt < 10) return [14, 165, 233, 150];
            if (kt < 20) return [16, 185, 129, 160];
            if (kt < 30) return [234, 179, 8, 175];
            if (kt < 40) return [249, 115, 22, 190];
            if (kt < 60) return [239, 68, 68, 205];
            return [168, 85, 247, 220];
        }
    },
    temp: {
        title: 'Temperature',
        baseUnit: '°C',
        baseTicks: [-10, 0, 10, 20, 30, 40],
        gradient: 'linear-gradient(to right, #3b82f6, #06b6d4, #10b981, #f59e0b, #ef4444, #7c3aed)',
        getValueColor(val) {
            if (val < 0) return [59, 130, 246, 150];
            if (val < 10) return [6, 182, 212, 155];
            if (val < 20) return [16, 185, 129, 160];
            if (val < 30) return [245, 158, 11, 170];
            if (val < 40) return [239, 68, 68, 185];
            return [124, 58, 237, 200];
        }
    },
    rain: {
        title: 'Precipitation',
        baseUnit: 'mm',
        baseTicks: [0, 0.5, 2.0, 5.0, 10.0, 25.0],
        gradient: 'linear-gradient(to right, rgba(0,0,0,0), #38bdf8, #0284c7, #2563eb, #9333ea, #d946ef)',
        getValueColor(val) {
            if (val <= 0.1) return [0, 0, 0, 0];
            if (val < 1.0) return [56, 189, 248, 140];
            if (val < 3.0) return [2, 132, 199, 160];
            if (val < 8.0) return [37, 99, 235, 175];
            if (val < 20.0) return [147, 51, 234, 190];
            return [217, 70, 239, 210];
        }
    },
    clouds: {
        title: 'Cloud Cover',
        baseUnit: '%',
        baseTicks: [0, 20, 40, 60, 80, 100],
        gradient: 'linear-gradient(to right, rgba(0,0,0,0), #94a3b8, #cbd5e1, #e2e8f0, #f8fafc, #ffffff)',
        getValueColor(val) {
            if (val < 10) return [0, 0, 0, 0];
            const alpha = Math.min(180, Math.floor((val / 100) * 160 + 20));
            return [203, 213, 225, alpha];
        }
    },
    pressure: {
        title: 'Sea Level Pressure',
        baseUnit: 'hPa',
        baseTicks: [990, 1000, 1010, 1015, 1020, 1030],
        gradient: 'linear-gradient(to right, #2563eb, #0284c7, #10b981, #f59e0b, #dc2626, #7c3aed)',
        getValueColor(val) {
            if (val < 1000) return [37, 99, 235, 160];
            if (val < 1008) return [2, 132, 199, 150];
            if (val < 1015) return [16, 185, 129, 140];
            if (val < 1022) return [245, 158, 11, 160];
            return [220, 38, 38, 180];
        }
    }
};

function convertValue(val, layer, targetUnit) {
    if (val === null || val === undefined) return null;
    if (layer === 'wind') {
        if (targetUnit === 'kt') return val * 0.539957;
        if (targetUnit === 'm/s') return val * 0.277778;
        if (targetUnit === 'mph') return val * 0.621371;
        return val; // km/h
    }
    if (layer === 'temp') {
        if (targetUnit === '°F') return val * 1.8 + 32;
        return val; // °C
    }
    if (layer === 'rain') {
        if (targetUnit === 'in') return val / 25.4;
        return val; // mm
    }
    if (layer === 'pressure') {
        if (targetUnit === 'inHg') return val * 0.02953;
        return val; // hPa
    }
    return val;
}

function formatValue(val, layer, unit) {
    if (val === null || val === undefined) return '--';
    const cVal = convertValue(val, layer, unit);
    if (layer === 'rain' || unit === 'inHg') return `${cVal.toFixed(1)} ${unit}`;
    return `${cVal.toFixed(0)} ${unit}`;
}

function cycleActiveLayerUnit() {
    const opts = UNIT_OPTIONS[windyActiveLayer] || ['kt'];
    const current = activeUnits[windyActiveLayer] || opts[0];
    const nextIdx = (opts.indexOf(current) + 1) % opts.length;
    activeUnits[windyActiveLayer] = opts[nextIdx];

    updateScaleBar();
    if (lastClickedPointData) {
        renderPointDrawerData(lastClickedPointData);
    }
}

function updateScaleBar() {
    const cfg = LAYER_CONFIGS[windyActiveLayer] || LAYER_CONFIGS.wind;
    const unit = activeUnits[windyActiveLayer] || cfg.baseUnit;

    const unitBtn = document.getElementById('scaleUnitBtn');
    if (unitBtn) unitBtn.textContent = unit;

    const strip = document.getElementById('legendColorStrip');
    if (strip) strip.style.background = cfg.gradient;

    const ticksEl = document.getElementById('legendTicks');
    if (ticksEl) {
        ticksEl.innerHTML = cfg.baseTicks.map(t => {
            const converted = convertValue(t, windyActiveLayer, unit);
            if (windyActiveLayer === 'rain' || unit === 'inHg') return `<span>${converted.toFixed(1)}</span>`;
            return `<span>${converted.toFixed(0)}</span>`;
        }).join('');
    }
}

function initWeatherMap() {
    const container = document.getElementById('leafletMap');
    if (!container) return;

    if (!leafletMap) {
        // Full World View by Default (Center [20.0, 20.0], Zoom 3, Wrap World)
        leafletMap = L.map('leafletMap', {
            zoomControl: true,
            attributionControl: true,
            minZoom: 2,
            maxZoom: 10,
            worldCopyJump: true
        }).setView([20.0, 20.0], 3);

        loadMapTileProvider(0);

        leafletMap.on('click', onMapClickPoint);
        leafletMap.on('mousemove', onMapHoverCursor);
        leafletMap.on('moveend zoomend', debouncedFetchWeatherGrid);

        window.addEventListener('resize', resetWindyCanvasSize);
    }

    updateScaleBar();
    onWindyTimelineScrub(windyHour);
    fetchWeatherGrid();
}

function loadMapTileProvider(idx) {
    if (!leafletMap) return;
    if (currentTileLayer) leafletMap.removeLayer(currentTileLayer);

    const provider = TILE_PROVIDERS[idx] || TILE_PROVIDERS[0];
    currentTileLayer = L.tileLayer(provider.url, provider.options);
    
    let failed = false;
    currentTileLayer.on('tileerror', function() {
        if (failed) return;
        failed = true;
        currentTileProviderIdx = (currentTileProviderIdx + 1) % TILE_PROVIDERS.length;
        showWeatherToast(`Tile issue, switched map to ${TILE_PROVIDERS[currentTileProviderIdx].name}`);
        loadMapTileProvider(currentTileProviderIdx);
    });

    currentTileLayer.addTo(leafletMap);
}

function debouncedFetchWeatherGrid() {
    if (gridFetchDebounceTimer) clearTimeout(gridFetchDebounceTimer);
    gridFetchDebounceTimer = setTimeout(fetchWeatherGrid, 400);
}

async function fetchWeatherGrid() {
    if (!leafletMap) return;
    const bounds = leafletMap.getBounds();
    const south = bounds.getSouth();
    const west = bounds.getWest();
    const north = bounds.getNorth();
    const east = bounds.getEast();

    const isMobile = window.innerWidth < 768;
    const rows = isMobile ? 12 : 18;
    const cols = isMobile ? 14 : 24;

    const spinner = document.getElementById('weatherMapSpinner');
    if (spinner) spinner.classList.remove('hidden');

    try {
        const url = `/api/wind/grid?south=${south.toFixed(4)}&west=${west.toFixed(4)}&north=${north.toFixed(4)}&east=${east.toFixed(4)}&rows=${rows}&cols=${cols}&hour=${windyHour}&layer=${windyActiveLayer}`;
        const data = await fetchApi(url);
        windyGridData = data;

        if (spinner) spinner.classList.add('hidden');
        hideWeatherToast();

        resetWindyCanvasSize();
        drawWindyHeatmapCanvas();
        initWindyCanvas();
    } catch (err) {
        if (spinner) spinner.classList.add('hidden');
        showWeatherToast("Weather grid telemetry fallback active");
        drawWindyHeatmapCanvas();
    }
}

// Bilinear Interpolation Engine
function interpolateGridValue(lat, lon, varName) {
    if (!windyGridData || !windyGridData.data || !windyGridData.data[varName]) return null;

    const g = windyGridData;
    const array = g.data[varName];
    const rows = g.rows;
    const cols = g.cols;

    if (lat > g.north || lat < g.south || lon < g.west || lon > g.east) return null;

    const rFloat = (g.north - lat) / (g.north - g.south) * (rows - 1);
    const cFloat = (lon - g.west) / (g.east - g.west) * (cols - 1);

    const r0 = Math.max(0, Math.min(rows - 1, Math.floor(rFloat)));
    const r1 = Math.min(rows - 1, r0 + 1);
    const c0 = Math.max(0, Math.min(cols - 1, Math.floor(cFloat)));
    const c1 = Math.min(cols - 1, c0 + 1);

    const dr = rFloat - r0;
    const dc = cFloat - c0;

    const v00 = array[r0 * cols + c0];
    const v01 = array[r0 * cols + c1];
    const v10 = array[r1 * cols + c0];
    const v11 = array[r1 * cols + c1];

    if (v00 === undefined) return null;

    return (1 - dr) * (1 - dc) * v00 + (1 - dr) * dc * v01 + dr * (1 - dc) * v10 + dr * dc * v11;
}

// Draw Interpolated Heatmap Canvas
function drawWindyHeatmapCanvas() {
    const canvas = document.getElementById('windyHeatmapCanvas');
    if (!canvas || !leafletMap) return;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    if (!windyGridData) return;

    const cfg = LAYER_CONFIGS[windyActiveLayer] || LAYER_CONFIGS.wind;
    const varName = (windyActiveLayer === 'wind') ? 'wind_speed' : windyActiveLayer;

    const step = 6;
    const imgData = ctx.createImageData(canvas.width, canvas.height);
    const pixels = imgData.data;

    for (let py = 0; py < canvas.height; py += step) {
        for (let px = 0; px < canvas.width; px += step) {
            const latlng = leafletMap.containerPointToLatLng([px, py]);
            const val = interpolateGridValue(latlng.lat, latlng.lng, varName);

            if (val !== null && val !== undefined) {
                const rgba = cfg.getValueColor(val);
                for (let dy = 0; dy < step && (py + dy) < canvas.height; dy++) {
                    for (let dx = 0; dx < step && (px + dx) < canvas.width; dx++) {
                        const index = ((py + dy) * canvas.width + (px + dx)) * 4;
                        pixels[index] = rgba[0];
                        pixels[index + 1] = rgba[1];
                        pixels[index + 2] = rgba[2];
                        pixels[index + 3] = rgba[3];
                    }
                }
            }
        }
    }

    ctx.putImageData(imgData, 0, 0);
}

// Animated Streamline Wind Vector Particle Engine (White Streamlines)
function initWindyCanvas() {
    const canvas = document.getElementById('windyCanvas');
    if (!canvas) return;
    resetWindyCanvasSize();

    if (!windyParticlesVisible) {
        const ctx = canvas.getContext('2d');
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        return;
    }

    const count = window.innerWidth < 768 ? 2200 : 4500;
    windyParticles = [];
    for (let i = 0; i < count; i++) {
        windyParticles.push(createRandomStreamlineParticle(canvas.width, canvas.height));
    }

    if (windyAnimFrameId) cancelAnimationFrame(windyAnimFrameId);
    animateWindyParticles();
}

function resetWindyCanvasSize() {
    const canvas = document.getElementById('windyCanvas');
    const hCanvas = document.getElementById('windyHeatmapCanvas');
    const wrapper = document.getElementById('windyMapFrame');
    if (!wrapper) return;
    const w = wrapper.clientWidth;
    const h = wrapper.clientHeight;
    if (canvas) { canvas.width = w; canvas.height = h; }
    if (hCanvas) { hCanvas.width = w; hCanvas.height = h; }
}

function createRandomStreamlineParticle(w, h) {
    return {
        x: Math.random() * w,
        y: Math.random() * h,
        age: 0,
        maxAge: Math.floor(Math.random() * 60 + 40)
    };
}

function animateWindyParticles() {
    const canvas = document.getElementById('windyCanvas');
    if (!canvas || !leafletMap) return;

    if (document.hidden || !windyParticlesVisible) {
        windyAnimFrameId = requestAnimationFrame(animateWindyParticles);
        return;
    }

    const ctx = canvas.getContext('2d');

    // Fading Dark Trail Background Fill
    ctx.fillStyle = 'rgba(10, 15, 25, 0.18)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    ctx.lineWidth = 1.2;
    ctx.lineCap = 'round';

    const zoom = leafletMap.getZoom();
    const speedScale = 0.3 * Math.pow(1.15, zoom - 3);

    windyParticles.forEach((p, index) => {
        const latlng = leafletMap.containerPointToLatLng([p.x, p.y]);
        const u = interpolateGridValue(latlng.lat, latlng.lng, 'u');
        const v = interpolateGridValue(latlng.lat, latlng.lng, 'v');

        if (u === null || v === null) {
            windyParticles[index] = createRandomStreamlineParticle(canvas.width, canvas.height);
            return;
        }

        const nextX = p.x + u * speedScale;
        const nextY = p.y - v * speedScale;

        const alpha = (1.0 - (p.age / p.maxAge)) * 0.85;

        // Thin WHITE Streamlines (Windy Style)
        ctx.strokeStyle = `rgba(255, 255, 255, ${alpha.toFixed(2)})`;
        ctx.beginPath();
        ctx.moveTo(p.x, p.y);
        ctx.lineTo(nextX, nextY);
        ctx.stroke();

        p.x = nextX;
        p.y = nextY;
        p.age++;

        if (p.age >= p.maxAge || p.x > canvas.width || p.y > canvas.height || p.x < 0 || p.y < 0) {
            windyParticles[index] = createRandomStreamlineParticle(canvas.width, canvas.height);
        }
    });

    windyAnimFrameId = requestAnimationFrame(animateWindyParticles);
}

function toggleParticlesVisible(checked) {
    windyParticlesVisible = checked;
    initWindyCanvas();
}

function toggleLayerMenuDrawer() {
    const drawer = document.getElementById('windyLayerSwitcher');
    if (drawer) drawer.classList.toggle('open');
}

function setWindyLayer(layerType) {
    windyActiveLayer = layerType;

    document.querySelectorAll('#windyLayerSwitcher .layer-btn').forEach(btn => {
        if (btn.getAttribute('data-layer') === layerType) btn.classList.add('active');
        else btn.classList.remove('active');
    });

    updateScaleBar();
    fetchWeatherGrid();
}

function onWindyTimelineScrub(val) {
    windyHour = parseInt(val);
    const scrubber = document.getElementById('windyTimelineScrubber');
    if (scrubber) scrubber.value = windyHour;

    const baseDate = new Date();
    baseDate.setHours(baseDate.getHours() + windyHour);

    const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const dayStr = `${dayNames[baseDate.getDay()]} ${baseDate.getDate()}`;
    const timeStr = `${String(baseDate.getHours()).padStart(2, '0')}:00`;

    const nextDate = new Date(baseDate.getTime() + 24 * 3600 * 1000);
    const nextDayStr = `${dayNames[nextDate.getDay()]} ${nextDate.getDate()}`;

    const tabDay = document.getElementById('windyTabDay');
    if (tabDay) tabDay.textContent = dayStr;

    const tabTime = document.getElementById('windyTabTime');
    if (tabTime) tabTime.textContent = timeStr;

    const tabNext = document.getElementById('windyTabNextDay');
    if (tabNext) tabNext.textContent = nextDayStr;

    fetchWeatherGrid();
}

function toggleWindyTimelinePlay() {
    const playBtn = document.getElementById('windyPlayBtn');
    if (windyTimelineTimer) {
        clearInterval(windyTimelineTimer);
        windyTimelineTimer = null;
        if (playBtn) playBtn.innerHTML = `<i class="fa-solid fa-play"></i>`;
    } else {
        if (playBtn) playBtn.innerHTML = `<i class="fa-solid fa-pause"></i>`;
        windyTimelineTimer = setInterval(() => {
            windyHour = (windyHour + 1) % 72;
            onWindyTimelineScrub(windyHour);
        }, 1500);
    }
}

function getWeatherEmoji(code, cloud, rain) {
    if (rain > 5.0 || code === 63 || code === 65 || code === 95) return '⛈️';
    if (rain > 0.1 || code === 61 || code === 80) return '🌧️';
    if (cloud > 70 || code === 3) return '☁️';
    if (cloud > 30 || code === 1 || code === 2) return '⛅';
    return '☀️';
}

async function onMapClickPoint(e) {
    const lat = e.latlng.lat;
    const lon = e.latlng.lng;

    if (clickPinMarker && leafletMap) leafletMap.removeLayer(clickPinMarker);

    const pinIcon = L.divIcon({
        html: `<div style="color:#ef4444; font-size:24px; filter:drop-shadow(0 4px 8px rgba(0,0,0,0.8));"><i class="fa-solid fa-location-dot"></i></div>`,
        className: 'click-pin-icon',
        iconSize: [24, 24],
        iconAnchor: [12, 24]
    });

    clickPinMarker = L.marker([lat, lon], { icon: pinIcon }).addTo(leafletMap);

    const drawer = document.getElementById('pointWeatherDrawer');
    if (drawer) drawer.classList.remove('hidden');

    document.getElementById('ptLocationName').textContent = 'Loading weather...';
    document.getElementById('ptCoords').textContent = `${lat.toFixed(4)}° N, ${lon.toFixed(4)}° E`;

    try {
        const res = await fetchApi(`/api/weather/point?lat=${lat.toFixed(4)}&lon=${lon.toFixed(4)}`);
        lastClickedPointData = { lat, lon, res };
        renderPointDrawerData(lastClickedPointData);
    } catch (err) {
        document.getElementById('ptLocationName').textContent = `${lat.toFixed(4)}° N, ${lon.toFixed(4)}° E`;
    }
}

function renderPointDrawerData(pointObj) {
    const { lat, lon, res } = pointObj;
    const locName = (res.location && res.location.name) ? res.location.name : `${lat.toFixed(4)}° N, ${lon.toFixed(4)}° E`;
    document.getElementById('ptLocationName').textContent = locName;

    if (res.current) {
        const c = res.current;
        const tempUnit = activeUnits.temp || '°C';
        const windUnit = activeUnits.wind || 'kt';
        const rainUnit = activeUnits.rain || 'mm';
        const pressUnit = activeUnits.pressure || 'hPa';

        const emoji = getWeatherEmoji(c.weather_code || 0, c.cloud_cover || 20, c.precipitation || 0);
        const emojiEl = document.getElementById('ptEmoji');
        if (emojiEl) emojiEl.textContent = emoji;

        document.getElementById('ptTemp').textContent = formatValue(c.temperature_2m, 'temp', tempUnit);

        const windSpd = c.wind_speed_10m || 15.0;
        const windDir = c.wind_direction_10m || 135;
        document.getElementById('ptWindVal').textContent = formatValue(windSpd, 'wind', windUnit);

        const arrow = document.getElementById('ptWindArrow');
        if (arrow) arrow.style.transform = `rotate(${windDir}deg)`;

        document.getElementById('ptGusts').textContent = `Gusts: ${formatValue(c.wind_gusts_10m || windSpd * 1.3, 'wind', windUnit)}`;
        document.getElementById('ptRainSum').textContent = formatValue(c.precipitation || 0.0, 'rain', rainUnit);
        document.getElementById('ptClouds').textContent = `${c.cloud_cover || 25}%`;
        document.getElementById('ptHumidity').textContent = `${c.relative_humidity_2m || 68}%`;
        document.getElementById('ptPressure').textContent = formatValue(c.pressure_msl || 1012.4, 'pressure', pressUnit);
    }

    if (res.hourly && res.hourly.temperature_2m) {
        const hScroll = document.getElementById('ptHourlyScroll');
        const tempUnit = activeUnits.temp || '°C';
        let html = '';
        for (let i = 0; i < Math.min(24, res.hourly.temperature_2m.length); i++) {
            const timeStr = res.hourly.time ? (res.hourly.time[i].split('T')[1] || `${i}:00`) : `${i}:00`;
            const rawTemp = res.hourly.temperature_2m[i];
            const displayTemp = formatValue(rawTemp, 'temp', tempUnit);
            html += `
                <div class="hourly-item">
                    <span class="text-white-50">${timeStr}</span><br>
                    <span style="font-size:16px;">⛅</span><br>
                    <strong class="text-white">${displayTemp}</strong>
                </div>
            `;
        }
        if (hScroll) hScroll.innerHTML = html;
    }

    if (res.daily && res.daily.temperature_2m_max) {
        const dList = document.getElementById('ptDailyList');
        const tempUnit = activeUnits.temp || '°C';
        let html = '';
        for (let i = 0; i < Math.min(5, res.daily.temperature_2m_max.length); i++) {
            const day = res.daily.time ? res.daily.time[i] : `Day ${i+1}`;
            const maxT = formatValue(res.daily.temperature_2m_max[i], 'temp', tempUnit);
            const minT = formatValue(res.daily.temperature_2m_min ? res.daily.temperature_2m_min[i] : res.daily.temperature_2m_max[i] - 8, 'temp', tempUnit);
            html += `
                <div class="daily-item text-white">
                    <span><strong>${day}</strong></span>
                    <span class="text-cyan"><i class="fa-solid fa-cloud-showers-heavy"></i> ${res.daily.precipitation_probability_max ? res.daily.precipitation_probability_max[i] : 20}%</span>
                    <span><strong>${maxT}</strong> / <small class="text-white-50">${minT}</small></span>
                </div>
            `;
        }
        if (dList) dList.innerHTML = html;
    }
}

function closePointWeatherDrawer() {
    const drawer = document.getElementById('pointWeatherDrawer');
    if (drawer) drawer.classList.add('hidden');
    if (clickPinMarker && leafletMap) leafletMap.removeLayer(clickPinMarker);
    lastClickedPointData = null;
}

function onMapHoverCursor(e) {
    const tooltip = document.getElementById('weatherHoverTooltip');
    if (!tooltip || !windyGridData) return;

    const latlng = e.latlng;
    const varName = (windyActiveLayer === 'wind') ? 'wind_speed' : windyActiveLayer;
    const val = interpolateGridValue(latlng.lat, latlng.lng, varName);

    if (val === null || val === undefined) {
        tooltip.classList.add('hidden');
        return;
    }

    const unit = activeUnits[windyActiveLayer] || LAYER_CONFIGS[windyActiveLayer].baseUnit;
    const formatted = formatValue(val, windyActiveLayer, unit);
    const cfg = LAYER_CONFIGS[windyActiveLayer] || LAYER_CONFIGS.wind;
    tooltip.textContent = `${cfg.title}: ${formatted}`;

    const container = document.getElementById('windyMapFrame');
    if (container) {
        const rect = container.getBoundingClientRect();
        const mouseX = e.originalEvent.clientX - rect.left;
        const mouseY = e.originalEvent.clientY - rect.top;

        tooltip.style.left = `${mouseX}px`;
        tooltip.style.top = `${mouseY}px`;
        tooltip.classList.remove('hidden');
    }
}

function onWeatherSearchInput(val) {
    if (searchDebounceTimer) clearTimeout(searchDebounceTimer);
    const dropdown = document.getElementById('searchAutocompleteResults');

    if (!val || val.trim().length < 2) {
        if (dropdown) dropdown.classList.add('hidden');
        return;
    }

    searchDebounceTimer = setTimeout(async () => {
        try {
            const res = await fetchApi(`/api/geocode?q=${encodeURIComponent(val.trim())}`);
            const items = res.results || res || [];

            if (items.length === 0) {
                dropdown.innerHTML = `<div class="search-item"><span class="text-secondary">No locations found</span></div>`;
                dropdown.classList.remove('hidden');
                return;
            }

            dropdown.innerHTML = items.map(item => `
                <div class="search-item" onclick="selectSearchResult(${item.latitude}, ${item.longitude}, '${escapeHtml(item.name)}')">
                    <strong>${escapeHtml(item.name)}</strong>
                    <small class="text-secondary">${escapeHtml(item.admin1 || item.country || '')}</small>
                </div>
            `).join('');
            dropdown.classList.remove('hidden');
        } catch (e) {
            if (dropdown) dropdown.classList.add('hidden');
        }
    }, 300);
}

function selectSearchResult(lat, lon, name) {
    const dropdown = document.getElementById('searchAutocompleteResults');
    if (dropdown) dropdown.classList.add('hidden');

    const input = document.getElementById('weatherSearchInput');
    if (input) input.value = name;

    if (leafletMap) {
        leafletMap.flyTo([lat, lon], 7);
        onMapClickPoint({ latlng: { lat, lng: lon } });
    }
}

function locateUserGeolocation() {
    if (!navigator.geolocation) {
        alert('Geolocation is not supported by your browser.');
        return;
    }

    navigator.geolocation.getCurrentPosition(
        pos => {
            const lat = pos.coords.latitude;
            const lon = pos.coords.longitude;
            if (leafletMap) {
                leafletMap.flyTo([lat, lon], 8);
                onMapClickPoint({ latlng: { lat, lng: lon } });
            }
        },
        err => {
            alert('Unable to retrieve location.');
        }
    );
}

function toggleWindyBasemap() {
    if (!leafletMap) return;
    windyBasemapTheme = (windyBasemapTheme === 'dark') ? 'light' : 'dark';

    Object.values(windyTileLayers).forEach(l => leafletMap.removeLayer(l));

    if (windyBasemapTheme === 'light') {
        windyTileLayers.light.addTo(leafletMap);
        document.getElementById('basemapThemeText').textContent = 'Light Tiles';
    } else {
        windyTileLayers.dark.addTo(leafletMap);
        document.getElementById('basemapThemeText').textContent = 'Dark Tiles';
    }
}

function toggleMapAlertsOverlay() {
    mapAlertsOverlayVisible = !mapAlertsOverlayVisible;
    const txt = document.getElementById('alertsToggleText');
    if (txt) txt.textContent = mapAlertsOverlayVisible ? 'Alerts: ON' : 'Alerts: OFF';

    alertsMarkersList.forEach(m => leafletMap.removeLayer(m));
    alertsMarkersList = [];

    if (mapAlertsOverlayVisible && leafletMap) {
        const alertLocs = [
            { name: "Coimbatore Corridor", lat: 11.0168, lon: 76.9558, msg: "TEMPERATURE ALERT: High heat stress > 36°C" },
            { name: "Madurai District", lat: 9.9252, lon: 78.1198, msg: "HEAVY RAIN WARNING: Convective showers in 48h" },
            { name: "Cuddalore Coastal Belt", lat: 11.7480, lon: 79.7714, msg: "WIND SQUALL ALERT: Coastal gusts up to 45 km/h" }
        ];

        alertLocs.forEach(a => {
            const alertIcon = L.divIcon({
                html: `<div style="background:#ef4444; color:#fff; width:28px; height:28px; border-radius:50%; display:flex; align-items:center; justify-content:center; box-shadow:0 0 15px rgba(239,68,68,0.9); font-size:14px;"><i class="fa-solid fa-triangle-exclamation"></i></div>`,
                className: 'alert-marker-icon',
                iconSize: [28, 28],
                iconAnchor: [14, 14]
            });

            const m = L.marker([a.lat, a.lon], { icon: alertIcon }).addTo(leafletMap);
            m.bindPopup(`
                <div style="color:#0f172a; font-family:sans-serif; padding:4px;">
                    <strong style="color:#dc2626;"><i class="fa-solid fa-triangle-exclamation"></i> ${a.name}</strong><br>
                    <small>${a.msg}</small>
                </div>
            `);
            alertsMarkersList.push(m);
        });
    }
}

function openFarmAdviceModal() {
    const modal = document.getElementById('farmAdviceModal');
    const body = document.getElementById('farmAdviceModalBody');
    if (!modal || !body) return;

    const farmDist = currentFarmer ? currentFarmer.district : 'Thanjavur';
    const farmSoil = currentFarmer ? currentFarmer.soil : 'ALLUVIAL';
    const farmWater = currentFarmer ? currentFarmer.water : 'MEDIUM';

    body.innerHTML = `
        <div style="font-size:14px; color:var(--text-primary); line-height:1.6;">
            <div style="background:rgba(245,158,11,0.12); border:1px solid #f59e0b; border-radius:10px; padding:12px; margin-bottom:16px;">
                <strong>📍 Farm Location:</strong> ${escapeHtml(farmDist)}, Tamil Nadu<br>
                <strong>🌱 Saved Soil Type:</strong> ${escapeHtml(farmSoil)} | <strong>💧 Water Level:</strong> ${escapeHtml(farmWater)}
            </div>

            <h4 style="color:#10b981; margin-bottom:8px;"><i class="fa-solid fa-shield-halved"></i> Weather-Aware Farming Protocol:</h4>
            <ul style="padding-left:20px; display:flex; flex-direction:column; gap:8px;">
                <li><strong>Rainfall Management:</strong> If rain probability exceeds 50%, pause scheduled NPK (Urea/DAP) top-dressing to prevent nutrient runoff.</li>
                <li><strong>Wind & Spraying Rules:</strong> Do not apply liquid foliar sprays when wind speeds exceed 20 km/h to prevent chemical drift.</li>
                <li><strong>Heat Stress Mitigation:</strong> Maintain 2-3 cm shallow water standing in Paddy fields when maximum daytime temp exceeds 35°C.</li>
                <li><strong>Irrigation Scheduling:</strong> Ensure canal sluice gate allocation is requested 24 hours prior to forecasted dry spells.</li>
            </ul>
        </div>
    `;

    modal.classList.add('active');
}

function closeFarmAdviceModal() {
    const modal = document.getElementById('farmAdviceModal');
    if (modal) modal.classList.remove('active');
}

function showWeatherToast(msg) {
    const toast = document.getElementById('weatherToast');
    const txt = document.getElementById('weatherToastText');
    if (toast && txt) {
        txt.textContent = msg;
        toast.classList.remove('hidden');
    }
}

function hideWeatherToast() {
    const toast = document.getElementById('weatherToast');
    if (toast) toast.classList.add('hidden');
}

// =========================================================================
// RISK ALERTS BOARD
// =========================================================================
async function loadLiveAlerts() {
    try {
        const dist = currentFarmer ? currentFarmer.district : '';
        const alerts = await fetchApi(`/alerts?district=${encodeURIComponent(dist)}`);

        // Update Ticker
        const ticker = document.getElementById('weatherTickerText');
        if (ticker && alerts.length > 0) {
            ticker.textContent = alerts.join(' | ');
        }

        // Update Alert Board List
        const board = document.getElementById('alertBoardList');
        if (board) {
            board.innerHTML = alerts.map(a => `
                <div class="card inner-card mb-2" style="border-left: 4px solid var(--theme-warning);">
                    <div class="flex-between">
                        <div><i class="fa-solid fa-triangle-exclamation text-warning"></i> <strong>${escapeHtml(a)}</strong></div>
                        <small class="text-secondary">Live Telemetry</small>
                    </div>
                </div>
            `).join('');
        }
    } catch (e) {}
}

// =========================================================================
// WATER ALLOCATION CONCURRENCY SIMULATION
// =========================================================================
async function triggerWaterSimulation() {
    const consoleEl = document.getElementById('waterLogsConsole');
    if (!consoleEl) return;

    consoleEl.textContent = 'Launching 5-thread Java canal sluice gate simulation...\nWaiting for CountDownLatch synchronization...';

    try {
        const res = await fetchApi('/water/simulate', { method: 'POST' });
        consoleEl.textContent = res.logs || 'Simulation finished.';
    } catch (err) {
        consoleEl.textContent = `Simulation Error: ${err.message}`;
    }
}

// =========================================================================
// ADMIN AREA (CROP CATALOG & FARMER REGISTRY)
// =========================================================================
async function loadAdminCrops() {
    const tbody = document.getElementById('adminCropsTableBody');
    if (!tbody) return;

    try {
        cropsCache = await fetchApi('/crops');
        tbody.innerHTML = cropsCache.map(c => `
            <tr>
                <td><strong>${escapeHtml(c.name)}</strong></td>
                <td>${escapeHtml(c.type)}</td>
                <td><small>${escapeHtml(c.soils ? c.soils.join(', ') : '')}</small></td>
                <td>${escapeHtml(c.season)}</td>
                <td>${escapeHtml(c.water)}</td>
                <td>${c.yield} Qt</td>
                <td>₹${c.marketPrice.toLocaleString('en-IN')}</td>
                <td>₹${c.laborCost ? c.laborCost.toLocaleString('en-IN') : '6,000'}</td>
            </tr>
        `).join('');
    } catch (e) {}
}

function openCropModal() {
    document.getElementById('cropModal').classList.add('active');
}

function closeCropModal() {
    document.getElementById('cropModal').classList.remove('active');
}

async function handleSaveCropAdmin(e) {
    e.preventDefault();
    const name = document.getElementById('cropNameInput').value.trim();
    const type = document.getElementById('cropTypeInput').value;
    const season = document.getElementById('cropSeasonInput').value;
    const water = document.getElementById('cropWaterInput').value;
    const soils = document.getElementById('cropSoilsInput').value.trim();
    const yieldVal = document.getElementById('cropYieldInput').value;
    const price = document.getElementById('cropPriceInput').value;
    const seed = document.getElementById('cropSeedCostInput').value;
    const labor = document.getElementById('cropLaborCostInput').value;
    const urea = document.getElementById('cropUreaInput').value;
    const dap = document.getElementById('cropDapInput').value;
    const mop = document.getElementById('cropMopInput').value;

    try {
        await fetchApi('/crops', {
            method: 'POST',
            body: JSON.stringify({
                name, type, season, water, soils,
                yield: yieldVal, marketPrice: price, seedCost: seed, laborCost: labor,
                urea, dap, mop
            })
        });

        alert(i18n.t('messages.cropSaved'));
        closeCropModal();
        loadAdminCrops();
    } catch (err) {
        alert(err.message || 'Failed to save crop.');
    }
}

async function loadAdminFarmers() {
    const tbody = document.getElementById('adminFarmersTableBody');
    if (!tbody) return;

    try {
        farmersCache = await fetchApi('/farmers');
        tbody.innerHTML = farmersCache.map(f => `
            <tr>
                <td><strong>${escapeHtml(f.id)}</strong></td>
                <td>${escapeHtml(f.name)}</td>
                <td>${escapeHtml(f.district || 'Thanjavur')}</td>
                <td>${f.acres} Ac</td>
                <td>${escapeHtml(f.soil)}</td>
                <td>${escapeHtml(f.water)}</td>
                <td><span class="badge ${f.acres <= 5 ? 'badge-success' : 'badge-info'}">${escapeHtml(f.type)}</span></td>
                <td class="text-warning">${Math.round(f.subsidyRate * 100)}%</td>
            </tr>
        `).join('');
    } catch (e) {}
}

// Utility Escaper
function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
