(() => {
  if (window.__IH_CB) { window.__IH_CB.scan(); return; }

  const ON_DDG = /(^|\.)duckduckgo\.com$/.test(location.hostname);
  const ON_GOOGLE = /(^|\.)google\.[a-z.]{2,6}$/.test(location.hostname);
  const ON_BRAVE = /(^|\.)brave\.com$/.test(location.hostname);
  const ON_SOGOU = /(^|\.)sogou\.com$/.test(location.hostname);
  const ITEM_SEL = [
    '.serp-item', '[class*="serp-item"]', '[class*="SerpItem"]',
    '[class*="SerpList-Item"]',
    '.tile--img', '[class*="tile--img"]',
    '.isv-r', '[data-rid]', '[data-lpage]',
    '.imgpt', '[class*="imgpt"]', '.iusc',
    '[class*="ImageCard"]', '[class*="imageCard"]', '[class*="photo-card"]',
    // DOM DuckDuckGo 2025+: плитка — LI > FIGURE c хешированными классами,
    // селектор структурный, только на доменах DDG (чтобы не зацепить чужие)
    ...(ON_DDG ? ['ol > li > figure', 'figure', 'article'] : []),
    // плитка выдачи картинок Google (udm=2); хешированные классы Google
    // меняются от релиза к релизу — при несовпадении срабатывает fallback
    ...(ON_GOOGLE ? ['.eA0Zlc'] : []),
    // плитка выдачи Brave: BUTTON.image-result с прокси-миниатюрой внутри
    ...(ON_BRAVE ? ['button.image-result'] : []),
    // плитка выдачи Sogou: DIV.img-layout > A.img-height (drag-img = картинка)
    ...(ON_SOGOU ? ['[class*="img-layout"]', '[class*="img-height"]'] : [])
  ].join(',');

  const IMAGE_RE = /\.(jpe?g|png|gif|webp|bmp|avif|jfif|tiff?)([?#]|$)/i;

  // Миниатюры части поисковиков не имеют расширения в URL — без этих
  // шаблонов looksImage отбрасывал бы плитки целиком (чекбоксы «не у всех»).
  const looksImage = (u) =>
    IMAGE_RE.test(u) ||
    /avatars\.mds\.yandex\.net/.test(u) ||
    /\/orig(\/|$|\?)/i.test(u) ||
    /\/iu\//.test(u) ||
    /encrypted-tbn\d*\.gstatic\.com/.test(u) ||   // миниатюры Google (udm=2)
    /\.googleusercontent\.com/.test(u) ||          // прокси-миниатюры Google
    /\.mm\.bing\.net\/th/.test(u) ||               // кеш Bing (и миниатюры Yahoo)
    /\/it\/u=/.test(u) ||                          // миниатюры Baidu (img*.baidu.com/it/u=…)
    /pstatic\.net/.test(u) ||                      // миниатюры Naver (search.pstatic.net)
    /imgs\.search\.brave\.com\//.test(u) ||        // прокси-миниатюры Brave (оригинал в base64 в пути)
    /sogoucdn\.com/.test(u) ||                     // миниатюры Sogou (img*.sogoucdn.com)
    /pic\.sogou\.com/.test(u);                     // миниатюры/детальные ссылки Sogou

  // --- data-state (новый UI Яндекса): JSON всего SERP'а в атрибуте Root'а ---
  const STATE = new Map(); // url или origin+pathname -> origUrl
  const normKey = (u) => {
    try { const x = new URL(u, location.href); return x.origin + x.pathname; }
    catch (e) { return u; }
  };
  let lastStateSrc = '';
  const buildState = () => {
    const els = [...document.querySelectorAll('[data-state]')];
    const src = els.map((e) => e.getAttribute('data-state')).join('|').length
      + ':' + (els[0] ? els[0].getAttribute('data-state').slice(0, 200) : '');
    if (src === lastStateSrc && STATE.size) return;
    lastStateSrc = src;
    STATE.clear();
    els.forEach((el) => {
      let data;
      try { data = JSON.parse(el.getAttribute('data-state')); } catch (e) { return; }
      const gather = (o, orig) => {
        if (typeof o === 'string') {
          if (/^https?:\/\//.test(o)) { STATE.set(o, orig); STATE.set(normKey(o), orig); }
        } else if (Array.isArray(o)) o.forEach((v) => gather(v, orig));
        else if (o && typeof o === 'object') Object.values(o).forEach((v) => gather(v, orig));
      };
      const visit = (obj) => {
        if (!obj || typeof obj !== 'object') return;
        if (Array.isArray(obj)) { obj.forEach(visit); return; }
        if (typeof obj.origUrl === 'string' && /^https?:/.test(obj.origUrl)) {
          gather(obj, obj.origUrl); // все url этого элемента выдачи ведут к его оригиналу
        } else {
          Object.values(obj).forEach(visit);
        }
      };
      visit(data);
    });
  };

  // Кандидаты-«оригиналы» внутри одного элемента выдачи.
  // Возвращает записи {u: url, s: score}, отсортированные по убыванию
  // «качества» (оригиналы — выше, запасные миниатюры — ниже); оценка нужна
  // процедуре подъёма хоста (см. scan).
  const collectCandidateRecs = (root) => {
    const out = [];
    const seen = new Map();
    // root и его потомки: querySelectorAll не видит атрибуты самого root
    const selfAndDesc = (sel) => {
      const res = [];
      if (root.matches && root.matches(sel)) res.push(root);
      res.push(...root.querySelectorAll(sel));
      return res;
    };
    const add = (raw, score, force) => {
      try {
        if (!raw || typeof raw !== 'string' || raw.startsWith('data:')) return;
        const abs = new URL(raw, location.href);
        if (abs.protocol !== 'http:' && abs.protocol !== 'https:') return;
        // Прокси DuckDuckGo: раскрываем оригинал из параметра u=.
        // Принимаем его всегда (force): у выдачи DDG оригинал часто без
        // расширения (кеш tse*.mm.bing.net/th/id/…), но это заведомо картинка.
        if (abs.hostname.endsWith('duckduckgo.com') && abs.pathname.startsWith('/iu')) {
          const orig = abs.searchParams.get('u');
          if (orig) { add(orig, Math.max(score, 85), true); return; }
        }
        // Прокси Brave: /<подпись>/rs:fit:…/g:ce/<base64 оригинала> —
        // настоящий URL зашит в путь, расширения нет. Иконки фавиконов
        // (payload ведёт на favicons.search.brave.com) выбрасываем целиком.
        if (abs.hostname === 'imgs.search.brave.com') {
          const pm = abs.pathname.match(/\/g:[a-z]+\/(.+)$/);
          if (pm) {
            try {
              let b64 = pm[1].replace(/\/+/g, '').replace(/-/g, '+').replace(/_/g, '/');
              b64 += '='.repeat((4 - b64.length % 4) % 4);
              const bin = atob(b64);
              const bytes = new Uint8Array(bin.length);
              for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
              const orig = new TextDecoder('utf-8').decode(bytes);
              if (/^https?:\/\//i.test(orig)) {
                if (new URL(orig).hostname.endsWith('favicons.search.brave.com')) return;
                add(orig, Math.max(score, 85), true);
                return;
              }
            } catch (e) {}
          }
        }
        if (!force && !looksImage(abs.href)) return;
        if (seen.has(abs.href)) {
          const prev = seen.get(abs.href);
          if (score > prev.s) prev.s = score;
          return;
        }
        const rec = { u: abs.href, s: score };
        seen.set(abs.href, rec);
        out.push(rec);
      } catch (e) {}
    };

    // 1. Яндекс: JSON в data-bem (origUrl — большой файл)
    selfAndDesc('[data-bem]').forEach((el) => {
      let data;
      try { data = JSON.parse(el.getAttribute('data-bem')); } catch (e) { return; }
      const walk = (obj, key) => {
        if (typeof obj === 'string') {
          let sc = 0;
          if (key && /orig/i.test(key)) sc = 100;
          else if (key && /(preview|thumb|small)/i.test(key)) sc = 15;
          else if (key) sc = 60;
          add(obj, sc);
        } else if (Array.isArray(obj)) {
          obj.forEach((v) => walk(v, key));
        } else if (obj && typeof obj === 'object') {
          Object.entries(obj).forEach(([k, v]) => walk(v, k));
        }
      };
      walk(data, '');
    });

    // 1b. Sogou: настоящая картинка плитки — в атрибутах drag-img / data-img
    // (img рендерится директивой v-lazy и может ещё не иметь src).
    // Без гейта по хосту: на мок-страницах hostname пуст, а drag-img вне
    // Sogou почти не встречается; data-img принимаем только «похожим на
    // картинку», чтобы не тащить мусор с чужих сайтов.
    selfAndDesc('[drag-img], [data-img]').forEach((el) => {
      ['drag-img', 'data-img'].forEach((k) => {
        const v = el.getAttribute(k);
        if (v && /^https?:/i.test(v)) add(v, 75, k === 'drag-img');
      });
    });

    // 2. Ссылки-плитки: оригиналы в параметрах (Яндекс, Google, DuckDuckGo…)
    selfAndDesc('a[href]').forEach((a) => {
      try {
        const abs = new URL(a.href, location.href);
        const p = abs.searchParams;
        // Параметры-оригиналы поисковиков — картинки по определению (force:
        // у них бывает URL без расширения — кеш Bing у DDG и т.п.).
        // piurl — прокси Startpage, mediaurl — детальные ссылки Yahoo/Bing,
        // picurl — детальные ссылки Sogou; url/du — общие, только если похоже
        // на картинку (force=false)
        [['img_url', 80, true], ['imgurl', 80, true], ['murl', 80, true],
         ['u', 85, true], ['piurl', 80, true], ['mediaurl', 80, true],
         ['picurl', 80, true], ['du', 75, false], ['url', 70, false]]
          .forEach(([k, sc, force]) => {
            const v = p.get(k);
            if (v && /^https?:/i.test(v)) add(v, sc, force);
          });
        // Bing: JSON в атрибуте m=
        const m = a.getAttribute('m');
        if (m && m.trim().startsWith('{')) {
          try {
            const j = JSON.parse(m);
            if (j && typeof j.murl === 'string') add(j.murl, 85, true);
          } catch (e) {}
        }
        add(a.href, 25); // прямая ссылка на файл тоже кандидат
      } catch (e) {}
    });

    // 3. Обычные <img> и background-image — последний шанс
    selfAndDesc('img').forEach((im) => {
      add(im.currentSrc || im.src, 10);
      (im.srcset || '').split(',').forEach((part) => {
        const u = part.trim().split(/\s+/)[0];
        if (u) add(u, 12);
      });
    });
    selfAndDesc('[style*="background"]').forEach((el) => {
      const st = el.getAttribute('style') || '';
      const re = /background-image:\s*url\((['"]?)([^)'"]+)\1\)/gi;
      let m;
      while ((m = re.exec(st)) !== null) add(m[2], 10);
    });

    // 4. Подстановка оригиналов из data-state: миниатюра -> её origUrl
    const extra = [];
    out.forEach((rec) => {
      const orig = STATE.get(rec.u) || STATE.get(normKey(rec.u));
      if (orig && !seen.has(orig)) {
        const ins = { u: orig, s: rec.s + 50 };
        seen.set(orig, ins);
        extra.push(ins);
      }
    });
    out.push(...extra);

    out.forEach((rec) => { if (/\/orig(\/|$|\?)/i.test(rec.u)) rec.s += 15; });
    out.sort((a, b) => b.s - a.s);
    return out;
  };
  const collectCandidates = (root) => collectCandidateRecs(root).map((rec) => rec.u);

  const SEL = new Map(); // элемент-плитка -> { cands, cb, input, checked }

  const refresh = () => {
    const arr = [];
    SEL.forEach((rec) => {
      if (rec.checked) arr.push({ best: rec.cands[0], alts: rec.cands.slice(1, 5) });
    });
    window.__IH_CB_COUNT = arr.length;
    window.__IH_SEL_JSON = JSON.stringify(arr);
  };

  const attach = (host, cands) => {
    try {
      // Чекбокс нельзя вставлять внутрь замещаемых элементов (<img>, <picture>)
      // — браузер никогда не рисует их потомков, галочка была бы невидима.
      // Используем родителя (новый DOM DDG даёт хостом именно сам <img>).
      while (host && (host.tagName === 'IMG' || host.tagName === 'PICTURE')) {
        host = host.parentElement;
      }
      if (!host || host === document.body || host === document.documentElement) return;
      const pos = getComputedStyle(host).position;
      if (!pos || pos === 'static') host.classList.add('ih-cb-host');
      const cb = document.createElement('label');
      cb.className = 'ih-cb';
      cb.title = __IH_CB_TITLE__;
      const input = document.createElement('input');
      input.type = 'checkbox';
      const box = document.createElement('span');
      box.className = 'ih-cb-box';
      cb.appendChild(input);
      cb.appendChild(box);
      const toggle = () => {
        input.checked = !input.checked;
        const rec = SEL.get(host);
        if (rec) {
          rec.checked = input.checked;
          host.classList.toggle('ih-checked', input.checked);
          refresh();
        }
      };
      ['mousedown', 'mouseup', 'dblclick', 'contextmenu'].forEach((t) => {
        cb.addEventListener(t, (e) => e.stopPropagation());
      });
      cb.addEventListener('click', (e) => {
        // preventDefault гасит и переход по ссылке-плитке, и нативный toggle:
        e.preventDefault();
        e.stopPropagation();
        toggle();
      });
      host.appendChild(cb);
      SEL.set(host, { cands, best: cands[0], cb, input, checked: false });
    } catch (e) {}
  };

  const scan = () => {
    buildState();
    // подчистить плитки, исчезнувшие со страницы
    SEL.forEach((rec, host) => {
      if (!document.documentElement.contains(host)) {
        SEL.delete(host);
        refresh();
      }
    });
    const roots = new Set();
    document.querySelectorAll('a[href], [data-bem], img, [style*="background-image"]')
      .forEach((el) => {
        const host = el.closest(ITEM_SEL) || el;
        roots.add(host);
      });
    roots.forEach((host) => {
      try {
      if (host.dataset.ihCbDone === 'ok') return;
      // не вешаем чекбокс на всю страницу или общий контейнер плиток
      if (host === document.body || host === document.documentElement) {
        host.dataset.ihCbDone = 'skip';
        return;
      }
      const matched = host.matches && host.matches(ITEM_SEL);
      if (!matched && host.querySelectorAll(ITEM_SEL).length > 0) {
        host.dataset.ihCbDone = 'skip'; // внутри есть нормальные плитки — это обёртка
        return;
      }
      if (!matched) {
        // случайный внутренний элемент: не дублируем чекбокс той же картинке
        let dup = false;
        SEL.forEach((rec, h) => {
          if (h === host) return;
          if (h.contains(host) || host.contains(h)) dup = true;
        });
        if (dup) { host.dataset.ihCbDone = 'dup'; return; }
      }
      const recs = collectCandidateRecs(host);
      if (!recs.length) { delete host.dataset.ihCbDone; return; } // ленивые картинки — попробуем позже
      if (!matched) {
        // Поисковики (Google в первую очередь) регулярно меняют хешированные
        // классы плиток — селектор перестал совпадать, и хостом становится
        // случайный внутренний элемент (обычно голый <img>). Тогда лучшим
        // кандидатом остаётся только миниатюра, а настоящий оригинал прячется
        // в якоре-обёртке (/imgres?imgurl=… у Google, u= у DDG…). Поднимаемся
        // от хоста к минимальной обёртке, которая ЗНАЕТ оригинал: пока у
        // кандидата слабая оценка (одна миниатюра), перебираем родителей,
        // но не выше тех, что захватывают чужие <img> (соседние плитки)
        // или содержат настоящую плитку по селектору.
        let bestHost = host, bestRecs = recs, bestScore = recs[0].s;
        // количество <img> считаем включительно с самим элементом: у голого
        // <img> querySelectorAll('img') пуст, и guard всегда срабатывал бы
        const imgCount = (el) =>
          (el.matches && el.matches('img') ? 1 : 0)
          + el.querySelectorAll('img').length;
        let probe = host;
        for (let hop = 0; hop < 8; hop++) {
          const p = probe.parentElement;
          if (!p || p === document.body || p === document.documentElement) break;
          if (imgCount(p) !== imgCount(host)) break;
          if (p.matches(ITEM_SEL) || p.querySelectorAll(ITEM_SEL).length > 0) break;
          const pc = collectCandidateRecs(p);
          const ps = pc.length ? pc[0].s : -1;
          if (ps > bestScore) { bestHost = p; bestRecs = pc; bestScore = ps; }
          probe = p;
        }
        if (bestHost !== host) { host = bestHost; recs = bestRecs; }
        let same = false;
        SEL.forEach((rec, h) => {
          if (h !== host && !h.contains(host) && !host.contains(h) && rec.cands[0] === recs[0].u) same = true;
        });
        if (same) { host.dataset.ihCbDone = 'dup'; return; }
      }
      const cands = recs.map((rec) => rec.u);
      host.dataset.ihCbDone = 'ok';
      attach(host, cands);
      } catch (e) {}   // одна битая плитка не должна рушить весь скан
    });
    refresh();
  };

  // Стили чекбоксов. ВАЖНО: страница-выдача может быть в любой момент
  // заменена (ошибка сети, переадресация) — document.head и даже
  // documentElement бывают null, и незащищённый appendChild падал
  // «Uncaught TypeError», оставаясь в консоли вкладки. Всё под защитой.
  const injectStyle = () => {
    try {
      if (document.getElementById('ih-cb-style')) return;
      const root = document.head || document.documentElement;
      if (!root) return;      // документ ещё грузится/заменяется — стили поставит следующий скан
      const st = document.createElement('style');
      st.id = 'ih-cb-style';
      st.textContent = [
        '.ih-cb{position:absolute!important;top:8px;left:8px;z-index:2147483640;',
        'width:26px;height:26px;margin:0;cursor:pointer;display:block;filter:drop-shadow(0 1px 3px rgba(0,0,0,.45));}',
        '.ih-cb input{position:absolute;opacity:0;width:100%;height:100%;margin:0;cursor:pointer;}',
        '.ih-cb .ih-cb-box{position:absolute;inset:0;border-radius:7px;',
        'background:rgba(255,255,255,.93);border:2px solid #ff7a1a;}',
        '.ih-cb:hover .ih-cb-box{background:#fff;}',
        '.ih-cb .ih-cb-box::after{content:\'\';position:absolute;left:7px;top:2px;width:6px;height:12px;',
        'border:solid #fff;border-width:0 3px 3px 0;transform:rotate(45deg);opacity:0;}',
        '.ih-cb input:checked + .ih-cb-box{background:#ff7a1a;}',
        '.ih-cb input:checked + .ih-cb-box::after{opacity:1;}',
        '.ih-cb-host{position:relative!important;}',
        '.ih-checked{outline:3px solid #ff7a1a!important;outline-offset:-3px;border-radius:6px;}'
      ].join('');
      root.appendChild(st);
    } catch (e) {}
  };

  // массовое снятие отметок (кнопка «Снять отметки» на тулбаре)
  const uncheckAll = () => {
    SEL.forEach((rec, host) => {
      rec.input.checked = false;
      rec.checked = false;
      host.classList.remove('ih-checked');
    });
    refresh();
  };

  window.__IH_CB = { scan, uncheckAll };

  try { injectStyle(); scan(); } catch (e) {}   // документ может быть mid-teardown

  // Подхватывать плитки, догружаемые при прокрутке (бесконечная выдача).
  // ТРОТТЛИНГ, а не debounce: при шторме мутаций (Google дорисовывает выдачу
  // несколько секунд подряд) debounce всё время откладывал скан до паузы —
  // чекбоксы появлялись поздно и не на всех плитках. Троттлинг гарантирует
  // скан не позже 400 мс после ПЕРВОЙ мутации шторма.
  // attributes+['src']: у ленивых плиток <img> получает src позже — childList
  // смену атрибута не видит, поэтому на части плиток чекбоксов не было вовсе.
  let lastScan = 0, pend = 0, mo = null, observed = null;
  const schedule = () => {
    clearTimeout(pend);
    const wait = Math.max(0, lastScan + 400 - Date.now());
    pend = setTimeout(() => {
      lastScan = Date.now();
      try { scan(); } catch (e) {}   // скан из таймера — без защиты падал бы молча
    }, wait);
  };
  const startObserver = () => {
    const b = document.body || document.documentElement;
    if (!b || b === observed) return;
    if (!mo) {
      mo = new MutationObserver(schedule);
      // страховка: раз в 2 с перепроверяем тело и пересканируем — чинит
      // случаи, когда выдача дорисовалась «тихо» (без мутаций observer'а)
      setInterval(() => { startObserver(); schedule(); }, 2000);
    }
    mo.observe(b, { childList: true, subtree: true,
                    attributes: true, attributeFilter: ['src'] });
    observed = b;
  };
  if (document.body) { lastScan = Date.now(); startObserver(); }
  else document.addEventListener('DOMContentLoaded', startObserver);
})()
