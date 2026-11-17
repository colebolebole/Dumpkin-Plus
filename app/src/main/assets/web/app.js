'use strict';
/* Dumpkin+ dashboard. Plain JS, no dependencies. User-controlled text is only ever inserted with textContent. */
(function () {
  // ------------------------------------------------------------------ helpers
  const STATUS = { stopped: 'Offline', starting: 'Starting…', running: 'Online', stopping: 'Stopping…', crashed: 'Crashed' };
  const GAME_MODES = ['Survival', 'Creative', 'Adventure', 'Spectator'];
  const DIFFICULTIES = ['Peaceful', 'Easy', 'Normal', 'Hard'];
  const PRESETS = [
    { label: 'Fast join', view: 6, sim: 4 },
    { label: 'Balanced', view: 8, sim: 6 },
    { label: 'Detailed', view: 12, sim: 8 },
  ];

  const ICONS = {
    play: 'M8 5v14l11-7z',
    stop: 'M6 6h12v12H6z',
    add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
    back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    copy: 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
    trash: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
    refresh: 'M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    cloud: 'M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z',
    cloudOff: 'M19.35 10.04C18.67 6.59 15.64 4 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46C10.21 6.23 11.08 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19c1.66 0 3 1.34 3 3 0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 18.16 24 16.68 24 15c0-2.64-2.05-4.78-4.65-4.96zM3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4 3 5.27zM7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73z',
    error: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z',
    power: 'M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z',
    chip: 'M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z',
    logout: 'M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z',
    send: 'M2.01 21L23 12 2.01 3 2 10l15 2-15 2z',
    check: 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
    eye: 'M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z',
    eyeOff: 'M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z',
  };

  function icon(name, cls) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'i' + (cls ? ' ' + cls : ''));
    svg.setAttribute('aria-hidden', 'true');
    const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    p.setAttribute('d', ICONS[name]);
    svg.append(p);
    return svg;
  }

  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'hidden') el[k] = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat(Infinity)) {
      if (kid === undefined || kid === null || kid === false) continue;
      el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
    return el;
  }

  const fmtMb = (mb) => (mb >= 1024 ? (mb / 1024).toFixed(1) + ' GB' : Math.round(mb) + ' MB');
  const fmtBytes = (b) => (b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : b >= 1024 ? Math.round(b / 1024) + ' KB' : b + ' B');
  function fmtDuration(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
    if (hh > 0) return hh + 'h ' + String(mm).padStart(2, '0') + 'm';
    if (mm > 0) return mm + 'm ' + String(ss).padStart(2, '0') + 's';
    return ss + 's';
  }
  const isActive = (s) => s.status === 'starting' || s.status === 'running' || s.status === 'stopping';
  // A restart goes stopping, then offline for a moment, then starting. Show it as one steady "restarting" state.
  const isRestarting = (s) => s.restarting === true;
  const isBusy = (s) => s.status === 'starting' || s.status === 'stopping' || isRestarting(s);
  const statusText = (s) => (isRestarting(s) ? 'Restarting…' : STATUS[s.status]);
  const visualStatus = (s) => (isRestarting(s) ? 'starting' : s.status);

  function toast(message, kind) {
    const t = h('div', { class: 'toast' + (kind === 'error' ? ' error' : ''), role: kind === 'error' ? 'alert' : 'status' }, message);
    $('#toasts').append(t);
    setTimeout(() => t.remove(), kind === 'error' ? 6000 : 3200);
  }
  function $(sel, root) { return (root || document).querySelector(sel); }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
    } catch (_) {
      const ta = h('textarea', { value: text, readonly: '' });
      ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.append(ta); ta.select();
      try { document.execCommand('copy'); } catch (__) { /* ignore */ }
      ta.remove();
    }
    toast('Copied ' + text);
  }

  // ------------------------------------------------------------------ API
  class ApiError extends Error {
    constructor(status, message) { super(message); this.status = status; }
  }

  async function api(method, path, body) {
    let res;
    try {
      res = await fetch(path, {
        method,
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'X-Requested-With': 'Dumpkin', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        body: body !== undefined ? JSON.stringify(body) : undefined,
      });
    } catch (_) {
      throw new ApiError(0, "Can't reach your phone.");
    }
    let data = null;
    try { data = await res.json(); } catch (_) { /* no body */ }
    if (!res.ok) throw new ApiError(res.status, (data && data.error) || 'Something went wrong (' + res.status + ').');
    return data;
  }

  // ------------------------------------------------------------------ shared widgets
  function seg(options, value, onChange, cls) {
    const wrap = h('div', { class: 'seg' + (cls ? ' ' + cls : ''), role: 'group' });
    const buttons = options.map((o) => {
      const b = h('button', {
        type: 'button',
        'aria-pressed': String(o.value === value),
        onclick: () => { wrap.set(o.value); onChange(o.value); },
      }, o.label);
      wrap.append(b);
      return [o, b];
    });
    wrap.set = (v) => buttons.forEach(([o, b]) => b.setAttribute('aria-pressed', String(o.value === v)));
    return wrap;
  }

  function toggle(checked, onChange, label) {
    const input = h('input', { type: 'checkbox', checked: !!checked, 'aria-label': label, onchange: () => onChange(input.checked) });
    return h('label', { class: 'switch' }, input, h('span'));
  }

  function switchRow(title, desc, checked, onChange) {
    return h('div', { class: 'row-switch' },
      h('div', { class: 'txt' }, h('div', { class: 't' }, title), desc ? h('div', { class: 'd' }, desc) : null),
      toggle(checked, onChange, title));
  }

  function field(label, control, wide) {
    const err = h('div', { class: 'error-text', role: 'alert' });
    const f = h('div', { class: 'field' + (wide ? ' wide' : '') }, h('label', {}, label), control, err);
    f.error = (msg) => { err.textContent = msg || ''; const i = control.matches && control.matches('.input') ? control : control.querySelector && control.querySelector('.input'); if (i) i.classList.toggle('bad', !!msg); };
    return f;
  }

  function slider(min, max, step, value, fmt, onChange) {
    const out = h('output', {}, fmt(value));
    const input = h('input', { type: 'range', min, max, step, value, 'aria-label': 'value', oninput: () => { out.textContent = fmt(+input.value); onChange(+input.value); } });
    const el = h('div', { class: 'range' }, input, out);
    el.set = (v) => { input.value = v; out.textContent = fmt(+v); };
    return el;
  }

  function dialog(build) {
    const dlg = h('dialog');
    const close = (result) => { dlg.close(); dlg.remove(); if (dlg.resolve) dlg.resolve(result); };
    dlg.append(build(close, dlg));
    dlg.addEventListener('cancel', () => { dlg.remove(); if (dlg.resolve) dlg.resolve(undefined); });
    document.body.append(dlg);
    dlg.showModal();
    return dlg;
  }

  function confirmDialog({ title, body, confirm, danger }) {
    return new Promise((resolve) => {
      const dlg = dialog((close) => h('div', { class: 'dlg' },
        h('h2', {}, title), h('p', { class: 'hint' }, body),
        h('div', { class: 'actions' },
          h('button', { class: 'btn outline', type: 'button', onclick: () => close(false) }, 'Cancel'),
          h('button', { class: 'btn ' + (danger ? 'danger' : ''), type: 'button', onclick: () => close(true) }, confirm))));
      dlg.resolve = resolve;
    });
  }

  function blobEl(status, large) {
    const el = h('div', { class: 'blob ' + status + (large ? ' lg' : ''), 'aria-hidden': 'true' });
    setBlob(el, status, large);
    return el;
  }
  function setBlob(el, status, large) {
    if (el.dataset.status === status) return;
    el.dataset.status = status;
    el.className = 'blob ' + status + (large ? ' lg' : '');
    const name = status === 'running' ? 'cloud' : status === 'stopped' ? 'cloudOff' : status === 'crashed' ? 'error' : 'power';
    el.replaceChildren(icon(name));
  }

  // ------------------------------------------------------------------ app state
  let state = null;          // last /api/state
  let fetchedAt = 0;         // Date.now() when state arrived
  let route = parseRoute();
  let pollTimer = null;
  let view = null;           // current view controller { update(), destroy() }
  let shell = null;          // refs to the persistent chrome
  let engineDialog = null;

  function parseRoute() {
    const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
    if (parts[0] === 'server' && parts[1]) return { name: 'server', id: decodeURIComponent(parts[1]), tab: parts[2] || 'console' };
    return { name: 'home' };
  }
  function go(hash) { location.hash = hash; }

  const serverById = (id) => (state && state.servers.find((s) => s.id === id)) || null;
  const addressFor = (s) => ((state && state.device.addresses[0]) || location.hostname) + ':' + s.port;

  // ------------------------------------------------------------------ boot / login / polling
  async function boot() {
    try {
      applyState(await api('GET', '/api/state'));
      mountShell();
      renderRoute();
      schedulePoll();
    } catch (e) {
      if (e.status === 401) showLogin();
      else showFatal(e.message);
    }
  }

  function showFatal(message) {
    $('#app').replaceChildren(h('div', { class: 'login' }, h('div', { class: 'card' },
      h('h1', {}, 'Dumpkin+'), h('p', {}, message),
      h('button', { class: 'btn', onclick: boot }, 'Try again'))));
  }

  function showLogin(notice) {
    clearTimeout(pollTimer);
    if (view) { view.destroy(); view = null; }
    shell = null; state = null;
    const err = h('div', { class: 'error-text', role: 'alert' }, notice || '');
    const input = h('input', { class: 'input', type: 'password', autocomplete: 'current-password', placeholder: 'XXXX-XXXX-XXXX', 'aria-label': 'Password' });
    let shown = false;
    const eye = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Show password', onclick: () => {
      shown = !shown; input.type = shown ? 'text' : 'password'; eye.replaceChildren(icon(shown ? 'eyeOff' : 'eye'));
    } }, icon('eye'));
    const submit = h('button', { class: 'btn big', type: 'submit' }, 'Sign in');
    const form = h('form', { onsubmit: async (ev) => {
      ev.preventDefault();
      submit.disabled = true; err.textContent = '';
      try {
        await api('POST', '/api/login', { password: input.value.trim() });
        boot();
      } catch (e) {
        err.textContent = e.message; input.select();
      } finally { submit.disabled = false; }
    } }, h('div', { class: 'pw' }, input, eye), err, submit);
    $('#app').replaceChildren(h('div', { class: 'login' }, h('div', { class: 'card' },
      h('img', { src: '/logo.svg', alt: '' }), h('h1', {}, 'Dumpkin+'),
      h('p', {}, 'Enter the dashboard password shown in the app, on the Dashboard tab.'), form)));
    input.focus();
  }

  function schedulePoll() {
    clearTimeout(pollTimer);
    const busy = state && (state.engine.downloadingId || state.engine.checking || state.servers.some((s) => s.status === 'starting' || s.status === 'stopping'));
    // Gentler than the stock dashboard: every poll is work for an older phone's CPU and Wi-Fi.
    const delay = document.hidden ? 10000 : busy ? 1200 : 3000;
    pollTimer = setTimeout(poll, delay);
  }

  async function poll() {
    try {
      applyState(await api('GET', '/api/state'));
      setOffline(false);
      if (view) view.update();
      renderEngineDialog();
    } catch (e) {
      if (e.status === 401) { showLogin('You were signed out. Sign in again.'); return; }
      setOffline(true);
    }
    schedulePoll();
  }
  document.addEventListener('visibilitychange', () => { if (state && !document.hidden) { clearTimeout(pollTimer); poll(); } });

  function applyState(s) { state = s; fetchedAt = Date.now(); }

  function setOffline(off) {
    if (!shell) return;
    shell.offline.hidden = !off;
  }

  // Re-fetch right after an action so the UI reflects it without waiting for the next tick.
  async function refreshNow() {
    try { applyState(await api('GET', '/api/state')); if (view) view.update(); renderEngineDialog(); } catch (_) { /* poll will report */ }
    schedulePoll();
  }

  // ------------------------------------------------------------------ shell
  function mountShell() {
    const chips = h('div', { class: 'chips' });
    const offline = h('div', { class: 'offline', role: 'status', hidden: true }, "Can't reach your phone. Retrying…");
    const main = h('main');
    shell = { chips, offline, main };
    $('#app').replaceChildren(h('div', { class: 'shell' },
      offline,
      h('header', { class: 'topbar' },
        h('a', { class: 'brand', href: '#/' }, h('img', { src: '/logo.svg', alt: '' }), 'Dumpkin+'),
        h('div', { class: 'grow' }), chips,
        h('button', { class: 'icon-btn', title: 'Engine builds', 'aria-label': 'Engine builds', onclick: openEngine }, icon('chip')),
        h('button', { class: 'icon-btn', title: 'Sign out', 'aria-label': 'Sign out', onclick: async () => {
          try { await api('POST', '/api/logout'); } catch (_) { /* ignore */ }
          showLogin();
        } }, icon('logout'))),
      main));
    updateChips();
  }

  function updateChips() {
    if (!shell || !state) return;
    const d = state.device;
    const running = state.servers.filter((s) => s.status === 'running').length;
    shell.chips.replaceChildren(
      h('span', { class: 'chip' }, 'Servers online ', h('b', {}, running + '/' + state.servers.length)),
      h('span', { class: 'chip' }, 'Free RAM ', h('b', {}, fmtMb(d.availMb) + ' of ' + fmtMb(d.totalMb))),
      h('span', { class: 'chip' }, 'v', state.app.version));
  }

  window.addEventListener('hashchange', () => { route = parseRoute(); if (shell) renderRoute(); });

  function renderRoute() {
    if (view) { view.destroy(); view = null; }
    shell.main.replaceChildren();
    if (route.name === 'server') {
      const s = serverById(route.id);
      if (!s) { go('#/'); toast('That server no longer exists.', 'error'); return; }
      view = serverView(route.id, route.tab);
      document.title = s.name + ' · Dumpkin+';
    } else {
      view = homeView();
      document.title = 'Dumpkin+';
    }
    shell.main.append(view.el);
    view.update();
  }

  // ------------------------------------------------------------------ home
  function homeView() {
    const grid = h('div', { class: 'grid' });
    const bannerSlot = h('div');
    const emptySlot = h('div');
    const el = h('div', {},
      h('div', { class: 'page-head' },
        h('div', {}, h('h1', {}, 'Servers'), h('div', { class: 'sub', id: 'sub' })),
        h('div', { class: 'grow' }),
        h('button', { class: 'btn big', onclick: openCreate }, icon('add'), 'New server')),
      bannerSlot, grid, emptySlot);
    const cards = new Map();

    function buildCard(s) {
      const blob = blobEl(s.status);
      const name = h('div', { class: 'name' });
      const stateEl = h('div', { class: 'state' });
      const meta = h('div', { class: 'meta' });
      const power = h('button', { class: 'power', type: 'button', onclick: (ev) => { ev.stopPropagation(); toggleServer(cards.get(s.id).data, power); } });
      const card = h('div', { class: 'card server-card link', role: 'link', tabindex: '0',
        onclick: () => go('#/server/' + encodeURIComponent(s.id)),
        onkeydown: (ev) => { if (ev.key === 'Enter') go('#/server/' + encodeURIComponent(s.id)); } },
        blob, h('div', { class: 'mid' }, name, stateEl, meta), power);
      cards.set(s.id, { el: card, blob, name, stateEl, meta, power, data: s });
      return card;
    }

    function updateCard(s) {
      const c = cards.get(s.id);
      c.data = s;
      c.el.classList.toggle('is-running', s.status === 'running');
      setBlob(c.blob, visualStatus(s));
      c.name.textContent = s.name;
      c.stateEl.textContent = statusText(s) + (s.status === 'running' ? ' · ' + s.online + '/' + s.max + ' players' : '');
      c.meta.textContent = 'Port ' + s.port + ' · ' + s.gameMode + ' · ' + s.difficulty;
      const busy = isBusy(s);
      c.power.className = 'power' + (isActive(s) ? ' stop' : '');
      c.power.disabled = busy;
      c.power.setAttribute('aria-label', (isActive(s) ? 'Stop ' : 'Start ') + s.name);
      c.power.replaceChildren(busy ? icon('refresh', 'spin') : icon(isActive(s) ? 'stop' : 'play'));
    }

    function update() {
      updateChips();
      const servers = state.servers;
      const ids = new Set(servers.map((s) => s.id));
      for (const [id, c] of cards) if (!ids.has(id)) { c.el.remove(); cards.delete(id); }
      servers.forEach((s, i) => {
        if (!cards.has(s.id)) buildCard(s);
        updateCard(s);
        const node = cards.get(s.id).el;
        if (grid.children[i] !== node) grid.insertBefore(node, grid.children[i] || null);
      });
      const online = servers.filter((s) => s.status === 'running').length;
      $('#sub', el).textContent = servers.length === 0 ? '' : online + ' of ' + servers.length + ' online';

      const e = state.engine;
      if (!e.supported) {
        bannerSlot.replaceChildren(h('div', { class: 'banner error' }, h('div', { class: 'grow' }, h('b', {}, "This phone can't run Pumpkin"),
          "Pumpkin's Android build is arm64 only; other CPUs need a self-built engine.")));
      } else if (e.installed.length === 0) {
        bannerSlot.replaceChildren(h('div', { class: 'banner' }, h('div', { class: 'grow' }, h('b', {}, 'Get the Pumpkin engine'),
          'Servers need the Pumpkin server binary. It is a one-time download from the official GitHub releases.'),
          h('button', { class: 'btn', onclick: openEngine }, icon('chip', 'sm'), 'Open engine')));
      } else bannerSlot.replaceChildren();

      emptySlot.replaceChildren(servers.length ? '' : h('div', { class: 'empty' }, blobEl('stopped', true),
        h('h2', {}, 'No servers yet'), h('p', {}, 'Create one and your phone becomes a Minecraft host.')));
    }
    return { el, update, destroy() {} };
  }

  async function toggleServer(s, btn) {
    await serverAction(s, isActive(s) ? '/stop' : '/start', btn);
  }

  async function restartServer(s, btn) {
    if (s.online > 0) {
      // Restarting disconnects everyone, so check first if anyone is playing.
      const ok = await confirmDialog({
        title: 'Restart "' + s.name + '"?',
        body: s.online + (s.online === 1 ? ' player is' : ' players are') + ' online and will be disconnected. The server comes back in a few seconds.',
        confirm: 'Restart',
      });
      if (!ok) return;
    }
    await serverAction(s, '/restart', btn);
  }

  async function serverAction(s, path, btn) {
    if (btn) btn.disabled = true;
    try {
      const updated = await api('POST', '/api/servers/' + encodeURIComponent(s.id) + path);
      const i = state.servers.findIndex((x) => x.id === s.id);
      if (i >= 0) state.servers[i] = updated;
      if (view) view.update();
      refreshNow();
    } catch (e) {
      toast(e.message, 'error');
      if (btn) btn.disabled = false;
    }
  }

  // ------------------------------------------------------------------ create dialog
  function openCreate() {
    let mode = 'Survival';
    let preset = PRESETS[0];
    const name = h('input', { class: 'input', value: 'My Pumpkin Server', maxlength: '40', 'aria-label': 'Name' });
    const nameField = field('Name', name);
    // Bedrock servers each need their own port: suggest the lowest one no server has claimed (Java or Bedrock).
    let bedrock = false;
    const claimed = new Set();
    state.servers.forEach((s) => { claimed.add(s.port); if (s.bedrock) claimed.add(s.bedrockPort); });
    let suggested = 19132;
    while (claimed.has(suggested)) suggested++;
    const bedrockPort = h('input', { class: 'input', type: 'number', inputmode: 'numeric', value: suggested, 'aria-label': 'Bedrock port' });
    const bedrockField = field('Bedrock port', bedrockPort);
    bedrockField.hidden = true;
    dialog((close) => {
      const create = h('button', { class: 'btn', type: 'submit' }, 'Create server');
      return h('form', { class: 'dlg', onsubmit: async (ev) => {
        ev.preventDefault();
        if (!name.value.trim()) { nameField.error('Give the server a name.'); return; }
        const body = { name: name.value.trim(), gameMode: mode, viewDistance: preset.view, simulationDistance: preset.sim };
        if (bedrock) { body.bedrock = true; body.bedrockPort = Number(bedrockPort.value); }
        create.disabled = true;
        try {
          const s = await api('POST', '/api/servers', body);
          close(true);
          await refreshNow();
          go('#/server/' + encodeURIComponent(s.id));
        } catch (e) { (bedrock ? bedrockField : nameField).error(e.message); create.disabled = false; }
      } },
        h('h2', {}, 'New server'),
        nameField,
        h('div', { class: 'field' }, h('div', { class: 'label' }, 'Game mode'), seg(GAME_MODES.map((m) => ({ value: m, label: m })), mode, (v) => { mode = v; })),
        h('div', { class: 'field' }, h('div', { class: 'label' }, 'Distance'), seg(PRESETS.map((p) => ({ value: p, label: p.label })), preset, (v) => { preset = v; }),
          h('div', { class: 'hint' }, 'Fewer chunks means a faster join on a phone. You can change this later.')),
        switchRow('Bedrock support (experimental)', 'Lets Bedrock players join on their own port.', false,
          (v) => { bedrock = v; bedrockField.hidden = !v; bedrockField.error(''); }),
        bedrockField,
        h('div', { class: 'actions' },
          h('button', { class: 'btn outline', type: 'button', onclick: () => close(false) }, 'Cancel'), create));
    });
    name.focus(); name.select();
  }

  // ------------------------------------------------------------------ server page
  function serverView(id, tab) {
    const hero = h('div', { class: 'card hero' });
    const panel = h('div');
    const tabs = seg([
      { value: 'console', label: 'Console' }, { value: 'settings', label: 'Settings' }, { value: 'config', label: 'Config file' },
    ], tab, (v) => go('#/server/' + encodeURIComponent(id) + '/' + v), 'tabs');
    const el = h('div', {},
      h('a', { class: 'back', href: '#/' }, icon('back'), 'All servers'),
      hero, tabs, panel);

    const refs = {};
    let panelCtl = null;

    function buildHero() {
      refs.blob = blobEl('stopped', true);
      refs.title = h('h1');
      refs.state = h('div', { class: 'state' });
      refs.detail = h('div', { class: 'detail' });
      refs.addrText = h('span');
      refs.copy = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Copy address', onclick: () => copyText(refs.addrText.textContent) }, icon('copy', 'sm'));
      refs.addr = h('span', { class: 'addr' }, refs.addrText, refs.copy);
      refs.power = h('button', { class: 'btn big', type: 'button', onclick: () => toggleServer(serverById(id), refs.power) });
      refs.restart = h('button', { class: 'btn big outline', type: 'button', hidden: true,
        onclick: () => restartServer(serverById(id), refs.restart) }, icon('refresh'), 'Restart');
      refs.memBar = h('i');
      refs.memText = h('span');
      refs.mem = h('div', { class: 'mem-line', hidden: true }, refs.memText, h('div', { class: 'meter' }, refs.memBar));
      hero.append(refs.blob, h('div', {}, refs.title, refs.state, refs.detail),
        h('div', { class: 'actions' }, refs.power, refs.restart, refs.addr, refs.mem));
    }
    buildHero();

    function updateHero(s) {
      setBlob(refs.blob, visualStatus(s), true);
      hero.className = 'card hero ' + visualStatus(s);
      refs.title.textContent = s.name;
      refs.state.textContent = statusText(s);
      const bits = [];
      if (isRestarting(s)) {
        bits.push('Back in a few seconds, with your latest settings');
      } else if (s.status === 'running') {
        bits.push(s.online + '/' + s.max + ' players');
        bits.push('up ' + fmtDuration(state.now - s.startedAt + (Date.now() - fetchedAt)));
        if (s.version) bits.push('Minecraft ' + s.version);
      } else if (s.status === 'crashed') bits.push(s.error || 'The server stopped unexpectedly');
      else if (s.status === 'stopped') bits.push('Start it, then players can join at the address below.');
      refs.detail.textContent = bits.join(' · ');
      refs.addrText.textContent = addressFor(s);
      const busy = isBusy(s);
      refs.power.className = 'btn big ' + (isActive(s) ? 'danger' : '');
      refs.power.disabled = busy;
      refs.power.replaceChildren(busy ? icon('refresh', 'spin') : icon(isActive(s) ? 'stop' : 'play'),
        busy ? statusText(s) : isActive(s) ? 'Stop server' : 'Start server');
      refs.restart.hidden = !(s.status === 'running' && !busy);
      refs.restart.disabled = busy;
      refs.mem.hidden = !(isActive(s) && s.rssMb > 0);
      if (!refs.mem.hidden) {
        refs.memText.textContent = 'Memory ' + fmtMb(s.rssMb) + (s.memoryLimitMb > 0 ? ' / ' + fmtMb(s.memoryLimitMb) : '');
        refs.memBar.style.width = (s.memoryLimitMb > 0 ? Math.min(100, (s.rssMb / s.memoryLimitMb) * 100) : 0) + '%';
        refs.mem.querySelector('.meter').hidden = s.memoryLimitMb <= 0;
      }
    }

    function mountPanel() {
      if (panelCtl) panelCtl.destroy();
      panel.replaceChildren();
      const s = serverById(id);
      panelCtl = tab === 'settings' ? settingsPanel(s) : tab === 'config' ? configPanel(s) : consolePanel(s);
      panel.append(panelCtl.el);
    }
    mountPanel();

    return {
      el,
      update() {
        const s = serverById(id);
        if (!s) { go('#/'); toast('That server was removed.', 'error'); return; }
        updateChips();
        updateHero(s);
        if (panelCtl && panelCtl.update) panelCtl.update(s);
      },
      destroy() { if (panelCtl) panelCtl.destroy(); },
    };
  }

  // ---- console
  function consolePanel(server) {
    const id = server.id;
    const box = h('div', { class: 'console', tabindex: '0', role: 'log', 'aria-label': 'Server console' });
    const input = h('input', { class: 'input', placeholder: 'Type a command (no slash)', 'aria-label': 'Command', autocomplete: 'off', spellcheck: 'false' });
    const sendBtn = h('button', { class: 'btn', type: 'submit', 'aria-label': 'Send' }, icon('send', 'sm'), 'Send');
    const quick = ['help', 'list'].map((c) => h('button', { class: 'chip', type: 'button', onclick: () => send(c) }, c));
    const clear = h('button', { class: 'chip', type: 'button', onclick: () => { box.replaceChildren(); count = 0; showEmpty(); } }, 'Clear view');
    const el = h('div', {}, box,
      h('div', { class: 'quick' }, ...quick, clear),
      h('form', { class: 'cmdbar', onsubmit: (ev) => { ev.preventDefault(); send(input.value); } }, input, sendBtn));
    let cursor = -1, alive = true, timer = null, count = 0;

    function showEmpty() { if (!box.children.length) box.append(h('div', { class: 'none' }, 'Server output will show up here.')); }
    function lineClass(t) {
      if (t.startsWith('>')) return 'cmd';
      if (t.startsWith('✖') || /\bERROR\b|panicked/i.test(t)) return 'err';
      if (t.startsWith('⚠') || /\bWARN\b/i.test(t)) return 'warn';
      if (/^[▶■↻ℹ]/.test(t)) return 'sys';
      return '';
    }
    function append(lines, reset) {
      if (reset) { box.replaceChildren(); count = 0; }
      if (!lines.length) { if (reset) showEmpty(); return; }
      const none = box.querySelector('.none'); if (none) none.remove();
      const stick = box.scrollHeight - box.scrollTop - box.clientHeight < 48;
      const frag = document.createDocumentFragment();
      for (const t of lines) { frag.append(h('div', { class: 'ln ' + lineClass(t) }, t || ' ')); count++; }
      box.append(frag);
      while (count > 1500) { box.firstChild.remove(); count--; }
      if (stick || reset) box.scrollTop = box.scrollHeight;
    }
    async function pull() {
      if (!alive) return;
      try {
        const r = await api('GET', '/api/servers/' + encodeURIComponent(id) + '/logs?after=' + cursor);
        cursor = r.cursor;
        append(r.lines, r.reset);
      } catch (e) {
        if (e.status === 401) return;
      }
      if (alive) timer = setTimeout(pull, document.hidden ? 4000 : 1000);
    }
    async function send(text) {
      const cmd = (text || '').trim();
      if (!cmd) return;
      try {
        await api('POST', '/api/servers/' + encodeURIComponent(id) + '/command', { command: cmd });
        input.value = '';
        clearTimeout(timer); pull();
      } catch (e) { toast(e.message, 'error'); }
    }
    function update(s) {
      const on = isActive(s);
      input.disabled = !on; sendBtn.disabled = !on;
      quick.forEach((q) => { q.disabled = !on; });
      input.placeholder = on ? 'Type a command (no slash)' : 'Start the server to send commands';
    }
    update(server);
    pull();
    return { el, update, destroy() { alive = false; clearTimeout(timer); } };
  }

  // ---- settings
  function settingsPanel(server) {
    const id = server.id;
    const KEYS = ['name', 'motd', 'port', 'maxPlayers', 'viewDistance', 'simulationDistance', 'gameMode', 'difficulty', 'onlineMode', 'bedrock', 'bedrockPort',
      'memoryLimitMb', 'memoryAction', 'cpuThreads', 'autoRestart', 'lightPing', 'compression', 'fetchSkins', 'engineId'];
    const pick = (s) => Object.fromEntries(KEYS.map((k) => [k, s[k]]));
    let baseline = pick(server);
    const draft = { ...baseline };
    const dev = state.device;
    const maxLimit = Math.max(512, Math.floor((dev.totalMb * 0.75) / 128) * 128);

    const saveBtn = h('button', { class: 'btn', type: 'button', onclick: save }, 'Save changes');
    const resetBtn = h('button', { class: 'btn outline', type: 'button', onclick: () => { Object.assign(draft, baseline); rebuild(); refreshBar(); } }, 'Discard');
    const barText = h('div', { class: 'grow' });
    const bar = h('div', { class: 'savebar', hidden: true }, barText, resetBtn, saveBtn);
    const body = h('div');
    const el = h('div', {}, body, bar);

    const changed = () => KEYS.filter((k) => draft[k] !== baseline[k]);
    function problems() {
      const p = {};
      const nameOk = typeof draft.name === 'string' && draft.name.trim().length > 0 && draft.name.length <= 40;
      if (!nameOk) p.name = 'Give the server a name (up to 40 characters).';
      if (!Number.isInteger(draft.port) || draft.port < 1024 || draft.port > 65535) p.port = 'Use a port between 1024 and 65535.';
      else if (state.servers.some((s) => s.id !== id && s.port === draft.port)) p.port = 'Another server already uses this port.';
      else if (state.servers.some((s) => s.id !== id && s.bedrock && s.bedrockPort === draft.port)) p.port = 'Another server uses this port for Bedrock.';
      if (draft.bedrock) {
        if (!Number.isInteger(draft.bedrockPort) || draft.bedrockPort < 1024 || draft.bedrockPort > 65535) p.bedrockPort = 'Use a port between 1024 and 65535.';
        else if (draft.bedrockPort === draft.port) p.bedrockPort = 'Must differ from the Java port.';
        else if (state.servers.some((s) => s.id !== id && s.bedrock && s.bedrockPort === draft.bedrockPort)) p.bedrockPort = 'Another server already uses this Bedrock port.';
        else if (state.servers.some((s) => s.id !== id && s.port === draft.bedrockPort)) p.bedrockPort = 'Another server uses this port for Java.';
      }
      if (!Number.isInteger(draft.maxPlayers) || draft.maxPlayers < 1 || draft.maxPlayers > 1000) p.maxPlayers = '1 to 1000.';
      return p;
    }
    function refreshBar() {
      const n = changed().length;
      const bad = Object.keys(problems()).length > 0;
      bar.hidden = n === 0;
      barText.textContent = n + (n === 1 ? ' unsaved change' : ' unsaved changes');
      saveBtn.disabled = bad;
    }
    function set(key, value) { draft[key] = value; refreshBar(); }
    function showProblems(fields) { const p = problems(); for (const [k, f] of Object.entries(fields)) f.error(p[k]); }

    async function save() {
      const patch = {};
      for (const k of changed()) patch[k] = draft[k];
      saveBtn.disabled = true;
      try {
        const r = await api('PATCH', '/api/servers/' + encodeURIComponent(id), patch);
        baseline = pick(r);
        Object.assign(draft, baseline);
        const i = state.servers.findIndex((s) => s.id === id);
        if (i >= 0) state.servers[i] = r;
        toast(r.restartRequired ? 'Saved. Restart the server to apply.' : 'Saved');
        refreshBar();
        if (view) view.update();
      } catch (e) { toast(e.message, 'error'); refreshBar(); }
    }

    function rebuild() {
      const text = (key, label, max, wide) => {
        const input = h('input', { class: 'input', value: draft[key], maxlength: String(max), 'aria-label': label, oninput: () => { set(key, input.value); fields[key] && showProblems({ [key]: fields[key] }); } });
        return fields[key] = field(label, input, wide);
      };
      const number = (key, label) => {
        const input = h('input', { class: 'input', type: 'number', inputmode: 'numeric', value: draft[key], 'aria-label': label,
          oninput: () => { set(key, input.value === '' ? NaN : Number(input.value)); showProblems(fields); } });
        return fields[key] = field(label, input);
      };
      const fields = {};
      const presetValue = () => PRESETS.find((p) => p.view === draft.viewDistance && p.sim === draft.simulationDistance) || null;
      const presetSeg = seg(PRESETS.map((p) => ({ value: p, label: p.label })), presetValue(), (p) => {
        set('viewDistance', p.view); set('simulationDistance', p.sim); viewSlider.set(p.view); simSlider.set(p.sim);
      });
      const viewSlider = slider(2, 24, 1, draft.viewDistance, (v) => v + ' chunks', (v) => { set('viewDistance', v); presetSeg.set(presetValue()); });
      const simSlider = slider(2, 16, 1, draft.simulationDistance, (v) => v + ' chunks', (v) => { set('simulationDistance', v); presetSeg.set(presetValue()); });

      const limitSlot = h('div', { class: 'field wide' });
      const buildLimit = () => {
        limitSlot.replaceChildren();
        if (draft.memoryLimitMb > 0) {
          const shown = Math.min(Math.max(draft.memoryLimitMb, 256), maxLimit);
          limitSlot.append(h('label', {}, 'Memory limit'), slider(256, maxLimit, 128, shown, fmtMb, (v) => set('memoryLimitMb', v)),
            h('div', { class: 'label' }, 'When the limit is exceeded'),
            seg([{ value: 'Warn', label: 'Warn only' }, { value: 'Restart', label: 'Restart server' }], draft.memoryAction, (v) => set('memoryAction', v)));
        }
      };
      buildLimit();

      // The Bedrock port only matters (and is only checked) while Bedrock is switched on.
      const bedrockSlot = h('div', { class: 'fields' });
      const buildBedrock = () => {
        bedrockSlot.replaceChildren();
        delete fields.bedrockPort;
        if (draft.bedrock) {
          bedrockSlot.append(number('bedrockPort', 'Bedrock port'),
            h('div', { class: 'hint wide' }, 'Bedrock players connect to this port. Give every server its own to run several at once.'));
          showProblems({ bedrockPort: fields.bedrockPort });
        }
      };
      buildBedrock();

      const engines = [{ value: null, label: 'Newest' }, ...state.engine.installed.map((e) => ({ value: e.id, label: e.label }))];
      const threads = [{ value: 0, label: 'Auto' }, ...Array.from({ length: dev.cores }, (_, i) => ({ value: i + 1, label: String(i + 1) }))];

      body.replaceChildren(
        h('div', { class: 'card' }, h('h2', {}, 'General'),
          h('div', { class: 'fields' }, text('name', 'Server name', 40), text('motd', 'Message of the day', 120), number('port', 'Port'), number('maxPlayers', 'Max players'))),
        h('div', { class: 'card' }, h('h2', {}, 'Gameplay'),
          h('div', { class: 'fields' },
            h('div', { class: 'field wide' }, h('div', { class: 'label' }, 'Game mode'), seg(GAME_MODES.map((m) => ({ value: m, label: m })), draft.gameMode, (v) => set('gameMode', v))),
            h('div', { class: 'field wide' }, h('div', { class: 'label' }, 'Difficulty'), seg(DIFFICULTIES.map((m) => ({ value: m, label: m })), draft.difficulty, (v) => set('difficulty', v))))),
        h('div', { class: 'card' }, h('h2', {}, 'Performance'),
          h('p', { class: 'hint' }, 'Joining sends every chunk in view distance, so fewer chunks means a much faster join.'),
          h('div', { class: 'fields' },
            h('div', { class: 'field wide' }, h('div', { class: 'label' }, 'Quick pick'), presetSeg),
            h('div', { class: 'field' }, h('label', {}, 'View distance'), viewSlider),
            h('div', { class: 'field' }, h('label', {}, 'Simulation distance'), simSlider))),
        h('div', { class: 'card' }, h('h2', {}, 'Resources'),
          h('p', { class: 'hint' }, 'This phone has ' + fmtMb(dev.totalMb) + ' of RAM and ' + dev.cores + ' CPU cores. Pumpkin has no heap setting, so Dumpkin+ watches its real RAM use.'),
          switchRow('Limit memory', 'Warn, or restart the server, when it uses more RAM than this.', draft.memoryLimitMb > 0,
            (on) => { set('memoryLimitMb', on ? Math.min(1024, maxLimit) : 0); buildLimit(); }),
          limitSlot,
          h('div', { class: 'field wide' }, h('div', { class: 'label' }, 'CPU threads'), seg(threads, draft.cpuThreads, (v) => set('cpuThreads', v))),
          switchRow('Restart after a crash', 'Tries again up to 3 times within 5 minutes.', draft.autoRestart, (v) => set('autoRestart', v))),
        h('div', { class: 'card' }, h('h2', {}, 'Network'),
          h('div', { class: 'field wide' }, h('div', { class: 'label' }, 'Packet compression'),
            seg(['Off', 'Fast', 'Balanced'].map((c) => ({ value: c, label: c })), draft.compression, (v) => set('compression', v)),
            h('div', { class: 'hint' }, 'Compressing chunks is the main CPU cost when someone joins. Fast is level 1; Balanced is Pumpkin\'s default.')),
          switchRow('Online mode', 'Verify players with Microsoft accounts. Each join waits on a Mojang lookup; offline mode skips it but lets anyone use any name.', draft.onlineMode, (v) => set('onlineMode', v)),
          switchRow('Fetch skins and capes', 'Turn off to trim work during joins. Players may show default skins.', draft.fetchSkins, (v) => set('fetchSkins', v)),
          switchRow('Lightweight server-list ping', 'Leaves the server icon out of the multiplayer list response so it loads faster.', draft.lightPing, (v) => set('lightPing', v)),
          switchRow('Bedrock support (experimental)', 'Lets Bedrock players join on their own port.', draft.bedrock, (v) => { set('bedrock', v); buildBedrock(); }),
          bedrockSlot),
        h('div', { class: 'card' }, h('h2', {}, 'Engine'),
          state.engine.installed.length
            ? seg(engines, draft.engineId, (v) => set('engineId', v))
            : h('p', { class: 'hint' }, 'No engine installed yet.'),
          h('div', { class: 'hint' }, 'Servers use the newest installed build unless you pick one.')),
        h('div', { class: 'card' }, h('h2', {}, 'Danger zone'),
          h('div', { class: 'danger-zone' }, h('button', { class: 'btn danger-soft', type: 'button', id: 'del', onclick: del }, icon('trash', 'sm'), 'Delete server and world'))));
      body._fields = fields;
      showProblems(fields);
    }

    async function del() {
      const s = serverById(id);
      if (s && isActive(s)) { toast('Stop the server first.', 'error'); return; }
      const ok = await confirmDialog({ title: 'Delete "' + (s ? s.name : 'server') + '"?',
        body: 'The server, its world and all of its files will be permanently removed from the phone. This cannot be undone.', confirm: 'Delete', danger: true });
      if (!ok) return;
      try {
        await api('DELETE', '/api/servers/' + encodeURIComponent(id));
        toast('Server deleted');
        go('#/');
        refreshNow();
      } catch (e) { toast(e.message, 'error'); }
    }

    rebuild();
    return {
      el,
      update(s) {
        const d = body.querySelector('#del');
        if (d) d.disabled = isActive(s);
      },
      destroy() {},
    };
  }

  // ---- config file
  function configPanel(server) {
    const id = server.id;
    const area = h('textarea', { class: 'input', spellcheck: 'false', 'aria-label': 'pumpkin.toml', placeholder: 'The file is created the first time the server starts.', disabled: true });
    const save = h('button', { class: 'btn', type: 'button', onclick: write }, 'Save file');
    const reset = h('button', { class: 'btn danger-soft', type: 'button', onclick: doReset }, 'Reset configuration');
    const el = h('div', { class: 'card' }, h('h2', {}, 'pumpkin.toml'),
      h('p', { class: 'hint' }, 'Advanced. Pumpkin ignores unknown keys, but a value of the wrong type resets the whole file to defaults. Changes apply after a restart.'),
      area, h('div', { class: 'danger-zone', style: undefined }, save, reset));
    el.querySelector('.danger-zone').style.marginTop = '14px';
    let loaded = false;

    api('GET', '/api/servers/' + encodeURIComponent(id) + '/config').then((r) => { area.value = r.text; area.disabled = false; loaded = true; })
      .catch((e) => toast(e.message, 'error'));

    async function write() {
      if (!loaded) return;
      save.disabled = true;
      try {
        const r = await api('PUT', '/api/servers/' + encodeURIComponent(id) + '/config', { text: area.value });
        area.value = r.text;
        toast(r.restartRequired ? 'Saved. Restart the server to apply.' : 'Saved');
        refreshNow();
      } catch (e) { toast(e.message, 'error'); } finally { save.disabled = false; }
    }
    async function doReset() {
      const ok = await confirmDialog({ title: 'Reset configuration?', body: 'pumpkin.toml is rewritten from the Settings tab. Your world is not touched.', confirm: 'Reset', danger: true });
      if (!ok) return;
      try {
        const r = await api('POST', '/api/servers/' + encodeURIComponent(id) + '/config/reset');
        area.value = r.text; toast('Configuration reset');
      } catch (e) { toast(e.message, 'error'); }
    }
    return {
      el,
      update(s) { reset.disabled = isActive(s); },
      destroy() {},
    };
  }

  // ------------------------------------------------------------------ engine dialog
  function openEngine() {
    if (engineDialog) return;
    api('POST', '/api/engine/refresh').catch(() => {});
    const body = h('div', { class: 'list' });
    const dlg = dialog((close) => h('div', { class: 'dlg' },
      h('h2', {}, 'Engine builds'),
      h('p', { class: 'hint' }, 'Pumpkin server builds for Android, downloaded from the official GitHub releases.'),
      body,
      h('div', { class: 'actions' },
        h('button', { class: 'btn outline', type: 'button', onclick: () => api('POST', '/api/engine/refresh').then(refreshNow).catch((e) => toast(e.message, 'error')) }, icon('refresh', 'sm'), 'Check for updates'),
        h('button', { class: 'btn', type: 'button', onclick: () => close(true) }, 'Done'))));
    dlg.addEventListener('close', () => { engineDialog = null; });
    dlg.addEventListener('cancel', () => { engineDialog = null; });
    engineDialog = { dlg, body };
    renderEngineDialog();
    refreshNow();
  }

  function renderEngineDialog() {
    if (!engineDialog || !state) return;
    const e = state.engine;
    const anyActive = state.servers.some(isActive);
    const rows = [];
    if (e.message) rows.push(h('div', { class: 'banner error' }, h('div', { class: 'grow' }, e.message)));
    rows.push(h('h3', { class: 'label' }, 'Installed'));
    if (!e.installed.length) rows.push(h('p', { class: 'hint' }, 'Nothing installed yet. Get a build below.'));
    for (const i of e.installed) {
      rows.push(h('div', { class: 'item' },
        h('div', { class: 'grow' }, h('div', { class: 't' }, i.label), h('div', { class: 'd' }, [i.channel, fmtBytes(i.sizeBytes)].filter(Boolean).join(' · '))),
        h('button', { class: 'icon-btn', 'aria-label': 'Delete ' + i.label, disabled: anyActive, title: anyActive ? 'Stop all servers first' : 'Delete', onclick: async () => {
          try { await api('DELETE', '/api/engine/' + encodeURIComponent(i.id)); refreshNow(); } catch (err) { toast(err.message, 'error'); }
        } }, icon('trash', 'sm'))));
    }
    rows.push(h('h3', { class: 'label' }, 'Available on GitHub' + (e.checking ? ' (checking…)' : '')));
    if (!e.available.length && !e.checking) rows.push(h('p', { class: 'hint' }, 'No builds found yet. Try “Check for updates”.'));
    for (const a of e.available) {
      const downloading = e.downloadingId === a.id;
      const bar = downloading ? h('div', { class: 'progress' }, h('i')) : null;
      if (bar) bar.firstChild.style.width = Math.round(e.progress * 100) + '%';
      rows.push(h('div', { class: 'item', style: undefined },
        h('div', { class: 'grow' },
          h('div', { class: 't' }, a.channel + ' · ' + a.tag),
          h('div', { class: 'd' }, [fmtBytes(a.sizeBytes), (a.updated || '').slice(0, 10), a.blurb].filter(Boolean).join(' · ')), bar),
        a.installed ? h('span', { class: 'chip' }, icon('check', 'sm'), 'Installed')
          : h('button', { class: 'btn small', disabled: !!e.downloadingId || !e.supported, onclick: async () => {
            try { await api('POST', '/api/engine/download', { id: a.id }); refreshNow(); } catch (err) { toast(err.message, 'error'); }
          } }, downloading ? 'Downloading…' : 'Get')));
    }
    engineDialog.body.replaceChildren(...rows);
  }

  // ------------------------------------------------------------------ go
  boot();
})();
