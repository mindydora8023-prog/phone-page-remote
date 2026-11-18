const $ = (id) => document.getElementById(id);
const call = (method, ...args) => window.mygo.call(`Remote.${method}`, ...args);
const modeDirections = {
  reading: ["right", "left"],
  "reading-rtl": ["left", "right"],
  video: ["down", "up"],
  "video-reversed": ["up", "down"],
};
const direction = {
  right: [0, "\u624b\u6307\u53f3\u6ed1"], left: [180, "\u624b\u6307\u5de6\u6ed1"],
  up: [-90, "\u624b\u6307\u4e0a\u6ed1"], down: [90, "\u624b\u6307\u4e0b\u6ed1"],
};
const phases = {
  scanning: "\u626b\u63cf\u4e2d", connecting: "\u8fde\u63a5\u4e2d", disconnecting: "\u6b63\u5728\u65ad\u5f00",
};
let state;
let capture = null;
let editing = false;
let renderVersion = 0;
let phoneList = "";
let pulseTimer;

// Keycap labels used to switch size on a character-count cutoff, so "PageUp" and
// "PageDown" landed on unrelated sizes. Both keycaps now share ONE size: the
// largest that still fits the longer of the two labels.
const keyCanvas = document.createElement("canvas");
const keyContext = keyCanvas.getContext("2d");
const keySizes = [48, 45, 42, 39, 36, 33, 30, 27, 24, 21, 18, 15];
const keyFont = (size) => `800 ${size}px "Helvetica Neue",Arial,"Microsoft YaHei UI",sans-serif`;

// Horizontal room a label has inside one card, in CSS pixels.
function keyRoom(card) {
  if (!card || !card.clientWidth) return 0;
  const style = getComputedStyle(card);
  const padding = (parseFloat(style.paddingLeft) || 0) + (parseFloat(style.paddingRight) || 0);
  const gap = parseFloat(style.columnGap) || 0;
  const arrow = card.querySelector(".direction");
  const arrowWidth = arrow ? arrow.getBoundingClientRect().width : 0;
  return Math.max(24, card.clientWidth - padding - gap - arrowWidth - 4);
}

// Largest size from the scale whose rendered width fits `room`.
function sizeThatFits(text, room) {
  if (!keyContext || room <= 0) return keySizes[keySizes.length - 1];
  for (const size of keySizes) {
    keyContext.font = keyFont(size);
    if (keyContext.measureText(text).width <= room) return size;
  }
  return keySizes[keySizes.length - 1];
}

// One size for both cards, so the two labels never look unrelated.
function sharedKeycapSize(labels) {
  const room = Math.min(keyRoom($("prev-send")), keyRoom($("next-send")));
  if (room <= 0) return keySizes[0]; // not laid out yet: keep the default, fix on the next tick
  let size = keySizes[0];
  for (const label of labels) size = Math.min(size, sizeThatFits(label, room));
  return size;
}

function render(next) {
  const prev = state;
  state = next;
  const busy = Boolean(state.phase);
  const connected = Boolean(state.device);
  const waiting = state.phones.length > 0 && !connected && state.phase !== "scanning";
  $("connection").dataset.connected = connected;
  $("connection").textContent = phases[state.phase] || (connected ? "\u5df2\u8fde\u63a5" : "\u672a\u8fde\u63a5");
  $("scan").disabled = busy || connected;
  $("scan").textContent = state.phase === "scanning" ? "\u626b\u63cf\u4e2d\u2026" : (state.phones.length ? "\u91cd\u65b0\u626b\u63cf" : "\u626b\u63cf\u624b\u673a");
  $("initial").hidden = Boolean(state.message || connected || waiting);
  $("pair").hidden = !waiting;
  $("connect").disabled = !waiting || busy;
  $("phones").disabled = busy;
  $("code").disabled = busy;
  if (connected && !prev?.device) $("code").value = "";
  $("connected").hidden = !connected;
  $("device").textContent = state.device;
  $("disconnect").disabled = busy;
  $("message").hidden = !state.message;
  $("message").textContent = state.message;
  $("message").dataset.error = state.problem;
  const list = JSON.stringify(state.phones);
  if (list !== phoneList) {
    phoneList = list;
    const selected = $("phones").value;
    $("phones").replaceChildren(...state.phones.map((phone) => new Option(`${phone.name} \u00b7 ${phone.id.slice(6, 12)}`, phone.id)));
    if (state.phones.some((phone) => phone.id === selected)) $("phones").value = selected;
  }

  $("mode").disabled = Boolean(capture || editing);
  $("mode").value = state.settings.mode;
  const labels = ["prev", "next"].map((action) => (state.settings[action] || "").replace("Super", "Win"));
  const keycap = sharedKeycapSize(labels);
  ["prev", "next"].forEach((action, i) => {
    const text = labels[i];
    const send = $(`${action}-send`);
    const kbd = $(`${action}-key`);
    kbd.textContent = text;
    kbd.classList.toggle("combo", text.includes("+"));
    kbd.style.setProperty("--kbd-size", `${keycap}px`);
    const [angle, label] = direction[modeDirections[state.settings.mode][i]];
    send.style.setProperty("--angle", `${angle}deg`);
    $(`${action}-direction`).textContent = label;
    send.disabled = !connected || busy || Boolean(capture || editing);
    $(`${action}-edit`).disabled = Boolean(capture || editing);
  });
  if (prev && state.sent !== prev.sent) {
    clearTimeout(pulseTimer);
    document.querySelectorAll(".pulse").forEach((elem) => elem.classList.remove("pulse"));
    const sent = $(`${state.last}-send`);
    sent.classList.add("pulse");
    pulseTimer = setTimeout(() => sent.classList.remove("pulse"), 200);
  }
  $("global").disabled = (!connected && !state.global) || Boolean(capture || editing);
  $("global").setAttribute("aria-pressed", String(state.global));
  $("global").textContent = state.global ? "\u6682\u505c" : "\u542f\u7528";
}

