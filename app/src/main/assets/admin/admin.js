// TV Kiosk web admin. Talks only to this TV's own API, with the token the user enters.
// Everything is rendered with textContent: nothing from the TV is ever parsed as HTML.
"use strict";

const API = "/api/v1";
const STORE = "tvkiosk-token";

const GROUPS = [
  ["What the TV shows", ["mode", "start_url", "ha_url", "auth_mode", "ha_long_lived_token", "ha_kiosk_mode", "pages", "rotate_seconds", "scenes"]],
  ["Screen and remote", ["remote_mode", "keep_screen_on", "boot_start", "burn_in_guard", "screen_off_from", "screen_off_to"]],
  ["Wake on motion", ["rest_after_minutes", "rest_style", "motion_front", "motion_at_night"]],
  ["Pop-ups", ["popup_size", "popup_opacity", "popup_text_size", "popup_corner", "popup_duration", "popup_max_per_corner", "popup_take_remote"]],
  ["Recovery", ["reload_on_network", "reload_on_disconnect_seconds", "nightly_reload_hour"]],
  ["API and TvOverlay", ["api_enabled", "api_port", "tvoverlay_compat", "tvoverlay_port"]],
  ["MQTT", ["mqtt_enabled", "mqtt_host", "mqtt_port", "mqtt_tls", "mqtt_user", "mqtt_password", "mqtt_base_topic", "mqtt_discovery_prefix"]],
  ["This TV", ["device_name", "settings_pin", "api_token"]],
];

const LABELS = {
  mode: "Shows", start_url: "Start page", ha_url: "Home Assistant address", auth_mode: "Sign-in",
  ha_long_lived_token: "Long-lived token", ha_kiosk_mode: "Hide HA header and sidebar", pages: "More pages (one per line)", scenes: "Scenes by time of day (one per line: 07:00 /page brightness=80 rest=clock rest_after=10)",
  rotate_seconds: "Seconds on each page (0 = off)", remote_mode: "Remote", keep_screen_on: "Keep the screen on",
  boot_start: "Start when the TV starts", burn_in_guard: "Burn-in guard", screen_off_from: "Night: black from hour (-1 = never)",
  screen_off_to: "Night: until hour", rest_after_minutes: "Rest after minutes without motion (0 = never)", rest_style: "While resting",
  motion_front: "Motion brings the dashboard back", motion_at_night: "Motion wakes it at night",
  popup_size: "Size", popup_opacity: "Opacity %", popup_text_size: "Text", popup_corner: "Corner",
  popup_duration: "Seconds on screen", popup_max_per_corner: "Most per corner", popup_take_remote: "Pop-ups with buttons take the remote",
  reload_on_network: "Reload when the network comes back", reload_on_disconnect_seconds: "Reload after HA is unreachable for (s)",
  nightly_reload_hour: "Reload every night at hour (-1 = never)", api_enabled: "REST API", api_port: "API port",
  tvoverlay_compat: "TvOverlay-compatible pop-ups (no token)", tvoverlay_port: "TvOverlay port", mqtt_enabled: "MQTT",
  mqtt_host: "Broker", mqtt_port: "Port", mqtt_tls: "TLS", mqtt_user: "User", mqtt_password: "Password",
  mqtt_base_topic: "Base topic", mqtt_discovery_prefix: "Discovery prefix", device_name: "Name",
  settings_pin: "Settings PIN (4-8 digits, empty removes it)", api_token: "API token (changing it disconnects this page)",
};

/** Commands whose REST route differs from their name. */
const ROUTES = { stop_sound: "sound/stop", notify_clear: "notify/clear" };

let token = "";
let schema = [];
let current = {};
const changes = {};

const $ = (id) => document.getElementById(id);
const el = (tag, props = {}, ...children) => {
  const node = Object.assign(document.createElement(tag), props);
  for (const child of children) if (child != null) node.append(child);
  return node;
};

function toast(text) {
  const t = $("toast");
  t.textContent = text;
  t.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove("show"), 2600);
}

async function api(path, body, method) {
  const options = { method: method || (body === undefined ? "GET" : "POST"), headers: { Authorization: "Bearer " + token } };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(API + path, options);
  const type = response.headers.get("Content-Type") || "";
  const data = type.startsWith("application/json") ? await response.json() : await response.text();
  if (response.status === 401) { forget(); throw new Error("The token was refused."); }
  if (!response.ok) throw new Error((data && data.error) || "The TV answered " + response.status);
  return data;
}

