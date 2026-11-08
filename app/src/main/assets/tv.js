/*
 * Canapé : couche « TV » injectée dans la page web.
 * Rend un site pensé pour la souris utilisable avec la télécommande :
 * déplacement aux flèches d'un élément cliquable au suivant, surbrillance,
 * défilement automatique, fenêtres (modales), touches vidéo, taille d'affichage.
 * Écrit en ES5 pour fonctionner aussi sur les anciennes Fire TV.
 */
(function () {
  'use strict';
  if (window.__tv) return;

  var SELECTOR = [
    'a[href]', 'button', 'input:not([type="hidden"])', 'select', 'textarea',
    'summary', 'video', 'iframe',
    '[tabindex]:not([tabindex="-1"])', '[contenteditable="true"]',
    '[role="button"]', '[role="link"]', '[role="tab"]', '[role="menuitem"]',
    '[role="menuitemradio"]', '[role="menuitemcheckbox"]', '[role="option"]',
    '[role="checkbox"]', '[role="radio"]', '[role="switch"]', '[role="slider"]',
    '[onclick]', '[class*="cursor-pointer"]'
  ].join(',');

  // Couches ouvertes que la touche Retour doit fermer en priorité.
  var OPEN_LAYER = [
    '[aria-modal="true"]', 'dialog[open]',
    '[role="dialog"][data-state="open"]', '[role="alertdialog"]',
    '[role="menu"][data-state="open"]', '[role="listbox"][data-state="open"]',
    '[data-radix-popper-content-wrapper]'
  ].join(',');

  var STYLE = [
    '[data-tvf]{outline:4px solid #fff!important;outline-offset:3px!important;',
    'box-shadow:0 0 0 7px rgba(0,0,0,.55),0 0 30px 8px rgba(255,255,255,.4)!important;',
    'transition:outline-offset .12s ease,box-shadow .12s ease!important}',
    'html{-webkit-tap-highlight-color:transparent}',
    '::-webkit-scrollbar{width:0!important;height:0!important;background:transparent!important}',
    '#__tv_osd{position:fixed;left:50%;bottom:8%;transform:translateX(-50%);',
    'z-index:2147483647;padding:12px 26px;border-radius:999px;',
    'background:rgba(0,0,0,.75);color:#fff;pointer-events:none;',
    'font:600 22px/1.2 system-ui,-apple-system,Roboto,"Segoe UI",sans-serif;',
    'opacity:0;transition:opacity .25s ease}',
    '#__tv_osd.on{opacity:1}'
  ].join('');

  var MODAL = '[aria-modal="true"],dialog[open]';

  var cur = null;          // élément en surbrillance
  var outside = null;      // dernier élément choisi hors fenêtre, retrouvé à sa fermeture
  var zoom = 100;          // taille d'affichage en %
  var pointerMode = false; // mode curseur actif côté appli
  var osdTimer = 0;

  /* ---------- outils ---------- */

  function addStyle() {
    if (document.getElementById('__tv_style')) return;
    var s = document.createElement('style');
    s.id = '__tv_style';
    s.textContent = STYLE;
    (document.head || document.documentElement).appendChild(s);
  }

  function connected(el) {
    return !!el && el.nodeType === 1 && document.documentElement.contains(el);
  }

  function vp() {
    var v = window.visualViewport;
    if (v) return { x: v.offsetLeft, y: v.offsetTop, w: v.width, h: v.height };
    return { x: 0, y: 0, w: window.innerWidth, h: window.innerHeight };
  }

  function inView(r) {
    return r.bottom > 0 && r.top < window.innerHeight &&
           r.right > 0 && r.left < window.innerWidth;
  }

  function usable(el, r) {
    if (r.width < 4 || r.height < 4) return false;
    if (el.disabled) return false;
    if (el.getAttribute('aria-disabled') === 'true') return false;
    if (el.closest('[aria-hidden="true"],[inert]')) return false;
    var cs = window.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.pointerEvents === 'none' || cs.opacity === '0') return false;
    return true;
  }

  // Élément dans un bandeau fixé à l'écran (en-tête qui reste en haut, barre du bas…).
  function pinned(el) {
    for (var e = el; e && e.nodeType === 1 && e !== document.body; e = e.parentElement) {
      var p = window.getComputedStyle(e).position;
      if (p === 'fixed' || p === 'sticky') return true;
    }
    return false;
  }

  function fire(el, type) {
    try {
      var bubbles = type !== 'mouseenter' && type !== 'mouseleave';
      el.dispatchEvent(new MouseEvent(type, { bubbles: bubbles, cancelable: true, view: window }));
    } catch (e) { /* ignoré */ }
  }

  function firePointer(el, type) {
    try {
      el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, view: window, pointerType: 'mouse', isPrimary: true, button: 0 }));
    } catch (e) { fire(el, type.replace('pointer', 'mouse')); }
  }

  function safeClick(el) {
    try { el.focus({ preventScroll: true }); } catch (e) { /* ignoré */ }
    if (typeof el.click === 'function') el.click(); else fire(el, 'click');
  }

  /* ---------- zone de navigation ---------- */

  // Plein écran > fenêtre modale ouverte > page entière.
  function scope() {
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs) return fs;
    var list = document.querySelectorAll(MODAL);
    for (var i = list.length - 1; i >= 0; i--) {
      var r = list[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && !list[i].closest('[aria-hidden="true"]')) return list[i];
    }
    return document;
  }

  function candidates(root) {
    var nodes = root.querySelectorAll(SELECTOR), out = [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i], r = el.getBoundingClientRect();
      if (usable(el, r)) out.push({ el: el, r: r });
    }
    if (!out.length && root !== document && root.matches && root.matches(SELECTOR)) {
      out.push({ el: root, r: root.getBoundingClientRect() });
    }
    return out;
  }

  function valid(el, root) {
    if (!connected(el)) return false;
    if (root !== document && !root.contains(el)) return false;
    var r = el.getBoundingClientRect();
    return usable(el, r) && inView(r);
  }

  function initial(list, root) {
    var i, r;
    if (root === document && outside && outside !== cur && connected(outside)) {
      r = outside.getBoundingClientRect();
      if (inView(r) && usable(outside, r)) return outside;
    }
    var a = document.activeElement;
    if (a && a !== document.body && a.matches && a.matches(SELECTOR) &&
        (root === document || root.contains(a))) {
      r = a.getBoundingClientRect();
      if (inView(r) && usable(a, r)) return a;
    }
    // Premier élément visible dans l'ordre de lecture, en évitant les bandeaux fixes
    // (sauf s'il n'y a qu'eux à l'écran).
    var best = null, bestS = Infinity, bestPinned = null, bestPinnedS = Infinity;
    for (i = 0; i < list.length; i++) {
      r = list[i].r;
      if (!inView(r)) continue;
      var sc = Math.max(r.top, 0) * 3 + Math.max(r.left, 0);
      if (root === document && pinned(list[i].el)) {
        if (sc < bestPinnedS) { bestPinnedS = sc; bestPinned = list[i].el; }
      } else if (sc < bestS) { bestS = sc; best = list[i].el; }
    }
    return best || bestPinned;
  }

  /* ---------- choix de l'élément voisin ---------- */

  function gap(a1, a2, b1, b2) {
    if (b2 < a1) return a1 - b2;
    if (b1 > a2) return b1 - a2;
    return 0;
  }

  function pick(dir, from, fromEl, list, strict) {
    var fcx = (from.left + from.right) / 2, fcy = (from.top + from.bottom) / 2;
    var best = null, bestS = Infinity;
    for (var i = 0; i < list.length; i++) {
      var c = list[i], r = c.r, el = c.el;
      if (el === fromEl || el.contains(fromEl)) continue; // ni soi-même ni un parent
      var cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
      // Bouton à l'intérieur d'une carte : visité en premier avec gauche / droite
      // (haut / bas passent directement à la rangée suivante).
      var inside = fromEl.contains(el);
      if (inside && (dir === 'up' || dir === 'down') && strict) continue;
      var ok, main, side, off;
      if (dir === 'right' || dir === 'left') {
        var sgn = dir === 'right' ? 1 : -1;
        if (inside) {
          ok = (cx - fcx) * sgn > from.width * 0.2; main = 0;
        } else if (strict) {
          ok = sgn > 0 ? r.left >= from.right - 2 : r.right <= from.left + 2;
          main = sgn > 0 ? r.left - from.right : from.left - r.right;
        } else {
          ok = (cx - fcx) * sgn > 2;
          main = sgn > 0 ? r.left - from.right : from.left - r.right;
        }
        side = inside ? 0 : gap(from.top, from.bottom, r.top, r.bottom);
        off = Math.abs(cy - fcy);
      } else {
        var sg = dir === 'down' ? 1 : -1;
        if (inside) {
          ok = (cy - fcy) * sg > from.height * 0.2; main = 0;
        } else if (strict) {
          ok = sg > 0 ? r.top >= from.bottom - 2 : r.bottom <= from.top + 2;
          main = sg > 0 ? r.top - from.bottom : from.top - r.bottom;
        } else {
          ok = (cy - fcy) * sg > 2;
          main = sg > 0 ? r.top - from.bottom : from.top - r.bottom;
        }
        side = inside ? 0 : gap(from.left, from.right, r.left, r.right);
        off = Math.abs(cx - fcx);
      }
      if (!ok) continue;
      if (main < 0) main = 0;
      var score = inside ? off * 0.05 : main + side * 2.5 + off * 0.35;
      if (score < bestS) { bestS = score; best = c; }
    }
    return best;
  }

  /* ---------- surbrillance et défilement ---------- */

  function reveal(el, smooth) {
    var r = el.getBoundingClientRect(), h = window.innerHeight, m = h * 0.15;
    var block = (r.top < m || r.bottom > h - m) ? 'center' : 'nearest';
    try {
      el.scrollIntoView({ block: block, inline: 'nearest', behavior: smooth ? 'smooth' : 'auto' });
    } catch (e) { el.scrollIntoView(false); }
  }

  function mark(el) {
    if (cur && cur !== el) {
      cur.removeAttribute('data-tvf');
      fire(cur, 'mouseout');
    }
    cur = el;
    if (el) {
      el.setAttribute('data-tvf', '');
      if (!el.closest(MODAL)) outside = el;
    }
  }

  function focusEl(el) {
    addStyle();
    mark(el);
    fire(el, 'mouseover');
    try { el.focus({ preventScroll: true }); } catch (e) { /* ignoré */ }
    if (!pinned(el)) reveal(el, true); // un en-tête fixe est déjà visible : ne pas remonter la page
  }

  function scroller(el, axis) {
    while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
      var cs = window.getComputedStyle(el);
      var ov = axis === 'y' ? cs.overflowY : cs.overflowX;
      var room = axis === 'y' ? el.scrollHeight > el.clientHeight + 2 : el.scrollWidth > el.clientWidth + 2;
      if ((ov === 'auto' || ov === 'scroll' || ov === 'overlay') && room) return el;
      el = el.parentElement;
    }
    return null;
  }

  function pageScrollable() {
    var se = document.scrollingElement || document.documentElement;
    return se.scrollHeight > se.clientHeight + 2;
  }

  function scrollStep(dir) {
    var vertical = dir === 'up' || dir === 'down';
    var sign = (dir === 'down' || dir === 'right') ? 1 : -1;
    var axis = vertical ? 'y' : 'x';
    var root = scope();
    var base = connected(cur) ? cur : document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
    var s = scroller(base, axis);
    if (root !== document) {
      // Dans une fenêtre ou en plein écran : on ne fait défiler que son contenu.
      if (s && !(s === root || root.contains(s))) s = null;
      if (!s && scroller(root, axis) === root) s = root;
      if (!s) return;
    } else if (!s && vertical && !pageScrollable()) {
      s = scroller(document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2), axis);
    }
    var amount = (vertical ? window.innerHeight : window.innerWidth) * 0.6 * sign;
    var opts = { left: vertical ? 0 : amount, top: vertical ? amount : 0, behavior: 'smooth' };
    if (s) s.scrollBy(opts);
    else if (vertical) window.scrollBy(opts);
  }

  /* ---------- actions appelées par l'appli ---------- */

  function move(dir) {
    addStyle();
    var root = scope();
    var list = candidates(root);
    var h = window.innerHeight;
    if (!valid(cur, root)) {
      var vertical = dir === 'up' || dir === 'down';
      if (vertical && connected(cur) && (root === document || root.contains(cur))) {
        // L'élément choisi a quitté l'écran (la page a défilé) : on repart du bord de l'écran,
        // dans la même colonne, sans revenir en arrière.
        var cr = cur.getBoundingClientRect();
        var edge = dir === 'down' ? -1 : h;
        var line = { top: edge, bottom: edge + 1, left: cr.left, right: cr.right, width: cr.width, height: 1 };
        var free = [];
        for (var k = 0; k < list.length; k++) {
          if (root !== document || !pinned(list[k].el)) free.push(list[k]);
        }
        var next = pick(dir, line, document.createElement('i'), free, true);
        if (next && inView(next.r)) focusEl(next.el); else scrollStep(dir);
        return null;
      }
      var start = initial(list, root);
      if (start) focusEl(start); else scrollStep(dir);
      return null;
    }
    var from = cur.getBoundingClientRect();
    var c = pick(dir, from, cur, list, true) || pick(dir, from, cur, list, false);
    if (!c) { scrollStep(dir); return null; }
    // Cible très loin (beaucoup de texte entre les deux) : on fait d'abord défiler.
    if ((dir === 'down' && c.r.top > h * 1.25) || (dir === 'up' && c.r.bottom < -h * 0.25)) {
      scrollStep(dir);
      return null;
    }
    focusEl(c.el);
    return null;
  }

  function activate() {
    var root = scope();
    if (!valid(cur, root)) {
      if (connected(cur) && (root === document || root.contains(cur))) {
        reveal(cur, false); // élément sorti de l'écran : on le ramène d'abord
      } else {
        var s = initial(candidates(root), root);
        if (s) focusEl(s);
        return null;
      }
    }
    var el = cur;
    if (el.tagName === 'VIDEO') { toggle(el); return null; }
    var r = el.getBoundingClientRect();
    var x1 = Math.max(r.left, 0), x2 = Math.min(r.right, window.innerWidth);
    var y1 = Math.max(r.top, 0), y2 = Math.min(r.bottom, window.innerHeight);
    if (x2 - x1 < 2 || y2 - y1 < 2) { safeClick(el); return null; }
    var x = (x1 + x2) / 2, y = (y1 + y2) / 2;
    var hit = document.elementFromPoint(x, y);
    if (hit && hit !== el && !el.contains(hit)) {
      // Quelque chose recouvre l'élément : clic direct plutôt qu'un appui à l'écran.
      safeClick(el);
      return null;
    }
    var v = vp();
    return { tap: true, x: (x - v.x) / v.w, y: (y - v.y) / v.h };
  }

  function openLayer() {
    var list = document.querySelectorAll(OPEN_LAYER);
    for (var i = list.length - 1; i >= 0; i--) {
      var r = list[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0) return list[i];
    }
    return null;
  }

  function back() {
    if (openLayer()) return { state: 'modal' };
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs) {
      try { (document.exitFullscreen || document.webkitExitFullscreen).call(document); } catch (e) { /* ignoré */ }
      return { state: 'fullscreen' };
    }
    return { state: 'none' };
  }

  function closeModal() {
    var m = openLayer();
    if (!m) return { ok: true };
    var btn = m.querySelector('[aria-label*="close" i],[aria-label*="fermer" i],[data-dismiss],[data-close],button.close');
    if (btn) { safeClick(btn); return { ok: true }; }
    // Sinon : « clic » en dehors de la fenêtre, ce qui la ferme sur la plupart des sites.
    var t = document.elementFromPoint(4, 4) || document.body;
    if (m.contains(t)) t = document.body;
    firePointer(t, 'pointerdown'); fire(t, 'mousedown');
    firePointer(t, 'pointerup'); fire(t, 'mouseup'); fire(t, 'click');
    return { ok: false };
  }

  function mainVideo() {
    var vids = document.querySelectorAll('video'), best = null, bestA = 0;
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!v.paused && !v.ended) return v;
      var r = v.getBoundingClientRect();
      var w = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
      var h = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
      var a = Math.max(0, w) * Math.max(0, h);
      if (a > bestA) { bestA = a; best = v; }
    }
    return best;
  }

  function toggle(v) {
    if (v.paused || v.ended) {
      var p = v.play();
      if (p && p['catch']) p['catch'](function () {});
      osd('Lecture');
    } else {
      v.pause();
      osd('Pause');
    }
  }

  function media(action) {
    var v = mainVideo();
    if (!v) return { none: true, iframe: !!(connected(cur) && cur.tagName === 'IFRAME') };
    try {
      if (action === 'toggle') toggle(v);
      else if (action === 'play') { if (v.paused || v.ended) toggle(v); }
      else if (action === 'pause') { if (!v.paused) toggle(v); }
      else if (action === 'ff' || action === 'rw') {
        var t = v.currentTime + (action === 'ff' ? 10 : -10);
        if (isFinite(v.duration)) t = Math.min(t, v.duration - 0.5);
        v.currentTime = Math.max(0, t);
        osd(action === 'ff' ? '+10 s' : '−10 s');
      }
    } catch (e) { /* vidéo en direct : pas d'avance rapide */ }
    return null;
  }

  function osd(text) {
    addStyle();
    var o = document.getElementById('__tv_osd');
    if (!o) { o = document.createElement('div'); o.id = '__tv_osd'; }
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    var host = (fs && fs.tagName !== 'VIDEO') ? fs : document.body;
    if (!host) return;
    if (o.parentNode !== host) host.appendChild(o);
    o.textContent = text;
    o.className = 'on';
    clearTimeout(osdTimer);
    osdTimer = setTimeout(function () { o.className = ''; }, 900);
  }

  // Mode curseur : défilement quand le curseur bute contre un bord de l'écran.
  function scrollAt(fx, fy, dxf, dyf) {
    var v = vp();
    var x = Math.min(Math.max(v.x + fx * v.w, 1), window.innerWidth - 1);
    var y = Math.min(Math.max(v.y + fy * v.h, 1), window.innerHeight - 1);
    var axis = dyf !== 0 ? 'y' : 'x';
    var s = scroller(document.elementFromPoint(x, y), axis);
    if (!s && !pageScrollable()) {
      s = scroller(document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2), axis);
    }
    var dx = dxf * v.w, dy = dyf * v.h;
    if (s) s.scrollBy(dx, dy); else window.scrollBy(dx, dy);
    return null;
  }

  /* ---------- taille d'affichage ---------- */

  function applyZoom() {
    var head = document.head;
    if (!head) return;
    var meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'viewport');
      meta.setAttribute('data-tv-orig', 'width=device-width, initial-scale=1');
      head.appendChild(meta);
    }
    if (!meta.hasAttribute('data-tv-orig')) {
      meta.setAttribute('data-tv-orig', meta.getAttribute('content') || 'width=device-width, initial-scale=1');
    }
    var want;
    if (zoom === 100) {
      want = meta.getAttribute('data-tv-orig');
    } else {
      var s = zoom / 100;
      var w = Math.round((window.screen.width || 960) / s);
      want = 'width=' + w + ', initial-scale=' + s + ', minimum-scale=' + s +
             ', maximum-scale=' + s + ', user-scalable=no';
    }
    if (meta.getAttribute('content') !== want) meta.setAttribute('content', want);
  }

  function config(z, pointer) {
    var n = parseInt(z, 10);
    zoom = (n >= 30 && n <= 300) ? n : 100;
    pointerMode = !!pointer;
    if (pointerMode) mark(null);
    applyZoom();
    return null;
  }

  function clearFocus() { mark(null); return null; }

  /* ---------- écouteurs ---------- */

  // Suit le focus donné par le site lui-même (fenêtres qui s'ouvrent, etc.).
  document.addEventListener('focusin', function (e) {
    var t = e.target;
    if (pointerMode || !t || t === cur || !t.matches || !t.matches(SELECTOR)) return;
    addStyle();
    mark(t);
  }, true);

  // Garde l'écran allumé pendant la lecture d'une vidéo.
  function reportPlaying() {
    var any = false, list = document.querySelectorAll('video,audio');
    for (var i = 0; i < list.length; i++) {
      if (!list[i].paused && !list[i].ended) { any = true; break; }
    }
    try { if (window.CanapeTV) window.CanapeTV.setPlaying(any); } catch (e) { /* ignoré */ }
  }
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (t) {
    document.addEventListener(t, reportPlaying, true);
  });

  // Si le site réécrit sa balise « viewport », on réapplique la taille choisie.
  try {
    new MutationObserver(function () { applyZoom(); }).observe(
      document.head || document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] }
    );
  } catch (e) { /* ignoré */ }

  window.__tv = {
    version: 1,
    move: move,
    activate: activate,
    back: back,
    closeModal: closeModal,
    media: media,
    scrollAt: scrollAt,
    config: config,
    clearFocus: clearFocus
  };

  addStyle();
  applyZoom();
})();
