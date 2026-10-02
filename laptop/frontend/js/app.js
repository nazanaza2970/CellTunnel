"use strict";

const $ = (id) => document.getElementById(id);
const pill = $("pill");
const uptime = $("uptime");
const logEl = $("log");
const msgEl = $("msg");
const btnStart = $("btnStart");
const btnStop = $("btnStop");
const btnApply = $("btnApply");
const btnUndo = $("btnUndo");

let lastLog = "";

function bindings() {
  const app = window.go && window.go.main && window.go.main.App;
  if (!app) {
    throw new Error("bindings not ready");
  }
  return app;
}

function showMsg(text, kind) {
  msgEl.textContent = text || "";
  msgEl.className = "msg" + (kind ? " " + kind : "");
}

function renderStatus(s) {
  const on = !!s.running;
  pill.textContent = on ? "ONLINE" : "OFFLINE";
  pill.className = "pill " + (on ? "on" : "off");
  uptime.textContent = on ? s.uptime : "";
  btnStart.disabled = on;
  btnStop.disabled = !on;
  if (s.log && s.log !== lastLog) {
    lastLog = s.log;
    logEl.hidden = false;
    logEl.textContent = s.log;
    logEl.scrollTop = logEl.scrollHeight;
  }
}

async function refresh() {
  try {
    renderStatus(await bindings().Status());
  } catch (e) {
    /* bindings not ready yet */
  }
}

async function start() {
  showMsg("", "");
  const app = bindings();
  try {
    await app.StartTunnel(
      $("proxy").value,
      $("pass").value,
      $("device").value,
      parseInt($("mtu").value, 10) || 1400
    );
    showMsg("tunnel started", "ok");
  } catch (e) {
    showMsg(String(e.message || e), "err");
  }
  refresh();
}

async function stop() {
  showMsg("", "");
  try {
    await bindings().StopTunnel();
    showMsg("tunnel stopped", "ok");
  } catch (e) {
    showMsg(String(e.message || e), "err");
  }
  refresh();
}

async function apply() {
  showMsg("", "");
  try {
    const out = await bindings().ApplyNetworkSetup();
    showMsg(out ? out : "network setup applied", out ? "" : "ok");
  } catch (e) {
    showMsg(String(e.message || e), "err");
  }
}

async function undo() {
  showMsg("", "");
  try {
    const out = await bindings().UndoNetworkSetup();
    showMsg(out ? out : "network setup undone", out ? "" : "ok");
  } catch (e) {
    showMsg(String(e.message || e), "err");
  }
}

btnStart.addEventListener("click", start);
btnStop.addEventListener("click", stop);
btnApply.addEventListener("click", apply);
btnUndo.addEventListener("click", undo);

document.addEventListener("click", async (ev) => {
  const btn = ev.target.closest(".copy");
  if (!btn) return;
  const code = btn.parentElement.querySelector("code");
  try {
    await navigator.clipboard.writeText(code.textContent);
    const old = btn.textContent;
    btn.textContent = "copied";
    setTimeout(() => (btn.textContent = old), 1200);
  } catch (e) {
    btn.textContent = "failed";
    setTimeout(() => (btn.textContent = "copy"), 1200);
  }
});

(async function init() {
  try {
    const os = await bindings().GetPlatform();
    document.body.classList.add(os === "windows" ? "is-win" : "is-linux");
  } catch (e) {
    document.body.classList.add("is-linux");
  }
  refresh();
  setInterval(refresh, 1500);
})();