async function command(name, args) {
  try {
    const result = await api("/" + (ROUTES[name] || name), args || {});
    toast(result.warning ? "Done, but: " + result.warning : "Done");
    setTimeout(refreshState, 400);
    return result;
  } catch (e) {
    toast(e.message);
  }
}

// ---- phone remote ----------------------------------------------------------------------

// No toast or state refresh per key: a remote has to feel immediate.
async function key(name) {
  try { await api("/remote", { key: name }); } catch (e) { toast(e.message); }
}

const KEYBOARD = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right", Enter: "ok", Escape: "back", Backspace: "back" };

function keyboardRemote(e) {
  if ($("control").hidden || e.target.closest("input, textarea, select")) return;
  const name = KEYBOARD[e.key];
  if (!name) return;
  e.preventDefault();
  key(name);
}

// ---- connect ---------------------------------------------------------------------------

function forget() {
  token = "";
  try { localStorage.removeItem(STORE); sessionStorage.removeItem(STORE); } catch (e) { /* storage blocked */ }
  show("connect");
  $("tabs").hidden = true;
  $("disconnect").hidden = true;
}

async function connect(candidate, remember) {
  token = candidate.trim();
  try {
    await api("/state");
  } catch (e) {
    $("connect-error").textContent = e.message;
    return false;
  }
  try {
    (remember ? localStorage : sessionStorage).setItem(STORE, token);
  } catch (e) { /* storage blocked: stay connected for this page only */ }
  $("connect-error").textContent = "";
  $("tabs").hidden = false;
  $("disconnect").hidden = false;
  show("overview");
  refreshState();
  loadSettings();
  return true;
}

// ---- tabs ------------------------------------------------------------------------------

function show(name) {
  for (const section of document.querySelectorAll("main > section")) section.hidden = section.id !== name;
  for (const tab of document.querySelectorAll("#tabs button")) tab.classList.toggle("active", tab.dataset.tab === name);
  if (name === "log") loadLog();
}

// ---- overview --------------------------------------------------------------------------

function card(key, value, tone) {
  return el("div", { className: "card" }, el("div", { className: "k", textContent: key }), el("div", { className: "v " + (tone || ""), textContent: value }));
}

function since(seconds) {
  if (seconds == null) return "never";
  const s = Math.max(0, Math.round(Date.now() / 1000 - seconds));
  if (s < 90) return s + " s ago";
  if (s < 5400) return Math.round(s / 60) + " min ago";
  return Math.round(s / 3600) + " h ago";
}

async function refreshState() {
  if (!token) return;
  let s;
  try { s = await api("/state"); } catch (e) { return; }
  $("device").textContent = s.model ? "· " + (current.device_name || s.model) : "";
  const screen = s.night ? "black (night)" : s.resting ? "resting" : s.screen;
  const cards = [
    card("Page", s.title || s.url || "-"),
    card("Home Assistant", s.ha_connection, s.ha_connection === "connected" ? "ok" : s.ha_connection === "unknown" ? "" : "warn"),
    card("Screen", screen + (s.dashboard_visible ? "" : " · another app in front")),
    card("Someone present", s.present ? "yes" : "no"),
    card("Last motion", since(s.last_motion_at)),
    card("Last wake", s.last_wake_ms == null ? "-" : s.last_wake_ms + " ms to the next frame"),
    card("Last error", s.last_error || "none", s.last_error ? "warn" : "ok"),
    card("Reloads", s.reload_count + (s.last_reload_reason ? " · last: " + s.last_reload_reason : "")),
    card("Memory", s.app_memory_mb + " MB used · " + s.memory_available_mb + " MB free", s.memory_low ? "warn" : ""),
    card("Running for", Math.round(s.app_uptime_s / 3600) + " h"),
    card("Versions", "TV Kiosk " + s.app_version + " · WebView " + s.webview_version + " · Android " + s.android_version),
    card("Device", (s.manufacturer || "") + " " + (s.model || "") + " · " + (s.ip || "")),
    s.update
      ? card("Updates", s.update.available ? "TV Kiosk " + s.update.latest + " is out" : "up to date", s.update.available ? "warn" : "ok")
      : card("Updates", "not checked (Settings → check_updates)"),
  ];
  $("cards").replaceChildren(...cards);
  const w = s.wake || {};
  const wake = [
    card("Show over other apps", w.overlay_permission ? "allowed" : "missing: grant over adb", w.overlay_permission ? "ok" : "bad"),
    card("Turn the screen on", w.turn_screen_on, w.turn_screen_on === "denied" ? "bad" : "ok"),
    card("Battery optimisation", w.battery_optimization_exempt ? "exempt" : "may be stopped", w.battery_optimization_exempt ? "ok" : "warn"),
  ];
  if (w.low_power_standby !== undefined) {
    const cut = w.low_power_standby && !w.low_power_standby_exempt;
    wake.push(card("In standby", cut ? "cut off the network: HA must switch the TV on" : "stays reachable", cut ? "warn" : "ok"));
  }
  $("wake").replaceChildren(...wake);
  const b = $("brightness");
  if (document.activeElement !== b) { b.value = s.brightness; $("brightness-value").textContent = s.brightness < 0 ? "device setting" : s.brightness + "%"; }
}

