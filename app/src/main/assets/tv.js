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
    // Surbrillance légère : voile blanc discret + fin contour blanc.
    '[data-tvf]{outline:2px solid rgba(255,255,255,.9)!important;outline-offset:2px!important;',
    'box-shadow:inset 0 0 0 9999px rgba(255,255,255,.14),0 0 16px 2px rgba(255,255,255,.28)!important;',
    'transition:box-shadow .12s ease!important}',
    // Bouton « Image dans l'image » posé sur le lecteur.
    '#__tv_pip{position:fixed;z-index:2147483646;display:none;align-items:center;gap:8px;',
    'padding:8px 14px;border-radius:999px;border:1px solid rgba(255,255,255,.5);',
    'background:rgba(0,0,0,.68);color:#fff;cursor:pointer;margin:0;',
    'font:600 15px/1 system-ui,-apple-system,Roboto,"Segoe UI",sans-serif}',
    '#__tv_pip svg{display:block;flex:none}',
    // Pendant l'image dans l'image : seule la vidéo (ou l'image du podcast) reste affichée.
    'html[data-tv-pip],html[data-tv-pip] body{background:#000!important;overflow:hidden!important}',
    'html[data-tv-pip] body *{visibility:hidden!important}',
    'html[data-tv-pip] [data-tv-stage]{visibility:visible!important;position:fixed!important;',
    'left:0!important;top:0!important;right:auto!important;bottom:auto!important;',
    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;',
    'min-width:0!important;min-height:0!important;margin:0!important;padding:0!important;',
    'border:0!important;border-radius:0!important;z-index:2147483647!important;',
    'object-fit:contain!important;background:#000!important;transform:none!important;',
    'opacity:1!important;clip-path:none!important}',
    'html[data-tv-pip] [data-tv-stage-anc]{transform:none!important;filter:none!important;',
    'perspective:none!important;contain:none!important;will-change:auto!important;',
    'clip-path:none!important;overflow:visible!important;opacity:1!important}',
    '#__tv_cover{position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:#000;',
    'display:flex;align-items:center;justify-content:center}',
    '#__tv_cover img{max-width:100%;max-height:100%;object-fit:contain}',
    '#__tv_cover div{color:#fff;padding:8vh;text-align:center;',
    'font:600 7vh/1.3 system-ui,-apple-system,Roboto,"Segoe UI",sans-serif}',
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
  var pipSupported = false; // la Fire TV sait faire l'image dans l'image
  var pipActive = false;
  var tracked = [];         // médias créés par le site sans être affichés (lecteurs audio)
  var lastMedia = null;

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

  function pick(dir, from, fromEl, list, strict, sideWeight) {
    var sw = sideWeight === undefined ? 2.5 : sideWeight;
    var fcx = (from.left + from.right) / 2, fcy = (from.top + from.bottom) / 2;
    var best = null, bestS = Infinity;
    for (var i = 0; i < list.length; i++) {
      var c = list[i], r = c.r, el = c.el;
      if (el === fromEl || el.contains(fromEl)) continue; // ni soi-même ni un parent
      var cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
      // Bouton à l'intérieur d'une carte, ou posé par-dessus (commandes d'un lecteur vidéo) :
      // visité en premier avec gauche / droite (haut / bas passent à la rangée suivante).
      var inside = fromEl.contains(el) || (
        r.left >= from.left - 1 && r.right <= from.right + 1 &&
        r.top >= from.top - 1 && r.bottom <= from.bottom + 1 &&
        r.width * r.height < from.width * from.height * 0.6);
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
      var score = inside ? off * 0.05 : main + side * sw + off * 0.35 * (sw / 2.5);
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
        // Ici l'ordre de lecture compte plus que la colonne : premier élément visible en haut (ou en bas).
        var next = pick(dir, line, document.createElement('i'), free, true, 0.05);
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

  /* ---------- médias (vidéo et audio) ---------- */

  function visibleArea(el) {
    if (!connected(el)) return 0;
    var r = el.getBoundingClientRect();
    var w = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
    var h = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
    return Math.max(0, w) * Math.max(0, h);
  }

  function isPlaying(m) {
    return !m.paused && !m.ended;
  }

  function allMedia() {
    var list = Array.prototype.slice.call(document.querySelectorAll('video,audio'));
    for (var i = 0; i < tracked.length; i++) {
      if (list.indexOf(tracked[i]) < 0) list.push(tracked[i]);
    }
    return list;
  }

  // Média à piloter : celui qui joue (vidéo visible d'abord), sinon la plus grande vidéo à l'écran.
  function mainMedia() {
    var list = allMedia(), i, m, best = null, bestA = 0;
    for (i = 0; i < list.length; i++) {
      m = list[i];
      if (isPlaying(m) && m.tagName === 'VIDEO' && visibleArea(m) > 0) return m;
    }
    for (i = 0; i < list.length; i++) {
      if (isPlaying(list[i])) return list[i];
    }
    for (i = 0; i < list.length; i++) {
      m = list[i];
      if (m.tagName !== 'VIDEO') continue;
      var a = visibleArea(m);
      if (a > bestA) { bestA = a; best = m; }
    }
    return best || lastMedia;
  }

  // Média concerné par l'image dans l'image : vidéo bien visible, sinon média en lecture.
  function pipTarget() {
    var list = allMedia(), i, m, best = null, bestA = 120 * 68;
    for (i = 0; i < list.length; i++) {
      m = list[i];
      if (m.tagName !== 'VIDEO') continue;
      var a = visibleArea(m);
      if (a > bestA) { bestA = a; best = m; }
    }
    if (best) return best;
    for (i = 0; i < list.length; i++) {
      if (isPlaying(list[i])) return list[i];
    }
    return null;
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
    var v = mainMedia();
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

  /* ---------- image dans l'image ---------- */

  var pipBtn = null;

  function pipButton() {
    if (pipBtn && connected(pipBtn)) return pipBtn;
    pipBtn = document.createElement('button');
    pipBtn.id = '__tv_pip';
    pipBtn.type = 'button';
    pipBtn.setAttribute('aria-label', 'Image dans l’image');
    pipBtn.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">' +
      '<rect x="2.5" y="4.5" width="19" height="15" rx="2" fill="none" stroke="currentColor" stroke-width="1.8"/>' +
      '<rect x="12" y="11" width="8" height="6" rx="1" fill="currentColor"/></svg>' +
      '<span>Image dans l’image</span>';
    pipBtn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      pipRequest();
    }, true);
    (document.body || document.documentElement).appendChild(pipBtn);
    return pipBtn;
  }

  // Place le bouton en haut à droite de la vidéo visible, ou en bas à droite de l'écran
  // quand seul un son (podcast) est en lecture.
  function updatePipButton() {
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    var t = (pipSupported && !pipActive && !fs) ? pipTarget() : null;
    if (!t) {
      if (pipBtn && connected(pipBtn)) {
        if (cur === pipBtn) mark(null);
        pipBtn.style.display = 'none';
      }
      return;
    }
    addStyle();
    var b = pipButton();
    b.style.display = 'flex';
    var bw = b.offsetWidth || 190, bh = b.offsetHeight || 36;
    var x, y;
    if (t.tagName === 'VIDEO' && visibleArea(t) > 0) {
      var r = t.getBoundingClientRect();
      x = Math.min(r.right, window.innerWidth) - bw - 12;
      y = Math.max(r.top, 0) + 12;
    } else {
      x = window.innerWidth - bw - 24;
      y = window.innerHeight - bh - 24;
    }
    b.style.left = Math.max(8, Math.round(x)) + 'px';
    b.style.top = Math.max(8, Math.round(y)) + 'px';
  }

  function pipRequest() {
    try {
      if (window.CanapeTV && window.CanapeTV.enterPip) window.CanapeTV.enterPip();
    } catch (e) { /* ignoré */ }
  }

  function largestImage(root) {
    var imgs = root.querySelectorAll('img'), best = null, bestA = 0;
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i], r = im.getBoundingClientRect();
      if (r.width < 80 || r.height < 80 || im.id === '__tv_cover_img') continue;
      var a = visibleArea(im) || r.width * r.height * 0.01;
      if (a > bestA) { bestA = a; best = im; }
    }
    return best;
  }

  // Image qui illustre le son : pochette déclarée par le site, sinon la plus grande image
  // à côté du lecteur, sinon la plus grande image de la page, sinon l'image de partage.
  function coverFor(m) {
    try {
      var md = navigator.mediaSession && navigator.mediaSession.metadata;
      if (md && md.artwork && md.artwork.length) {
        var best = md.artwork[0], bestSize = 0;
        for (var i = 0; i < md.artwork.length; i++) {
          var s = parseInt(String(md.artwork[i].sizes || '0').split('x')[0], 10) || 0;
          if (s >= bestSize) { bestSize = s; best = md.artwork[i]; }
        }
        if (best && best.src) return best.src;
      }
    } catch (e) { /* ignoré */ }
    if (m && m.poster) return m.poster;
    var img = null;
    if (m && connected(m)) {
      for (var p = m.parentElement, d = 0; p && d < 6 && !img; p = p.parentElement, d++) {
        img = largestImage(p);
      }
    }
    if (!img) img = largestImage(document);
    if (img) return img.currentSrc || img.src;
    var og = document.querySelector('meta[property="og:image"],meta[name="twitter:image"]');
    return og && og.content ? og.content : null;
  }

  function pipStart() {
    var m = pipTarget() || mainMedia();
    if (!m) return { none: true };
    pipActive = true;
    if (pipBtn) pipBtn.style.display = 'none';
    addStyle();
    document.documentElement.setAttribute('data-tv-pip', '');
    if (m.tagName === 'VIDEO' && connected(m)) {
      m.setAttribute('data-tv-stage', '');
      for (var a = m.parentElement; a && a !== document.documentElement; a = a.parentElement) {
        a.setAttribute('data-tv-stage-anc', '');
      }
      return { kind: 'video' };
    }
    var c = document.getElementById('__tv_cover');
    if (c) c.parentNode.removeChild(c);
    c = document.createElement('div');
    c.id = '__tv_cover';
    var src = coverFor(m);
    if (src) {
      var im = document.createElement('img');
      im.id = '__tv_cover_img';
      im.alt = '';
      im.src = src;
      c.appendChild(im);
    } else {
      var t = document.createElement('div');
      t.textContent = document.title || 'Lecture en cours';
      c.appendChild(t);
    }
    document.documentElement.appendChild(c);
    return { kind: 'audio', cover: src || null };
  }

  function pipEnd() {
    pipActive = false;
    document.documentElement.removeAttribute('data-tv-pip');
    var list = document.querySelectorAll('[data-tv-stage],[data-tv-stage-anc]');
    for (var i = 0; i < list.length; i++) {
      list[i].removeAttribute('data-tv-stage');
      list[i].removeAttribute('data-tv-stage-anc');
    }
    var c = document.getElementById('__tv_cover');
    if (c) c.parentNode.removeChild(c);
    updatePipButton();
    return null;
  }

  function pipCheck() {
    return { media: !!(pipTarget() || mainMedia()) };
  }

  /* ---------- onglets du site (raccourcis du menu) ---------- */

  function norm(s) {
    s = String(s || '').toLowerCase();
    if (s.normalize) s = s.normalize('NFD').replace(/[̀-ͯ]/g, '');
    return s.replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
  }

  // Cherche dans le site le lien ou bouton portant un de ces noms, et clique dessus.
  function openTab(names) {
    var els = document.querySelectorAll('a[href],button,[role="link"],[role="tab"],[role="menuitem"]');
    var best = null, bestScore = 0;
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.id === '__tv_pip') continue;
      var label = norm(el.textContent);
      var aria = norm(el.getAttribute('aria-label') || el.getAttribute('title'));
      if (label.length > 40) label = '';
      for (var k = 0; k < names.length; k++) {
        var n = norm(names[k]);
        var sc = (label === n || aria === n) ? 3 : ((label.indexOf(n) === 0 || aria.indexOf(n) === 0) ? 2 : 0);
        if (sc && usable(el, el.getBoundingClientRect())) sc += 1; // visible à l'écran : préféré
        if (sc > bestScore) { bestScore = sc; best = el; }
      }
    }
    if (!best) return { found: false };
    var href = best.tagName === 'A' ? best.href : '';
    safeClick(best);
    return { found: true, href: href };
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

  function config(z, pointer, pip) {
    var n = parseInt(z, 10);
    zoom = (n >= 30 && n <= 300) ? n : 100;
    pointerMode = !!pointer;
    pipSupported = !!pip;
    if (pointerMode) mark(null);
    applyZoom();
    updatePipButton();
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

  // Prévient l'appli quand un son ou une vidéo joue (écran allumé, télécommande, image dans l'image).
  var MEDIA_EVENTS = ['play', 'playing', 'pause', 'ended', 'emptied'];
  function reportPlaying() {
    var any = false, list = allMedia();
    for (var i = 0; i < list.length; i++) {
      if (isPlaying(list[i])) { any = true; break; }
    }
    try { if (window.CanapeTV) window.CanapeTV.setPlaying(any); } catch (e) { /* ignoré */ }
    updatePipButton();
  }
  MEDIA_EVENTS.forEach(function (t) {
    document.addEventListener(t, reportPlaying, true);
  });

  // Les lecteurs audio créés en JavaScript ne sont pas dans la page : on les repère
  // au moment où le site lance la lecture.
  function track(m) {
    lastMedia = m;
    if (tracked.indexOf(m) >= 0) return;
    tracked.push(m);
    if (tracked.length > 8) tracked.shift();
    MEDIA_EVENTS.forEach(function (t) { m.addEventListener(t, reportPlaying); });
  }
  try {
    var proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
    if (proto && !proto.__tvPatched) {
      var originalPlay = proto.play;
      proto.play = function () {
        try { track(this); } catch (e) { /* ignoré */ }
        return originalPlay.apply(this, arguments);
      };
      proto.__tvPatched = true;
    }
  } catch (e) { /* ignoré */ }

  // Suit la position de la vidéo (défilement, changement de page) pour le bouton.
  setInterval(updatePipButton, 600);

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
    clearFocus: clearFocus,
    pipStart: pipStart,
    pipEnd: pipEnd,
    pipCheck: pipCheck,
    openTab: openTab,
    cover: coverFor
  };

  addStyle();
  applyZoom();
})();
