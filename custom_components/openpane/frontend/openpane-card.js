// OpenPane card: control a TV or tablet running OpenPane from any Home Assistant dashboard.
// type: custom:openpane-card, entity: any entity of the screen (for example its Page sensor).
// Plain JavaScript, no build step; styled with Home Assistant's theme variables.

const KEYS = [
  ["up", "▲", "r-up"], ["left", "◀", "r-left"], ["ok", "OK", "r-ok"], ["right", "▶", "r-right"], ["down", "▼", "r-down"],
];

class OpenPaneCard extends HTMLElement {
  setConfig(config) {
    if (!config || !config.entity) throw new Error("Choose an entity of the OpenPane screen (for example its Page sensor)");
    this._config = config;
    this._built = false;
  }

  static getStubConfig(hass) {
    const entity = Object.keys(hass.entities || {}).find((id) => hass.entities[id].platform === "openpane");
    return { entity: entity || "" };
  }

  getCardSize() {
    return 5;
  }

  set hass(hass) {
    this._hass = hass;
    if (!this._built) this._build();
    const state = hass.states[this._config.entity];
    const device = this._device();
    this.shadowRoot.getElementById("name").textContent =
      this._config.title || (device && (device.name_by_user || device.name)) || "OpenPane";
    this.shadowRoot.getElementById("status").textContent = state ? state.state : "";
  }

  _device() {
    const entry = this._hass && this._hass.entities && this._hass.entities[this._config.entity];
    return entry && entry.device_id ? this._hass.devices[entry.device_id] : null;
  }

  /** Calls an openpane action on this screen; resolves true if it worked (errors show in the status). */
  _call(service, data) {
    const status = this.shadowRoot.getElementById("status");
    const device = this._device();
    if (!device) {
      status.textContent = "That entity isn't part of an OpenPane screen";
      return Promise.resolve(false);
    }
    return this._hass.callService("openpane", service, { device_id: [device.id], ...data }).then(
      () => true,
      (err) => {
        status.textContent = err.message || String(err);
        return false;
      },
    );
  }

  _build() {
    this._built = true;
    const root = this.shadowRoot || this.attachShadow({ mode: "open" });
    root.innerHTML = `
      <style>
        ha-card { padding: 16px; }
        .head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; margin-bottom: 12px; }
        #name { font-size: 1.15em; font-weight: 500; color: var(--primary-text-color); }
        #status { color: var(--secondary-text-color); font-size: .9em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .pad { display: grid; grid-template-columns: repeat(3, 52px); grid-template-rows: repeat(3, 52px); gap: 6px; justify-content: center; }
        .r-up { grid-column: 2; grid-row: 1; } .r-left { grid-column: 1; grid-row: 2; } .r-ok { grid-column: 2; grid-row: 2; }
        .r-right { grid-column: 3; grid-row: 2; } .r-down { grid-column: 2; grid-row: 3; }
        button { font: inherit; border-radius: 10px; border: 1px solid var(--divider-color); background: var(--card-background-color);
                 color: var(--primary-text-color); cursor: pointer; padding: 8px 12px; }
        button:hover { border-color: var(--primary-color); }
        .r-ok { background: var(--primary-color); color: var(--text-primary-color); border-color: var(--primary-color); }
        .row { display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; margin-top: 12px; }
        form { display: flex; gap: 8px; margin-top: 12px; }
        input { flex: 1; min-width: 0; font: inherit; padding: 8px 10px; border-radius: 10px; border: 1px solid var(--divider-color);
                background: var(--secondary-background-color); color: var(--primary-text-color); }
      </style>
      <ha-card>
        <div class="head"><div id="name"></div><div id="status"></div></div>
        <div class="pad">${KEYS.map(([key, label, cls]) => `<button class="${cls}" data-key="${key}" aria-label="${key}">${label}</button>`).join("")}</div>
        <div class="row">
          <button data-key="back">Back</button>
          <button id="wake">Wake</button>
          <button id="planner">Planner</button>
        </div>
        <form id="popup"><input id="popup-text" placeholder="Pop-up message" aria-label="Pop-up message" required><button>Show</button></form>
        <form id="note"><input id="note-text" maxlength="200" placeholder="Note for the family board" aria-label="Note for the family board" required><button>Post</button></form>
      </ha-card>`;
    root.querySelectorAll("[data-key]").forEach((b) => b.addEventListener("click", () => this._call("remote", { key: b.dataset.key })));
    root.getElementById("wake").addEventListener("click", () => this._call("wake", {}));
    root.getElementById("planner").addEventListener("click", () => this._call("open_url", { url: "kiosk:planner", return_after: 600 }));
    root.getElementById("popup").addEventListener("submit", (e) => {
      e.preventDefault();
      const input = root.getElementById("popup-text");
      this._call("notify", { title: input.value, duration: 15 }).then((ok) => { if (ok) input.value = ""; });
    });
    root.getElementById("note").addEventListener("submit", (e) => {
      e.preventDefault();
      const input = root.getElementById("note-text");
      this._call("post_note", { text: input.value }).then((ok) => { if (ok) input.value = ""; });
    });
  }
}

if (!customElements.get("openpane-card")) customElements.define("openpane-card", OpenPaneCard);
window.customCards = window.customCards || [];
if (!window.customCards.some((c) => c.type === "openpane-card")) {
  window.customCards.push({
    type: "openpane-card",
    name: "OpenPane",
    description: "Remote, pop-ups and notes for a screen running OpenPane",
  });
}