// ---- settings --------------------------------------------------------------------------

async function loadSettings() {
  const [sch, values] = await Promise.all([api("/settings/schema"), api("/settings")]);
  schema = sch.settings;
  current = values.settings;
  for (const k of Object.keys(changes)) delete changes[k];
  renderSettings();
}

function field(item) {
  const name = item.name;
  const value = current[name] !== undefined ? current[name] : item.default;
  const changed = () => {
    let v;
    if (item.type === "boolean") v = input.checked;
    else if (item.type === "integer") v = input.value === "" ? null : Number(input.value);
    else v = input.value;
    if (item.secret ? v !== "" : v !== value) changes[name] = v; else delete changes[name];
    row.classList.toggle("changed", name in changes);
    $("save").disabled = Object.keys(changes).length === 0;
  };
  let input;
  if (item.type === "boolean") {
    input = el("input", { type: "checkbox", checked: !!value });
  } else if (item.choices) {
    input = el("select", {}, ...item.choices.map((c) => el("option", { value: c, textContent: c, selected: c === value })));
  } else if (item.type === "integer") {
    input = el("input", { type: "number", value: value });
  } else if (name === "pages" || name === "scenes") {
    input = el("textarea", { value: value });
  } else {
    input = el("input", { type: item.secret ? "password" : "text", value: item.secret ? "" : value, autocomplete: "off",
      placeholder: item.secret ? "unchanged" : "" });
  }
  input.id = "s-" + name;
  input.addEventListener(item.type === "boolean" || item.choices ? "change" : "input", changed);
  const row = el("div", { className: "setting" },
    el("label", { htmlFor: input.id }, el("div", { className: "name", textContent: LABELS[name] || name }), el("div", { className: "key", textContent: name })),
    input);
  row.dataset.search = (name + " " + (LABELS[name] || "")).toLowerCase();
  return row;
}

function renderSettings() {
  const byName = Object.fromEntries(schema.map((s) => [s.name, s]));
  const used = new Set();
  const groups = GROUPS.map(([title, names]) => {
    const items = names.filter((n) => byName[n]).map((n) => { used.add(n); return byName[n]; });
    return [title, items];
  });
  const rest = schema.filter((s) => !used.has(s.name));
  if (rest.length) groups.push(["Other", rest]);
  $("groups").replaceChildren(...groups.filter(([, items]) => items.length).map(([title, items]) =>
    el("div", { className: "group" }, el("h2", { textContent: title }), ...items.map(field))));
  $("save").disabled = true;
  applyFilter();
}

function applyFilter() {
  const q = $("filter").value.trim().toLowerCase();
  for (const row of document.querySelectorAll(".setting")) row.hidden = q !== "" && !row.dataset.search.includes(q);
  for (const group of document.querySelectorAll(".group")) group.hidden = [...group.querySelectorAll(".setting")].every((r) => r.hidden);
}

async function save() {
  const body = { ...changes };
  try {
    const result = await api("/settings", body);
    const rejected = Object.entries(result.rejected || {});
    $("save-result").textContent = rejected.length
      ? "Not saved: " + rejected.map(([k, v]) => (LABELS[k] || k) + " " + v).join("; ")
      : "Saved " + result.applied.length + " setting" + (result.applied.length === 1 ? "" : "s") + ".";
    if ("api_token" in body && body.api_token) { token = body.api_token; try { if (localStorage.getItem(STORE)) localStorage.setItem(STORE, token); else sessionStorage.setItem(STORE, token); } catch (e) { /* ignore */ } }
    await loadSettings();
  } catch (e) {
    $("save-result").textContent = e.message;
  }
}

