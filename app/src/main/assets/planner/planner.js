// Family planner: the household's Home Assistant calendars and to-do lists, served by OpenPane.
// On the TV the app hands the token in through openPaneConnect(); elsewhere it comes from #token=.
"use strict";

const PALETTE = ["#4c8dff", "#3fb950", "#f0883e", "#db61a2", "#a371f7", "#2fbcb2", "#e3b341", "#f85149"];
const REFRESH_MS = 60 * 1000;
const MAX_EVENTS = 7;
const MAX_ITEMS = 12;

let token = "";
let days = 7;
let data = null;
let lastDay = "";
let timer = 0;

const $ = (id) => document.getElementById(id);

function el(tag, props, ...children) {
  const node = document.createElement(tag);
  if (props) Object.assign(node, props);
  for (const child of children) if (child != null) node.append(child);
  return node;
}

// ---- dates ---------------------------------------------------------------------------------

/** "2026-10-31" or "2026-10-31T09:30" as a local Date. */
function parse(text) {
  const [date, time] = text.split("T");
  const [y, m, d] = date.split("-").map(Number);
  const [h, mi] = (time || "0:0").split(":").map(Number);
  return new Date(y, m - 1, d, h, mi);
}

function key(date) {
  return date.getFullYear() + "-" + String(date.getMonth() + 1).padStart(2, "0") + "-" + String(date.getDate()).padStart(2, "0");
}

function addDays(date, n) {
  const copy = new Date(date);
  copy.setDate(copy.getDate() + n);
  return copy;
}

const timeFormat = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
const dayName = new Intl.DateTimeFormat(undefined, { weekday: "short" });
const dayDate = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short" });
const longDate = new Intl.DateTimeFormat(undefined, { weekday: "long", day: "numeric", month: "long" });

/** The events on [day] (a midnight Date): all-day ones first, then by start time. */
function eventsOn(day) {
  const start = day;
  const end = addDays(day, 1);
  return data.events
    .map((e) => ({ e, from: parse(e.start), to: parse(e.end) }))
    .filter(({ e, from, to }) => (e.all_day ? from <= start && to > start : from < end && (to > start || +to === +from && from >= start)))
    .sort((a, b) => (b.e.all_day - a.e.all_day) || (a.from - b.from));
}

// ---- drawing -------------------------------------------------------------------------------

function colourOf(calendar) {
  const index = data.calendars.findIndex((c) => c.entity_id === calendar);
  return PALETTE[(index < 0 ? 0 : index) % PALETTE.length];
}

function drawClock() {
  const now = new Date();
  $("clock").textContent = timeFormat.format(now);
  $("today").textContent = longDate.format(now);
  if (data && key(now) !== lastDay) draw();
}

