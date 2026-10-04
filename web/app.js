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
    if (mapSelect) mapSelect.innerHTML = html;
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
    if (sectionId === 'weather') initWeatherMap();
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

    // Load Live Weather for Farmer's District
    try {
        const weather = await fetchApi(`/weather?district=${encodeURIComponent(currentFarmer.district)}`);
        if (weather && weather.current) {
            document.getElementById('dashTemp').textContent = `${weather.current.temperature_2m} °C`;
            document.getElementById('dashHumidity').textContent = `${weather.current.relative_humidity_2m} %`;
            document.getElementById('dashWind').textContent = `${weather.current.wind_speed_10m} km/h`;
            if (weather.daily && weather.daily.precipitation_probability_max) {
                document.getElementById('dashRain').textContent = `${weather.daily.precipitation_probability_max[0]} %`;
            }
        }
    } catch (e) {}
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
// WEATHER MAP ENGINE (LEAFLET.JS)
// =========================================================================
function initWeatherMap() {
    const container = document.getElementById('leafletMap');
    if (!container) return;

    if (!leafletMap) {
        // Center map on Tamil Nadu (10.7870, 78.6569, zoom level 7)
        leafletMap = L.map('leafletMap').setView([10.7870, 78.6569], 7);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 18,
            attribution: '© OpenStreetMap contributors'
        }).addTo(leafletMap);

        // Add Circle Markers for 15 Districts
        Object.values(tnDistricts).forEach(dist => {
            const isHome = currentFarmer && currentFarmer.district === dist.name;
            const circle = L.circleMarker([dist.lat, dist.lon], {
                radius: isHome ? 12 : 8,
                color: isHome ? '#f59e0b' : '#10b981',
                fillColor: isHome ? '#f59e0b' : '#10b981',
                fillOpacity: 0.8,
                weight: isHome ? 3 : 1
            }).addTo(leafletMap);

            circle.bindPopup(`<b>${dist.name} District</b><br>Soil: ${dist.defaultSoil}`);
            circle.on('click', () => onMapDistrictSelect(dist.name));

            leafletMarkers[dist.name] = circle;
        });
    }

    const homeDist = currentFarmer ? currentFarmer.district : 'Thanjavur';
    const banner = document.getElementById('mapHighlightBanner');
    if (banner) {
        banner.innerHTML = `<i class="fa-solid fa-star text-warning"></i> <span>Your home district (<strong>${homeDist}</strong>) is highlighted on the map!</span>`;
    }

    onMapDistrictSelect(homeDist);
}

async function onMapDistrictSelect(districtName) {
    currentMapDistrict = districtName;
    const selectEl = document.getElementById('mapDistrictSelector');
    if (selectEl) selectEl.value = districtName;

    const distInfo = tnDistricts[districtName];
    if (distInfo && leafletMap) {
        leafletMap.panTo([distInfo.lat, distInfo.lon]);
        if (leafletMarkers[districtName]) {
            leafletMarkers[districtName].openPopup();
        }
    }

    // Fetch Live Open-Meteo Weather for selected district
    document.getElementById('panelDistrictTitle').textContent = `${districtName} District`;
    try {
        const weather = await fetchApi(`/weather?district=${encodeURIComponent(districtName)}`);
        if (weather && weather.current) {
            document.getElementById('panelTemp').textContent = `${weather.current.temperature_2m} °C`;
            document.getElementById('panelHumidity').textContent = `${weather.current.relative_humidity_2m} %`;
            document.getElementById('panelWind').textContent = `${weather.current.wind_speed_10m} km/h`;
            if (weather.daily && weather.daily.precipitation_probability_max) {
                document.getElementById('panelRain').textContent = `${weather.daily.precipitation_probability_max[0]} %`;
            }

            // Render 5-Day Forecast
            const forecastList = document.getElementById('forecastList');
            if (forecastList && weather.daily && weather.daily.time) {
                forecastList.innerHTML = weather.daily.time.slice(0, 5).map((dateStr, i) => `
                    <div class="forecast-item">
                        <span>${dateStr}</span>
                        <span>Max ${weather.daily.temperature_2m_max[i]}°C / Min ${weather.daily.temperature_2m_min[i]}°C</span>
                        <strong class="text-info">Rain ${weather.daily.precipitation_probability_max[i]}%</strong>
                    </div>
                `).join('');
            }
        }
    } catch (e) {}
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