async function exportSettings() {
  try {
    const data = await api("/settings");
    const name = "tv-kiosk-settings-" + (current.device_name || "tv").replace(/[^a-z0-9]+/gi, "-").toLowerCase() + ".json";
    const url = URL.createObjectURL(new Blob([JSON.stringify(data.settings, null, 2)], { type: "application/json" }));
    const a = el("a", { href: url, download: name });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    toast(e.message);
  }
}

async function importSettings(event) {
  const file = event.target.files[0];
  event.target.value = "";
  if (!file) return;
  let parsed;
  try {
    parsed = JSON.parse(await file.text());
  } catch (e) {
    $("save-result").textContent = "That file isn't a settings export.";
    return;
  }
  // This TV keeps its own name and API token.
  delete parsed.device_name;
  delete parsed.api_token;
  if (!confirm("Apply " + Object.keys(parsed).length + " settings from " + file.name + " to this TV?")) return;
  try {
    const result = await api("/settings", parsed);
    const rejected = Object.entries(result.rejected || {});
    $("save-result").textContent = "Imported " + result.applied.length + " settings." +
      (rejected.length ? " Skipped: " + rejected.map(([k, v]) => k + " (" + v + ")").join(", ") : "");
    await loadSettings();
  } catch (e) {
    $("save-result").textContent = e.message;
  }
}

// ---- log -------------------------------------------------------------------------------

async function loadLog() {
  try {
    $("log-text").textContent = await api("/logs?lines=300");
  } catch (e) {
    $("log-text").textContent = e.message;
  }
}

async function downloadDiagnostics(event) {
  event.preventDefault();
  try {
    const data = await api("/diagnostics");
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
    const a = el("a", { href: url, download: "tv-kiosk-diagnostics.json" });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    toast(e.message);
  }
}

// ---- start -----------------------------------------------------------------------------

document.addEventListener("DOMContentLoaded", () => {
  for (const tab of document.querySelectorAll("#tabs button")) tab.addEventListener("click", () => show(tab.dataset.tab));
  for (const button of document.querySelectorAll("[data-key]")) button.addEventListener("click", () => key(button.dataset.key));
  document.addEventListener("keydown", keyboardRemote);
  $("type-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const text = $("type-text").value;
    if (!text) return;
    try { await api("/remote", { text: text }); $("type-text").value = ""; } catch (err) { toast(err.message); }
  });
  for (const button of document.querySelectorAll("[data-cmd]")) {
    button.addEventListener("click", () => command(button.dataset.cmd, button.dataset.args ? JSON.parse(button.dataset.args) : {}));
  }
  $("connect-form").addEventListener("submit", (e) => { e.preventDefault(); connect($("token").value, $("remember").checked); });
  $("disconnect").addEventListener("click", forget);
  $("filter").addEventListener("input", applyFilter);
  $("save").addEventListener("click", save);
  $("export").addEventListener("click", exportSettings);
  $("import").addEventListener("change", importSettings);
  $("log-refresh").addEventListener("click", loadLog);
  $("diag").addEventListener("click", downloadDiagnostics);
  $("brightness").addEventListener("change", (e) => command("brightness", { level: Number(e.target.value) }));
  $("brightness").addEventListener("input", (e) => { $("brightness-value").textContent = e.target.value < 0 ? "device setting" : e.target.value + "%"; });
  $("open-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const target = $("open-target").value.trim();
    const back = Number($("open-return").value) || 0;
    const args = target.startsWith("/") ? { path: target } : { url: target };
    if (back > 0) args.return_after = back;
    command(target.startsWith("/") ? "navigate" : "url", args);
  });
  $("say-form").addEventListener("submit", (e) => { e.preventDefault(); command("tts", { message: $("say-text").value }); });
  $("popup-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const args = {};
    for (const [k, v] of new FormData(e.target)) if (v !== "") args[k] = k === "duration" ? Number(v) : v;
    command("notify", args);
  });
  setInterval(() => { if (!$("overview").hidden) refreshState(); }, 5000);

  // A token in the address (#token=..., from the TV's QR code) connects straight away, then leaves the address bar.
  const fromHash = new URLSearchParams(location.hash.slice(1)).get("token");
  if (fromHash) history.replaceState(null, "", location.pathname);
  let remembered = null;
  let session = null;
  try { remembered = localStorage.getItem(STORE); session = sessionStorage.getItem(STORE); } catch (e) { /* storage blocked */ }
  const start = fromHash || remembered || session;
  if (start) {
    $("token").value = start;
    $("remember").checked = !!remembered;
    connect(start, !!remembered);
  }
});