function draw() {
  const now = new Date();
  lastDay = key(now);
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());

  const legend = $("legend");
  legend.replaceChildren(...data.calendars.map((c) => {
    const dot = el("span", { className: "dot" });
    dot.style.setProperty("--cal", colourOf(c.entity_id));
    return el("li", null, dot, c.name || c.entity_id);
  }));

  const week = $("week");
  week.style.setProperty("--cols", String(Math.min(days, 7)));
  const cards = [];
  for (let i = 0; i < days; i++) {
    const day = addDays(today, i);
    const card = el("article", { className: "day" + (i === 0 ? " is-today" : "") });
    card.append(el("h2", null,
      el("span", { className: "name", textContent: i === 0 ? "Today" : i === 1 ? "Tomorrow" : dayName.format(day) }),
      el("span", { className: "date", textContent: dayDate.format(day) })));
    const list = el("div", { className: "events" });
    const events = eventsOn(day);
    for (const { e, from, to } of events.slice(0, MAX_EVENTS)) {
      const item = el("div", { className: "event" + (e.all_day ? " all-day" : "") });
      item.style.setProperty("--cal", colourOf(e.calendar));
      if (!e.all_day) {
        if (i === 0 && to <= now) item.classList.add("past");
        if (from <= now && to > now) item.classList.add("now");
        const label = from < day ? "continues" : timeFormat.format(from);
        item.append(el("span", { className: "time", textContent: label }));
      }
      item.append(el("span", { className: "what", textContent: e.summary }));
      // The place only where there's room: today's column, or a single-column (upright) layout.
      if (e.location && (i === 0 || days === 1)) item.append(el("span", { className: "where", textContent: e.location }));
      list.append(item);
    }
    if (events.length > MAX_EVENTS) list.append(el("div", { className: "more", textContent: "+" + (events.length - MAX_EVENTS) + " more" }));
    if (!events.length) list.append(el("div", { className: "free", textContent: "Nothing planned" }));
    card.append(list);
    cards.push(card);
  }
  week.replaceChildren(...cards);

  // The family board: notes across the top, ticked off like any to-do item.
  const board = data.board;
  const notes = board ? board.items.slice(0, 6).map((item) => {
    const note = el("button", { className: "note", type: "button", textContent: item.summary });
    note.addEventListener("click", () => tick(board.entity_id, item, note));
    return note;
  }) : [];
  $("board").replaceChildren(...notes);
  $("board").hidden = !notes.length;

  const lists = data.todos.map((todo) => {
    const items = todo.items.slice(0, MAX_ITEMS).map((item) => {
      const button = el("button", { className: "item", type: "button" }, el("span", { className: "box" }), el("span", { textContent: item.summary }));
      if (item.due) {
        const due = parse(item.due);
        button.append(el("span", { className: "due" + (due < today ? " late" : ""), textContent: dayDate.format(due) }));
      }
      button.addEventListener("click", () => tick(todo.entity_id, item, button));
      return el("li", null, button);
    });
    const more = todo.items.length > MAX_ITEMS ? el("li", { className: "more", textContent: "+" + (todo.items.length - MAX_ITEMS) + " more" }) : null;
    return el("section", { className: "list" },
      el("h2", null, el("span", { textContent: todo.name }), el("span", { className: "count", textContent: String(todo.items.length) })),
      items.length ? el("ul", null, ...items, more) : el("div", { className: "free", textContent: "All done" }));
  });
  $("todos").replaceChildren(...lists);
  $("todos").hidden = !lists.length;
  $("main").classList.toggle("no-todos", !lists.length);
  $("message").textContent = data.calendars.length || lists.length ? "" : "No calendars or to-do lists found in Home Assistant.";
  $("updated").textContent = "Updated " + timeFormat.format(now);
}

// ---- data ----------------------------------------------------------------------------------

async function api(path, body) {
  const options = { headers: { Authorization: "Bearer " + token } };
  if (body) {
    options.method = "POST";
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch("/api/v1" + path, options);
  const answer = await response.json().catch(() => ({}));
  if (!response.ok || answer.ok === false) throw new Error(answer.error || "The TV answered " + response.status);
  return answer;
}

async function load() {
  clearTimeout(timer);
  try {
    data = await api("/planner?days=" + days);
    draw();
  } catch (e) {
    // Keep showing the last week we had; say what's wrong underneath.
    $("message").textContent = (data ? "Not up to date: " : "") + e.message;
  }
  timer = setTimeout(load, REFRESH_MS);
}

async function tick(entity, item, button) {
  button.classList.add("done");
  try {
    await api("/planner/todo", { entity_id: entity, uid: item.uid, done: true });
    setTimeout(load, 800);
  } catch (e) {
    button.classList.remove("done");
    $("message").textContent = e.message;
  }
}

/** Called by OpenPane on the TV with its API token and the number of days to show. */
window.openPaneConnect = (value, count) => {
  document.documentElement.classList.add("tv");
  token = value;
  days = Math.max(1, Math.min(14, Number(count) || 7));
  load();
};

document.addEventListener("DOMContentLoaded", () => {
  drawClock();
  setInterval(drawClock, 10 * 1000);
  const params = new URLSearchParams(location.hash.slice(1));
  const fromHash = params.get("token");
  if (fromHash) {
    history.replaceState(null, "", location.pathname);
    days = Math.max(1, Math.min(14, Number(params.get("days")) || 7));
    token = fromHash;
    load();
  }
});