async function refresh() {
  const version = ++renderVersion;
  const next = await call("State");
  if (version === renderVersion) render(next);
}

function showError(error) {
  $("alert").textContent = error?.message || String(error);
  $("alert").hidden = false;
}

async function change(method, ...args) {
  $("alert").hidden = true;
  try {
    await call(method, ...args);
  } catch (error) {
    showError(error);
  }
  await refresh().catch(showError);
}

const namedKeys = {
  ArrowUp: "Up", ArrowDown: "Down", ArrowLeft: "Left", ArrowRight: "Right",
  PageUp: "PageUp", PageDown: "PageDown", Home: "Home", End: "End", Space: "Space",
  Enter: "Enter", Tab: "Tab", Backspace: "Backspace", Delete: "Delete", Insert: "Insert",
  Escape: "Escape",
};

function readKey(event) {
  const code = event.code;
  const key = namedKeys[code] || (/^Key[A-Z]$|^Digit[0-9]$/.test(code) ? code.slice(-1) : /^F\d+$/.test(code) ? code : "");
  if (!key) return "";
  return [event.ctrlKey && "Ctrl", event.altKey && "Alt", event.shiftKey && "Shift", event.metaKey && "Super", key].filter(Boolean).join("+");
}

async function startCapture(action) {
  editing = true;
  render(state);
  const restoreGlobal = state.global;
  try {
    if (restoreGlobal) await call("SetGlobal", false);
      capture = { action, restoreGlobal, previous: state.settings[action] };
    $("capture").hidden = false;
    $("capture").textContent = "\u6309\u4e0b\u65b0\u5feb\u6377\u952e\uff0cEsc \u53d6\u6d88\u3002";
    $("capture").focus();
  } catch (error) {
    showError(error);
  } finally {
    editing = false;
    await refresh().catch(showError);
  }
}

async function finishCapture(key) {
  if (!capture) return;
  const { action, restoreGlobal, previous } = capture;
  capture = null;
  editing = true;
  $("capture").hidden = true;
  let changed = false;
  try {
    if (key && key !== previous) {
      await call("SetKey", action, key);
      changed = true;
    }
  } catch (error) {
    showError(error);
  }
  if (restoreGlobal) {
    try {
      await call("SetGlobal", true);
    } catch (error) {
      showError(error);
      if (changed) {
        try {
          await call("SetKey", action, previous);
          await call("SetGlobal", true);
        } catch (restoreError) {
          showError(restoreError);
        }
      }
    }
  }
  editing = false;
  await refresh().catch(showError);
  $(`${action}-edit`).focus();
}

document.addEventListener("keydown", (event) => {
  if (editing) return;
  const key = readKey(event);
  if (capture) {
    event.preventDefault();
    event.stopPropagation();
    if (key && !event.repeat && !event.isComposing) void finishCapture(key === "Escape" ? "" : key);
    return;
  }
  if (!state?.device || state.global || event.target.closest("input,select") || event.isComposing) return;
  const action = key === state.settings.prev ? "prev" : key === state.settings.next ? "next" : "";
  if (!action) return;
  event.preventDefault();
  if (!event.repeat && !state.phase) void change("Send", action);
}, true);
window.addEventListener("blur", () => { if (capture) void finishCapture(""); });
$("scan").onclick = () => change("Scan");
$("pair").onsubmit = (event) => {
  event.preventDefault();
  void change("Connect", $("phones").value, $("code").value);
};
$("disconnect").onclick = () => change("Disconnect");
$("mode").onchange = () => change("SetMode", $("mode").value);
$("global").onclick = () => change("SetGlobal", !state.global);
for (const action of ["prev", "next"]) {
  $(`${action}-send`).onclick = () => change("Send", action);
  $(`${action}-edit`).onclick = () => { $("alert").hidden = true; void startCapture(action); };
}
refresh().then(() => setInterval(() => refresh().catch(showError), 400)).catch(showError);
