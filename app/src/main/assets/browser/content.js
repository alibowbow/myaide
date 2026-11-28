/* Isolated extension world: pages have no native API. Only commands from our native port are executed. */
(() => {
  let port, away=false, retries=0;
  const documentId = crypto.randomUUID ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint32Array(4)),n=>n.toString(16)).join('-');
  let refs = new Map(), revision = 0, working = false;
  const tidy = (s, n = 160) => String(s || '').replace(/\s+/g, ' ').trim().slice(0, n);
  const visible = e => {
    const r=e.getBoundingClientRect(); if(r.width<=0 || r.height<=0) return false;
    for(let node=e;node;node=node.parentElement || node.getRootNode()?.host) {
      const s=getComputedStyle(node); if(node.hidden || node.getAttribute('aria-hidden')==='true' || s.visibility==='hidden' || s.display==='none') return false;
    }
    return true;
  };
  const inViewport = e => { const r=e.getBoundingClientRect(); return visible(e) && (r.bottom ?? 1)>0 && (r.right ?? 1)>0 && (r.top ?? 0)<innerHeight && (r.left ?? 0)<innerWidth; };
  function roots() { const result=[document]; for(let i=0;i<result.length&&i<80;i++) for(const e of result[i].querySelectorAll('*')) if(e.shadowRoot) result.push(e.shadowRoot); return result; }
  const all = selector => roots().flatMap(root=>[...root.querySelectorAll(selector)]);
  const secret = e => e.matches('input[type="password"],input[type="hidden"],input[type="file"]') || /password|one.time|cc-|credit.card|security.code|otp|token|secret/i.test([e.autocomplete,e.name,e.id].join(' '));
  const stale = message => Object.assign(Error(message),{staleObservation:true});
  const editable = e => e.isContentEditable || ['','true','plaintext-only'].includes(e.getAttribute('contenteditable'));
  const inputText = e => String(editable(e) ? e.innerText ?? e.textContent : e.value).replace(/\r\n?/g,'\n').replace(/\u00a0/g,' ');
  const retains = (e,text) => {
    const actual=inputText(e), expected=text.replace(/\r\n?/g,'\n').replace(/\u00a0/g,' ');
    const children=[...e.childNodes].filter(n=>n.nodeType!==Node.TEXT_NODE || n.textContent!=='');
    // Gecko adds a terminal <br> to keep the caret on an empty final line.
    // A rich editor (YouTube Studio's title and description) may lay the same words out with other line breaks or spaces.
    const squash = s => s.replace(/\s+/g,' ').trim();
    return actual===expected || editable(e) && (children.at(-1)?.nodeName==='BR' &&
      (actual===expected+'\n' || expected==='' && children.length===1) || expected!=='' && squash(actual)===squash(expected));
  };
  // Anonymous-comment credentials protect a form, not the surrounding public article.
  // Fail closed unless both a clearly marked comment editor and independent public
  // content exist. The entire form stays unavailable to AI, not only its password.
  const commentMarker = /(?:comment|reply|(?:^|[\s_-])cmt(?:[\s_-]|$)|댓글|답글)/i;
  // Mobile forum bodies need not use <article>. Keep the content evidence
  // separate from the comment editor; these selectors never exempt a login form.
  const articleBody = '[itemprop="articleBody"],.write_div,.view_content,.gallview_contents,.thum-txtin';
  const composedContains = (parent, child) => {
    for (let node=child;node;node=node.parentElement || node.getRootNode()?.host)
      if (node===parent) return true;
    return false;
  };
  function commentRegion(password) {
    if (all('dialog,[role="dialog"],[aria-modal="true"]').some(e=>composedContains(e,password)) ||
        /^(?:current-password|one-time-code)$/i.test(password.autocomplete)) return null;
    // A collapsed/lazy comment editor still owns its password and submit controls.
    // It is never exposed or operated, so editor visibility is not read permission.
    const editors = all('textarea,[role="textbox"][contenteditable]:not([contenteditable="false"])');
    for (let region=password.form || password.parentElement, depth=0;
         region && depth<8 && !region.matches('body,html,main,article,[role="main"],[role="article"]');
         region=region.parentElement || region.getRootNode()?.host, depth++) {
      const localEditors=editors.filter(e=>composedContains(region,e) || e.form===region);
      if (!localEditors.length) continue;
      const markers=[region.id,region.className,region.getAttribute('aria-label'),
        ...localEditors.flatMap(e=>[e.id,e.name,e.getAttribute('aria-label'),e.getAttribute('placeholder')])].join(' ');
      if (!commentMarker.test(markers)) continue;
      // A comment-looking label must not turn an actual account/authentication form
      // or a modal authentication wall into a public-content exception.
      const owned=all('input,button').filter(e=>composedContains(region,e) || e.form===region);
      if (owned.some(e=>e.matches('input[autocomplete="username"],input[autocomplete="current-password"],input[type="email"]')) ||
          owned.filter(e=>e.matches('button,input[type="submit"]')).some(e=>
            /(?:log\s*in|sign\s*in|로그인|인증)/i.test(e.getAttribute('aria-label') || e.textContent || e.value || ''))) return null;
      const publicArticle=all('article,[role="article"],'+articleBody).some(e=>
        visible(e) && !composedContains(region,e) &&
        [...(e.matches(articleBody)?[e]:[]),...e.querySelectorAll('p,div')].some(part=>visible(part) && !part.closest('input,textarea,[contenteditable]') && !composedContains(region,part) &&
          !part.querySelector('input,textarea,[contenteditable]') && tidy(part.textContent).length>0));
      if (publicArticle) return region;
    }
    return null;
  }
  function commentRegions() {
    const regions=[...new Set(all('input[type="password"]').filter(visible).map(commentRegion).filter(Boolean))];
    // Externally associated submit controls own their descendants too: a nested
    // role=button/span must not escape through a bubbling click or label text.
    return [...regions,...all('[form]').filter(e=>e.form && regions.some(region=>
      e.form===region || composedContains(region,e.form)))];
  }
  const protectedComment = (e, regions) => regions.some(region=>composedContains(region,e) ||
    (e.form && (e.form===region || composedContains(region,e.form))) || composedContains(e,region));
  // Report only categories, never credentials, callback parameters or frame URLs.
  // Google's account chooser: YouTube sends a switch to another signed-in Google account through it. There only the rows of
  // accounts already signed in are shown and can be clicked (see snapshot); a password, code or captcha there stays protected.
  const CAPTCHA = /recaptcha|hcaptcha|challenges.cloudflare/;
  const accountChooser = () => location.hostname === 'accounts.google.com' && /accountchooser|signinchooser/i.test(location.pathname)
    && !all('input[type="password"],input[autocomplete="one-time-code"]').some(visible)
    && !all('iframe[src]').some(e => visible(e) && CAPTCHA.test(e.src));
  // An authentication form (a password, a one-time code, a captcha) protects itself, not the page around it (준: needless limits
  // off): a login box in a sidebar or a reCAPTCHA badge in a corner no longer blanks the article. The form, or the nearest box
  // holding the field with its own submit, is hidden from the AI and refused (authRegions); the rest stays readable and usable.
  // A page that is nothing but that form (a sign-in page: little else to read outside it), accounts.google.com and an OAuth
  // callback stay blanked whole, as before (guardReason).
  const landmark = 'body,html,main,article,[role="main"],[role="article"]';
  const submits = 'button,input[type="submit"],input[type="image"],[role="button"]';
  // A form that wraps the page (ASP.NET's one form around everything) is no login box.
  const pageForm = form => !!form.querySelector('main,article,[role="main"],[role="article"]') || tidy(form.textContent, 4000).length > 1500;
  function authBox(field) {
    const form = field.form || field.closest('form');
    if (form && !pageForm(form)) return form;
    if (field.tagName === 'IFRAME') return field.parentElement && !field.parentElement.matches(landmark) ? field.parentElement : field;
    for (let node=field.parentElement, depth=0; node && depth<6 && !node.matches(landmark) && node!==form; node=node.parentElement || node.getRootNode()?.host, depth++)
      if ([...node.querySelectorAll(submits)].some(e => e!==field && visible(e))) return node;
    return field.parentElement && !field.parentElement.matches(landmark) ? field.parentElement : field;
  }
  /** Each visible authentication field's box and why: captcha, otp or password (an anonymous comment's password is commentRegion's).
   * An invisible reCAPTCHA's corner badge (quiet) asks the user nothing: it is hidden, and never makes the page a sign-in page. */
  function authRegions() {
    const found = [];
    for (const e of all('input[type="password"]')) if (visible(e) && !commentRegion(e)) found.push({box:authBox(e), reason:'password'});
    for (const e of all('input[autocomplete="one-time-code"]')) if (visible(e)) found.push({box:authBox(e), reason:'otp'});
    for (const e of all('iframe[src]')) if (visible(e) && CAPTCHA.test(e.src)) {
      // A quiet badge hides only itself, never the form it sits in (a contact form with an invisible reCAPTCHA stays usable).
      if (/[?&]size=invisible(?:&|$)/.test(e.src) || e.closest('.grecaptcha-badge')) found.push({box:e.closest('.grecaptcha-badge') || e, reason:'captcha', quiet:true});
      else found.push({box:authBox(e), reason:'captcha'});
    }
    // One box once (its first reason: a password before a captcha), an outer box over the boxes inside it.
    return found.filter((a, i) => !found.some((b, j) => j!==i && b.box!==a.box && composedContains(b.box, a.box)) && found.findIndex(b => b.box===a.box)===i);
  }
  // Page furniture around a sign-in form: its header, menus, side and footer are not something to read.
  const furniture = 'header,footer,nav,aside,[role="banner"],[role="navigation"],[role="contentinfo"],[role="complementary"],[id*="footer" i],[class*="footer" i],[id*="header" i],[class*="header" i]';
  /** The authentication form is the page's main content: it holds the page's main part, or outside it and the page furniture
   * there is hardly anything to read (under 120 characters: a heading, "아이디 찾기", "회원가입"; 300 beside a captcha to solve,
   * as on a full-page "Verifying you are human" check). */
  function authPage(found) {
    const regions = found.filter(r => !r.quiet);
    if (!regions.length) return false;
    const boxes = found.map(r => r.box), enough = regions.some(r => r.reason === 'captcha') ? 300 : 120;
    if (regions.some(r => r.box.matches('main,[role="main"]') || r.box.querySelector('main,[role="main"]'))) return true;
    let size = 0, nodes = 0;
    for (const root of roots()) {
      const walker = document.createTreeWalker(root===document ? document.body || document.documentElement : root, NodeFilter.SHOW_TEXT);
      while (walker.nextNode() && size < enough && ++nodes < 4000) {
        const e = walker.currentNode.parentElement, t = tidy(walker.currentNode.textContent, 400);
        if (!t || !e || e.closest('script,style,noscript,template,' + furniture) || boxes.some(box => composedContains(box, e)) || !visible(e)) continue;
        size += t.length;
      }
    }
    return size < enough;
  }
  const guardReason = () => {
    if (accountChooser()) return '';
    if (location.hostname === 'accounts.google.com') return 'auth_host';
    if (/(?:^|[?&])(?:code|access_token|id_token|oauth_token)=/i.test(location.search)) return 'oauth_callback';
    const regions = authRegions();
    if (!authPage(regions)) return '';
    for (const reason of ['captcha', 'otp', 'password']) if (regions.some(r => r.reason === reason && !r.quiet)) return reason;
    return '';
  };
  const loginPage = () => !!guardReason();
  /** What the AI never sees or operates on a page it can read: anonymous comment forms and authentication forms, with the
   * controls that submit them from outside (a form attribute). */
  function protectedRegions(found = authRegions()) {
    const auth = found.map(r => r.box);
    return [...commentRegions(), ...auth, ...all('[form]').filter(e => e.form && auth.some(box => e.form===box || composedContains(box, e.form)))];
  }
  // Read-only, coarse evidence for long-running work. These are page claims,
  // never authorization or proof that a generation has completed. A missing
  // indicator is UNKNOWN: Canvas can keep working without changing visible text.
  function pageActivity() {
    const evidence = [];
    const documentLoading = document.readyState === 'loading';
    if (documentLoading) evidence.push('document_loading');
    const regions=protectedRegions();
    const observable = e => inViewport(e) && !secret(e) && !protectedComment(e,regions);
    if (all('[aria-busy="true"]').some(observable)) evidence.push('aria_busy');
    if (all('progress,[role="progressbar"]').some(e => {
      if (!observable(e)) return false;
      if (e.tagName === 'PROGRESS') return !e.hasAttribute('value') || e.value < e.max;
      const now = e.getAttribute('aria-valuenow'), max = e.getAttribute('aria-valuemax');
      // A completed determinate bar is not evidence of ongoing work. Invalid or
      // indeterminate values remain a busy hint, not a percentage or completion.
      return now === null || max === null || !now.trim() || !max.trim() ||
        !Number.isFinite(Number(now)) || !Number.isFinite(Number(max)) ||
        Number(now) < 0 || Number(max) <= 0 || Number(now) < Number(max);
    })) evidence.push('progressbar');
    if (location.protocol === 'https:' && location.hostname === 'gemini.google.com') {
      const stopLabels = new Set(['stop generating', 'stop response', 'stop generation',
        '생성 중지', '응답 중지', '답변 생성 중지']);
      if (all('button,[role="button"]').some(e => observable(e) && !e.disabled &&
          e.getAttribute('aria-disabled') !== 'true' &&
          stopLabels.has(tidy(e.getAttribute('aria-label') || e.innerText || e.getAttribute('title')).toLowerCase())))
        evidence.push('generation_stop');
    }
    return {state:evidence.length ? 'busy' : 'unknown', evidence, document_loading:documentLoading};
  }
  // A proposal precedes all DOM dispatch; native code performs and verifies the GET.
  function publicReadUrl(raw) {
    if (!raw || !String(raw).trim() || String(raw).trim().startsWith('#')) return '';
        try { const u = new URL(raw, document.baseURI || location.href);
          const readKeys = new Set(['id','no','page','p','pg','article','article_id','articleid','board','board_id','bid','bo_table','wr_id','news_id','nid','idx','index','category','cat','section','type','recommend','exception_mode','q','query','search','keyword','s','s_type','s_keyword','sort','order','list_num','offset','limit','date','from','to','lang','hl','gl','utm_source','utm_medium','utm_campaign','utm_content','utm_term',
            // Korean public sites' board, menu, search and paging keys (boardID, nttId, m, searchStr, pageIndex …).
            'boardid','boardseq','bbsid','bbs_id','bbsno','nttid','ntt_id','nttsn','articleseq','seq','menuid','menu_id','menuno','menu_no','mid','m','searchstr','searchtype','searchcnd','searchwrd','searchkeyword','searchcondition','pageindex','pageunit','currpage','cpage','curpage','pageno','page_no','lev','year','statusyn']);
          const path = decodeURIComponent(u.pathname);
          const types=new Set(['best','all','list','view','read','article','news','search','title','subject','content','latest','new','recommend','recommendation','popular','gallery','1','0']);
          const host=u.hostname.toLowerCase();
          if (u.href.length<=2048 && u.search.length<=1201 && (!u.port || u.port==='443') &&
              /^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,63}$/.test(host) &&
              !/(?:^|\.)(local|localhost|internal|invalid|test|onion|nip\.io|sslip\.io|localtest\.me)$/.test(host) && u.protocol === 'https:' && !u.username && !u.password && !u.hash &&
              !/(^|[\/_.-])(login|signin|signout|logout|oauth|auth|authorize|callback|password|passwd|reset|verify|confirm|captcha|delete|remove|destroy|erase|purge|withdraw|unsubscribe|purchase|buy|checkout|pay|payment|order|submit|send|write|post|edit|update|upload|save|create|execute|trigger|action|redirect|revoke|approve|accept|cancel|vote|like|dislike|react|reaction|follow|unfollow|join|leave|subscribe|register|enroll|invite|share|report|block|unblock|connect|disconnect|install|run|redeem|claim|transfer|donate)([\/_.-]|$)/i.test(path) &&
              !/%|;|\\|[\x00-\x1f\x7f]/.test(path) && !path.includes('..') && !u.search.slice(1).split('&').some(p=>u.search && !p) && [...u.searchParams].every(([k,v])=>readKeys.has(k.toLowerCase()) && !/[%\x00-\x1f\x7f]/.test(v) && (!['type','s_type'].includes(k.toLowerCase()) || types.has(v.toLowerCase())) && (k.toLowerCase()!=='recommend' || ['0','1'].includes(v)) && (k.toLowerCase()!=='exception_mode' || ['', 'recommend'].includes(v)) && v.length<=400 && !/https?:|javascript:|bearer\s|sk[-_]|access_token|password=/i.test(v))) return u.href;
        } catch (_) { }
    return '';
  }
  /** Where a native submit button, or Enter in a text field, sends its form: origin and path only (never its query or
   * fragment); "" when it sends none. */
  function formAction(e) {
    if (!e.form || !e.matches('button:not([type]),button[type="submit"],input[type="submit"],input[type="image"],input:not([type]),input[type="text"],input[type="search"],input[type="email"],input[type="number"],input[type="tel"]')) return '';
    try { const u=new URL(e.matches('button,input[type="submit"],input[type="image"]') && e.getAttribute('formaction') || e.form.getAttribute('action') || location.href, document.baseURI || location.href);
      return /^https?:$/.test(u.protocol) ? u.origin + u.pathname : ''; } catch (_) { return ''; }
  }
  function sameTab(e) {
    const target=e.getAttribute('target') || document.querySelector('base[target]')?.getAttribute('target') || '';
    return !target || target.toLowerCase()==='_self';
  }
  // Extract only a literal navigation assignment. Never evaluate page JavaScript or
  // treat an arbitrary click handler/function call as evidence of a GET destination.
  function anchorReadUrl(anchor) {
    const raw=anchor.getAttribute('href') || '', direct=publicReadUrl(raw);
    if(direct)return direct;
    const code=/^javascript:/i.test(raw) ? raw.replace(/^javascript:/i,'') :
      (!raw || raw.startsWith('#')) ? anchor.getAttribute('onclick') || '' : '';
    const match=code.trim().match(/^(?:(?:window|document)\.)?location(?:\.href)?\s*=\s*(['"])([^'"\\\r\n]+)\1\s*;?\s*(?:return\s+false\s*;?)?$/);
    return match ? publicReadUrl(match[2]) : '';
  }
  function navigationProposal(a,e) {
    if (!(a.action==='click' || a.action==='press' && a.text==='Enter')) return null;
    const anchor=e.closest('a[href]');
    if (anchor) {
      if (!sameTab(anchor) || anchor.hasAttribute('download')) return null;
      const url=anchorReadUrl(anchor);
      return url ? {method:'GET',url,document:documentId} : null;
    }
    const form=e.form;
    if (!form || !sameTab(form) || (form.getAttribute('method') || 'get').toLowerCase()!=='get') return null;
    const controls=[...form.elements];
    const submitters=controls.filter(c=>!c.matches(':disabled') &&
      (c.tagName==='BUTTON' && (!c.type || c.type==='submit') || c.tagName==='INPUT' && c.type==='submit'));
    if(a.action==='press' && e.tagName==='INPUT' && submitters.length>1) return null;
    const submitter=submitters.includes(e) ? e : a.action==='press' && e.tagName==='INPUT' && submitters.length<=1 ? submitters[0] : null;
    if (a.action==='click' && !submitter || a.action==='press' && e.tagName!=='INPUT' && !submitter) return null;
    if (submitter && ['formaction','formmethod','formtarget','formenctype'].some(n=>submitter.hasAttribute(n))) return null;
    const search=controls.some(c=>c.tagName==='INPUT' && !c.matches(':disabled') &&
      (c.type==='search' || c.getAttribute('role')==='searchbox' || /^(q|query|search|keyword|s_keyword)$/i.test(c.name)));
    if (!search) return null;
    const action=publicReadUrl(form.getAttribute('action') || location.href);
    if (!action) return null;
    const params=new URLSearchParams();
    for(const c of controls) {
      if(c.matches(':disabled') || !c.name) continue;
      if(c===submitter) { params.append(c.name,c.value || ''); continue; }
      if(c.tagName==='BUTTON' || c.tagName==='INPUT' && ['submit','button','reset'].includes(c.type)) continue;
      if(c.tagName!=='INPUT' || !['text','search','hidden'].includes(c.type) ||
          /password|one.time|cc-|credit.card|security.code|otp|token|secret|auth|cookie/i.test([c.autocomplete,c.name,c.id].join(' '))) return null;
      params.append(c.name,c.value || '');
    }
    const url=new URL(action); url.search=params.toString();
    const destination=publicReadUrl(url.href);
    return destination ? {method:'GET',url:destination,document:documentId} : null;
  }
  // The account chooser as the AI sees it: one row per signed-in account (its name and address), nothing else — no "use another
  // account", no removal, no signed-out account (that one asks for a password, which stays the user's).
  function chooserSnapshot() {
    refs = new Map(); revision++;
    const elements = [];
    for (const e of all('[data-identifier],[data-email]')) {
      if (elements.length >= 12 || !visible(e)) continue;
      const words = tidy(e.innerText || e.getAttribute('aria-label') || '', 200);
      const address = e.getAttribute('data-identifier') || e.getAttribute('data-email') || '';
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address) || /다른 계정|another account|계정 삭제|삭제|remove|로그아웃됨|signed out/i.test(words)) continue;
      const ref = revision + ':' + elements.length;
      refs.set(ref, {element:e, fingerprint:fingerprint(e)});
      elements.push({ref, tag:e.tagName.toLowerCase(), type:'', label:words || address, disabled:false, role:e.getAttribute('role') || 'link', editable:false, readonly:true,
        selected:false, current:false, selection_hint:false, checked:false, placeholder:'', account_choice:true});
    }
    return {ok:true, document:documentId, title:'Google 계정 선택', url:location.origin + location.pathname, account_chooser:true,
      text:'Google 계정 선택 화면이에요. 이미 로그인된 계정 중 하나를 누르기만 할 수 있어요. 비밀번호·인증 화면이 나오면 사용자가 직접 해야 해요.',
      elements, frames:0, activity:pageActivity()};
  }
  // The dialog on top, when one is open (a modal form, a menu sheet): its controls are the ones that can be used.
  function topDialog() {
    const open=all('dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"],tp-yt-paper-dialog').filter(e=>visible(e) && inViewport(e));
    return open.length ? open[open.length-1] : null;
  }
  const scrollable = e => { if(!e || e===document.body || e===document.documentElement) return false; const s=getComputedStyle(e); return /(auto|scroll|overlay)/.test(s.overflowY) && e.scrollHeight>e.clientHeight+4; };
  const area = e => e.clientHeight*e.clientWidth;
  /** What a scroll moves: the scrollable part of an open dialog, else the page when it can move that way, else the largest scrollable part on screen. null is the page. */
  function scroller(down) {
    const can = e => down ? e.scrollTop+e.clientHeight < e.scrollHeight-2 : e.scrollTop>0;
    const dialog=topDialog();
    if (dialog) {
      const inner=[dialog,...dialog.querySelectorAll('*')].filter(e=>scrollable(e) && visible(e) && can(e)).sort((x,y)=>area(y)-area(x))[0];
      if (inner) return inner;
    }
    const page=document.scrollingElement || document.documentElement;
    if (down ? scrollY+innerHeight < page.scrollHeight-2 : scrollY>0) return null;
    return all('div,section,main,article,aside,ul,ol,nav,form').filter(e=>scrollable(e) && inViewport(e) && can(e)).sort((x,y)=>area(y)-area(x))[0] || null;
  }
  function snapshot() {
    const guard = guardReason();
    if (guard) { refs = new Map(); revision++; return {ok:true, requires_user:true, error_code:'authentication_required', guard_reason:guard,
      error:guard==='password' ? '로그인 화면이라 이 페이지는 AI가 읽거나 조작하지 않아요. 로그인은 직접 하거나, 뒤로 가기 또는 공개 HTTPS 홈페이지로 이동을 새로 지시해 주세요.' : '인증·보안 확인은 직접 진행해 주세요. 이 화면에서 나가려면 뒤로 가기 또는 공개 HTTPS 홈페이지로 이동을 새로 지시해 주세요.',
      title:'보호된 입력 · 사용자 확인 필요', url:location.origin, document:documentId, elements:[], text:'보호된 화면의 내용은 AI에게 전달하지 않습니다.'}; }
    if (accountChooser()) return chooserSnapshot();
    refs = new Map(); revision++;
    const auth = authRegions(), elements = [], regions=protectedRegions(auth);
    // Menu items, list options and checkboxes too: YouTube Studio's account and channel menu, its category and language
    // lists and its checkboxes are made of these, not of buttons or links.
    // Controls scrolled out of sight are listed too (offscreen:true): a form inside a dialog (YouTube Studio's upload
    // details) scrolls inside the dialog, so a page scroll never brings them up; click and type bring them into view
    // themselves. An open dialog's controls come first, then the rest of what is on screen, then a few more off it.
    const dialog=topDialog(), rank=[[],[],[],[]];
    let seen=0;
    for (const e of all('a[href],button,input,textarea,select,[role="button"],[role="link"],[role="combobox"],[role="searchbox"],[role="tab"],[role="switch"],[role="radio"],[role="textbox"],[role="menuitem"],[role="menuitemradio"],[role="menuitemcheckbox"],[role="option"],[role="checkbox"],[role="treeitem"],[contenteditable]:not([contenteditable="false"])')) {
      if (++seen > 2000) break;
      if (secret(e) || !visible(e) || protectedComment(e,regions)) continue;
      const near=inViewport(e), inside=dialog && composedContains(dialog,e);
      rank[inside ? (near ? 0 : 1) : (near ? 2 : 3)].push(e);
    }
    const chosen=[...rank[0],...rank[1],...rank[2].slice(0,100),...rank[3].slice(0,40)].slice(0,150);
    for (const e of chosen) {
      const ref = revision + ':' + elements.length;
      refs.set(ref, {element:e, fingerprint:fingerprint(e)});
      const safeLabel = node => node && !protectedComment(node,regions) && !secret(node) ? node.textContent : '';
      const labelled = (e.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean).map(id=>safeLabel(document.getElementById(id))).join(' ').trim();
      // The first source with visible words: a link holding only an icon and a no-break space is named by its title or icon text.
      const label = [labelled, e.getAttribute('aria-label'), safeLabel(e.labels?.[0]), e.innerText, e.getAttribute('placeholder'), e.getAttribute('title'),
        [...e.querySelectorAll('img[alt]')].map(i=>i.getAttribute('alt')).join(' ')].map(v=>tidy(v)).find(Boolean) || '';
      const publicHref = e.tagName === 'A' && !e.hasAttribute('download') && sameTab(e) ? anchorReadUrl(e) : '';
      const navigationKind=e.tagName!=='A' ? '' : publicHref ? 'public_get' : /^javascript:/i.test(e.getAttribute('href') || '') ? 'scripted' : (e.getAttribute('href') || '').startsWith('#') ? 'fragment' : 'other';
      elements.push({...(publicHref ? {href:publicHref} : {}), ...(navigationKind ? {navigation_kind:navigationKind} : {}), ...(inViewport(e) ? {} : {offscreen:true}), ...(dialog && composedContains(dialog,e) ? {in_dialog:true} : {}),
        ref, tag:e.tagName.toLowerCase(), type:e.type || '', label, disabled:!!e.disabled, role:e.getAttribute('role') || '',
        editable:e.matches('textarea,input:not([type]),input[type="text"],input[type="search"],input[type="email"],input[type="url"]') || editable(e), readonly:!!e.readOnly,
        selected:e.getAttribute('aria-selected')==='true' || e.tagName==='OPTION' && !!e.selected,
        current:['page','step','location','date','time','true'].includes(e.getAttribute('aria-current')),
        // A visual class is a hint, not an ARIA selection or a completed goal.
        selection_hint:[e,e.parentElement].some(n=>n && /(?:^|\s)(?:on|active|selected|current)(?:\s|$)/i.test(n.className || '')),
        checked:e.getAttribute('aria-checked')==='true' || !!e.checked,
        placeholder:tidy(e.getAttribute('placeholder')),
        // An input inside a search landmark is a search box for the automation gate (BrowserDecisionEvidence.fieldKind).
        ...(e.tagName === 'INPUT' && e.closest('[role="search"],search') ? {in_search:true} : {}),
        // Where a submit button sends its form (no query, no fragment): the automation gate reads a checkout or payment address.
        ...(formAction(e) ? {form_action:formAction(e)} : {}),
        ...(e.tagName === 'SELECT' ? {options:[...e.options].slice(0,30).map(o=>({value:o.value,label:tidy(o.text)}))}: {})});
    }
    const chunks = []; let size = 0;
    for(const root of roots()) {
    const walker = document.createTreeWalker(root===document ? document.body || document.documentElement : root, NodeFilter.SHOW_TEXT);
    while(walker.nextNode() && size < 12000) {
      const e = walker.currentNode.parentElement;
      if (!e || e.closest('script,style,noscript,input,textarea,select,[contenteditable]') || !visible(e) || protectedComment(e,regions)) continue;
      const t = tidy(walker.currentNode.textContent, 1500); if(t) {chunks.push(t);size+=t.length;}
    }
    }
    // Preserve independently validated public read parameters (article id/filter/page).
    // Unknown queries, credentials and fragments still never enter observations.
    return {ok:true, document:documentId, title:tidy(document.title), url:publicReadUrl(location.href) || location.origin+location.pathname,
      text:chunks.join('\n').slice(0,12000), elements, frames:document.querySelectorAll('iframe').length, activity:pageActivity(),
      ...(auth.some(r => !r.quiet) ? {protected_form:{reason:auth.find(r => !r.quiet).reason, count:auth.filter(r => !r.quiet).length,
        note:'이 페이지의 로그인·인증 입력 영역은 AI에게 보이지 않고 누르거나 입력할 수 없어요. 나머지 내용은 그대로 읽고 쓸 수 있어요. 로그인이 꼭 필요하면 사용자에게 직접 해 달라고 하세요.'}} : {})};
  }
  // Sample meaningful rendered content rather than waiting for every advertisement,
  // animation or network resource. No page script is patched or evaluated.
  function renderState(target) {
    if (away) throw stale('페이지가 이동 중이에요. 새 페이지를 읽어 주세요.');
    if (loginPage()) return {protected:true, signature:'protected', busy:false};
    const regions=protectedRegions(), chunks=[];
    const scope=all('main,article,[role="main"],'+articleBody).filter(inViewport);
    const body=scope.length ? scope : [document.body || document.documentElement];
    for (const root of body.slice(0,4)) {
      const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT); let size=0;
      while(walker.nextNode() && size<6000) {
        const node=walker.currentNode.parentElement;
        if(!node || node.closest('script,style,noscript,input,textarea,select,[contenteditable]') || !inViewport(node) || protectedComment(node,regions))continue;
        const text=tidy(walker.currentNode.textContent,500); chunks.push(text);size+=text.length;
      }
    }
    const usable=target?.isConnected && visible(target) && !secret(target) && !protectedComment(target,regions);
    const control=usable ? [target.getAttribute('aria-selected'),target.getAttribute('aria-checked'),!!target.checked,
      document.activeElement===target,!!target.disabled,!!target.readOnly,
      target.matches('input,textarea,select') || editable(target) ? inputText(target) : tidy(target.textContent)] : [];
    const text=chunks.join('\n'), url=publicReadUrl(location.href) || location.origin+location.pathname;
    return {text,url,control:JSON.stringify(control),busy:pageActivity().state==='busy',signature:JSON.stringify([url,text,control,scrollX,scrollY])};
  }
  async function settle(target) {
    const started=performance.now(); let last=renderState(target), stable=0;
    while(performance.now()-started<1600) {
      await new Promise(resolve=>setTimeout(resolve,100));
      const next=renderState(target); stable=next.signature===last.signature ? stable+1 : 0; last=next;
      if(next.protected || stable>=3 && !next.busy) return {state:next,info:{state:next.protected?'protected':'stable',elapsed_ms:Math.round(performance.now()-started),samples_stable:stable}};
    }
    return {state:last,info:{state:'pending',timed_out:true,elapsed_ms:Math.round(performance.now()-started),samples_stable:stable}};
  }
  function actionEffect(action,before,after,settled,input) {
    if(after.protected)return {action,kind:'protected',dispatched:true,task_complete:false};
    const location_changed=before.url!==after.url, text_changed=before.text!==after.text, control_changed=before.control!==after.control;
    return {action,kind:settled.state==='pending'?'pending':location_changed?'navigation':input?'input_retained':control_changed?'control_changed':text_changed?'content_changed':'no_observable_change',
      dispatched:true,location_changed,text_changed,control_changed,task_complete:false,
      ...(input?{input_verified:true}:{}),retry_policy:'Observe or replan; lack of visible change is not permission to repeat an accepted action.'};
  }
  /** Files sent by the app for put_file, by token. */
  const uploads = new Map();
  /** A file field's accept list takes this file (an empty list takes anything). */
  const accepts = (input, file) => { const list=(input.accept||'').toLowerCase().split(',').map(s=>s.trim()).filter(Boolean);
    return !list.length || list.some(t => t===file.type || t.endsWith('/*') && file.type.startsWith(t.slice(0,-1)) || t.startsWith('.') && file.name.toLowerCase().endsWith(t)); };
  /**
   * put_file: the file the app sent goes straight into the page's file field (the one the clicked control opens, else the
   * one in its dialog, else the first that takes the file), as if chosen — no chooser has to open. A page with no file field
   * gets it dropped on the clicked control; failing both, the real tap is asked for (tap).
   */
  function putFile(a) {
    const upload=uploads.get(a.token);
    if (!upload || upload.total<0 || upload.parts.length!==upload.total || [...upload.parts].some(p=>!p)) throw Error('첨부할 파일을 아직 다 받지 못했어요. 다시 해 주세요.');
    const file=new File(upload.parts, String(a.name||'upload'), {type:String(a.type||'application/octet-stream')}); uploads.delete(a.token);
    if (a.document && a.document!==documentId) throw stale('페이지가 바뀌었어요. 다시 읽어 주세요.');
    const clicked=refs.get(a.ref)?.element;
    const fields=all('input[type="file"]').filter(i=>!i.disabled);
    const box=clicked && (clicked.closest('[role="dialog"],dialog,form') || clicked.parentElement);
    const input=(clicked && clicked.matches('input[type="file"]') ? clicked : null)
      || (clicked && fields.find(i=>composedContains(clicked,i)))
      || (box && fields.find(i=>composedContains(box,i) && accepts(i,file)))
      || fields.find(i=>accepts(i,file)) || fields[0];
    const put=()=>{ const dt=new DataTransfer(); dt.items.add(file); return dt; };
    if (input) {
      input.files=put().files;
      for (const type of ['input','change']) input.dispatchEvent(new Event(type,{bubbles:true,composed:true}));
      return {ok:true, document:documentId, attached:file.name, size:file.size, via:'file_field', action_executed:true, action_accepted:true};
    }
    if (clicked) {
      const dt=put(); for (const type of ['dragenter','dragover','drop']) clicked.dispatchEvent(new DragEvent(type,{bubbles:true,cancelable:true,composed:true,dataTransfer:dt}));
      return {ok:true, document:documentId, attached:file.name, size:file.size, via:'drop', action_executed:true, action_accepted:true};
    }
    throw Error('이 페이지에서 파일을 넣을 곳을 찾지 못했어요. 업로드 버튼이 보이는 화면에서 다시 해 주세요.');
  }
  // Keys press takes (BrowserTools.key): KeyboardEvent key, code and keyCode. Control+ or Meta+ goes before a, c, v, x, z or Enter.
  const KEYS={Enter:['Enter','Enter',13],Escape:['Escape','Escape',27],Tab:['Tab','Tab',9],ArrowUp:['ArrowUp','ArrowUp',38],ArrowDown:['ArrowDown','ArrowDown',40],
    ArrowLeft:['ArrowLeft','ArrowLeft',37],ArrowRight:['ArrowRight','ArrowRight',39],Backspace:['Backspace','Backspace',8],Delete:['Delete','Delete',46],
    Space:[' ','Space',32],Home:['Home','Home',36],End:['End','End',35],PageUp:['PageUp','PageUp',33],PageDown:['PageDown','PageDown',34]};
  const COMBO={a:['a','KeyA',65],c:['c','KeyC',67],v:['v','KeyV',86],x:['x','KeyX',88],z:['z','KeyZ',90],Enter:['Enter','Enter',13]};
  function keyOf(text) {
    const m=/^(Control|Meta)\+(a|c|v|x|z|Enter)$/.exec(String(text||''));
    const k=m ? COMBO[m[2]] : Object.hasOwn(KEYS,String(text)) ? KEYS[text] : null;
    return k ? {key:k[0],code:k[1],keyCode:k[2],mod:m?m[1]:'',name:m?m[2]:String(text)} : null;
  }
  /** What has focus on the page (inside open shadow roots too), never a protected field: else the page itself. */
  function focused() {
    let e=document.activeElement;
    while (e && e.shadowRoot && e.shadowRoot.activeElement) e=e.shadowRoot.activeElement;
    return e && e!==document.body && e!==document.documentElement && !secret(e) && !protectedComment(e,protectedRegions()) ? e : (document.body || document.documentElement);
  }
  const textField = e => !!e?.matches?.('textarea,input:not([type]),input[type="text"],input[type="search"],input[type="email"],input[type="url"],input[type="tel"],input[type="number"]');
  const fieldOf = e => textField(e) || !!e && editable(e);
  const activatable = e => !!e?.matches?.('button,summary,input[type="checkbox"],input[type="radio"],input[type="button"],input[type="submit"],input[type="reset"],[role="button"],[role="checkbox"],[role="switch"],[role="radio"],[role="option"],[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"],[role="tab"]');
  /** A page key's scroll (PageDown, Space, an arrow, Home/End) on what scrolls (an open dialog's part first, scroller). */
  function keyScroll(down, how) {
    const box=scroller(down), page=document.scrollingElement || document.documentElement, was=box ? box.scrollTop : scrollY;
    const step=(down?1:-1)*(how==='line' ? 40 : (box ? box.clientHeight : innerHeight)*.8);
    if (how==='edge') { const to=down ? (box||page).scrollHeight : 0; if (box) box.scrollTop=to; else scrollTo({top:to,behavior:'instant'}); }
    else if (box) box.scrollBy({top:step,behavior:'instant'}); else scrollBy({top:step,behavior:'instant'});
    const now=box ? box.scrollTop : scrollY;
    return {moved:now!==was, what:box ? (topDialog() && composedContains(topDialog(),box) ? 'dialog' : 'panel') : 'page'};
  }
  /** The caret in a field: by a letter (ArrowLeft/Right), to the line's start or end (Home/End), a line up or down in an editor. */
  function caret(e, k) {
    const back=['ArrowLeft','ArrowUp','Home'].includes(k.name);
    if (editable(e)) {
      const s=e.getRootNode().getSelection?.() || document.getSelection();
      s?.modify?.('move', back?'backward':'forward', k.name.startsWith('Arrow') ? (['ArrowUp','ArrowDown'].includes(k.name)?'line':'character') : 'lineboundary');
      return 'caret_moved';
    }
    if (e.tagName==='TEXTAREA' && ['ArrowUp','ArrowDown'].includes(k.name)) return '';
    try {
      const at=k.name==='Home' ? 0 : k.name==='End' ? e.value.length : Math.max(0, Math.min(e.value.length, (back ? e.selectionStart-1 : e.selectionEnd+1)));
      e.setSelectionRange(at, at); return 'caret_moved';
    } catch (_) { return ''; }
  }
  function selected(e) {
    if (textField(e)) { try { return e.value.slice(e.selectionStart, e.selectionEnd); } catch (_) { return e.value; } }
    return String((e.getRootNode?.().getSelection?.() || document.getSelection())?.toString() || '');
  }
  /**
   * A key as the page's own key events (keydown, keyup; Control/Meta set for a combination), then what the browser would do
   * that a script's key event does not do by itself: Enter submits or presses, Tab moves focus, Backspace/Delete delete,
   * Space types a space or presses, an arrow moves the caret or the choice in a list or scrolls, Home/End/PageUp/PageDown
   * move or scroll, Control+a selects all, Control+c/x copy (and cut) for this task, Control+v pastes what it copied (a.paste,
   * the app's, never the phone's clipboard), Control+z undoes. A page that handles the key itself (preventDefault) keeps it.
   */
  function press(e, k, a) {
    const ctrl=k.mod==='Control', meta=k.mod==='Meta', page=e===document.body || e===document.documentElement;
    if (!page) e.focus();
    const form=e.form; let submitted=false, effect='', copied, scroll;
    const mark=()=>{submitted=true}; form?.addEventListener('submit',mark,true);
    const init={key:k.key,code:k.code,keyCode:k.keyCode,which:k.keyCode,ctrlKey:ctrl,metaKey:meta,bubbles:true,composed:true};
    let accepted;
    try {
      accepted=e.dispatchEvent(new KeyboardEvent('keydown',{...init,cancelable:true}));
      if (accepted && !k.mod) {
        if (k.name==='Enter' && !submitted && e.matches?.('input') && form) { form.requestSubmit(); effect='submitted'; }
        else if (k.name==='Enter' && !submitted && e.matches?.('button,a[href]')) { e.click(); effect='clicked'; }
        else if (['Backspace','Delete'].includes(k.name) && fieldOf(e)) effect=document.execCommand(k.name==='Backspace'?'delete':'forwardDelete') ? 'deleted' : '';
        else if (k.name==='Space' && fieldOf(e)) effect=document.execCommand('insertText',false,' ') ? 'typed' : '';
        else if (k.name==='Space' && activatable(e)) { e.click(); effect='clicked'; }
        else if (k.name==='Space') { scroll=keyScroll(true,'page'); effect='scrolled'; }
        else if (['ArrowUp','ArrowDown'].includes(k.name) && e.tagName==='SELECT' && !e.multiple) {
          let i=e.selectedIndex; const step=k.name==='ArrowDown'?1:-1;
          do i+=step; while (e.options[i] && e.options[i].disabled);
          if (e.options[i]) { e.selectedIndex=i; e.dispatchEvent(new Event('input',{bubbles:true,composed:true})); e.dispatchEvent(new Event('change',{bubbles:true,composed:true})); effect='option_changed'; }
        }
        else if (k.name.startsWith('Arrow') || ['Home','End'].includes(k.name)) {
          if (fieldOf(e)) effect=caret(e,k);
          if (!effect && !fieldOf(e)) {
            if (['ArrowLeft','ArrowRight'].includes(k.name)) { const was=scrollX; scrollBy({left:k.name==='ArrowRight'?40:-40,behavior:'instant'}); scroll={moved:scrollX!==was,what:'page'}; }
            else scroll=keyScroll(['ArrowDown','End'].includes(k.name), ['Home','End'].includes(k.name) ? 'edge' : 'line');
            effect='scrolled';
          }
        }
        else if (['PageUp','PageDown'].includes(k.name) && !textField(e)) { scroll=keyScroll(k.name==='PageDown','page'); effect='scrolled'; }
      } else if (accepted) {
        if (k.name==='a') {
          if (textField(e)) e.select();
          else { const s=document.getSelection(), r=document.createRange(); r.selectNodeContents(editable(e) ? e : document.body || document.documentElement); s.removeAllRanges(); s.addRange(r); }
          effect='selected_all';
        } else if (k.name==='c' || k.name==='x') {
          copied=selected(e).slice(0,100000); // as much as a type may put back (BrowserTools.TYPE_LIMIT)
          try { document.execCommand('copy'); } catch (_) { }
          if (k.name==='x' && copied && fieldOf(e)) document.execCommand('delete');
          effect=copied ? (k.name==='x' ? 'cut' : 'copied') : 'nothing_selected';
        } else if (k.name==='v') {
          const words=String(a.paste||'');
          effect=!words ? 'nothing_to_paste' : fieldOf(e) && document.execCommand('insertText',false,words) ? 'pasted' : 'no_field';
        } else if (k.name==='z') effect=document.execCommand('undo') ? 'undone' : '';
      }
    } finally { form?.removeEventListener('submit',mark,true); }
    if (accepted && !k.mod && k.name==='Tab') { const regions=protectedRegions(); const fields=all('a[href],button,input,textarea,select,[tabindex]').filter(x=>inViewport(x)&&!secret(x)&&!protectedComment(x,regions)&&!x.disabled); const next=fields[fields.indexOf(e)+1]; if(next) { next.focus(); effect='focus_moved'; } }
    e.dispatchEvent(new KeyboardEvent('keyup',init));
    return {accepted, effect:accepted ? effect : 'page_handled', copied, scroll};
  }
  async function perform(a, acknowledge=()=>{}) {
    let input, target, scrolled=null, pressed=null;
    if (a.action === 'vault_fill') {
      if (location.origin !== a.origin || !location.href.startsWith('https:')) throw Error('저장된 로그인 주소와 현재 사이트가 달라요.');
      const fields = [...document.querySelectorAll('input')].filter(visible), password = fields.find(e=>e.type==='password');
      if (!password) throw Error('현재 페이지에 비밀번호 입력칸이 없어요.');
      const username = fields.find(e=>!secret(e) && (e.type==='email' || e.autocomplete==='username')) || fields.find(e=>!secret(e)&&e.type==='text');
      if (username) fill(username, a.username); fill(password, a.password);
      return {ok:true, filled:true}; // Never return credentials, and never submit automatically.
    }
    if (loginPage()) return snapshot();
    if (a.action === 'read') return snapshot();
    if (a.action === 'put_file') return putFile(a);
    if (accountChooser() && !['click','scroll'].includes(a.action)) throw Error('계정 선택 화면에서는 로그인된 계정을 고르는 것만 할 수 있어요.');
    // Do not regenerate refs before validating the caller's existing reference.
    target=refs.get(a.ref)?.element;
    let tap=null;
    const before=renderState(target);
    if (a.action === 'scroll') {
      // A named element is brought to the middle of the screen; otherwise the part that can move moves (see scroller).
      const named=a.ref ? refs.get(a.ref)?.element : null;
      if (named && named.isConnected && a.document===documentId) { named.scrollIntoView({block:'center'}); scrolled={moved:true, what:'element'}; }
      else {
        const down=a.direction!=='up', box=scroller(down);
        if (box) { const was=box.scrollTop; box.scrollBy({top:(down?1:-1)*box.clientHeight*.8,behavior:'instant'}); scrolled={moved:box.scrollTop!==was, what:topDialog() && composedContains(topDialog(),box) ? 'dialog' : 'panel'}; }
        else { const was=scrollY; scrollBy({top:(down?1:-1)*innerHeight*.8,behavior:'instant'}); scrolled={moved:scrollY!==was, what:'page'}; }
      }
    }
    else {
      if (a.document !== documentId) throw stale('페이지가 바뀌었어요. 다시 읽어 주세요.');
      // A key with no ref goes to what has focus on the page (a page key: PageDown, Control+a), never a protected field (focused).
      const free=a.action==='press' && !a.ref;
      const entry=free ? null : refs.get(a.ref), e=free ? focused() : entry?.element;
      if (!free && e && e.isConnected && authRegions().some(r => composedContains(r.box, e) || e.form && (e.form===r.box || composedContains(r.box, e.form))))
        throw stale('로그인·인증 입력 영역이라 누르거나 입력하지 않았어요. 이 부분은 사용자가 직접 해야 해요. 페이지의 나머지는 다시 읽어서 이어 갈 수 있어요.');
      if (!free && (!e || !e.isConnected || !visible(e) || e.disabled || (e.readOnly && a.action!=='press') || secret(e) || protectedComment(e,protectedRegions()) || fingerprint(e)!==entry.fingerprint)) throw stale('요소가 바뀌었거나 입력할 수 없어요. 다시 읽어 주세요.');
      const proposal=free ? null : navigationProposal(a,e);
      if (proposal) return {ok:true,document:documentId,navigation_proposal:proposal,action_executed:false};
      // A file is ready for this page's chooser (attach): the app taps it for real, since a file chooser opens only for a real touch.
      if (a.action==='click' && a.native_tap) {
        e.scrollIntoView({block:'center'});
        const r=e.getBoundingClientRect(), vv=window.visualViewport, s=vv?vv.scale:1, d=window.devicePixelRatio||1;
        tap={x:Math.round((r.left+r.width/2-(vv?vv.offsetLeft:0))*s*d), y:Math.round((r.top+r.height/2-(vv?vv.offsetTop:0))*s*d)};
      }
      else if (a.action==='click') { e.scrollIntoView({block:'center'}); e.click(); }
      else if(a.action==='type') {
        if(!e.matches('input,textarea') && !editable(e)) throw Error('입력칸이 아니에요.');
        input={element:e,text:String(a.text || '')}; fill(e,input.text);
      }
      else if(a.action==='select') { if(e.tagName!=='SELECT' || ![...e.options].some(o=>o.value===a.text)) throw Error('목록에 없는 값이에요.');input={element:e,text:a.text};fill(e,a.text); }
      else if(a.action==='press') {
        const k=keyOf(a.text);
        if(!k) throw Error('지원하지 않는 키예요.');
        pressed=press(e,k,a);
      }
      else throw Error('지원하지 않는 페이지 동작이에요.');
    }
    // Click/Enter may unload this document before the delayed snapshot. Confirm
    // dispatch immediately; the native executor still awaits a separate page read.
    // Input/select retain their post-render verification before acknowledgement.
    if(['click','press'].includes(a.action))acknowledge({action:a.action,document:documentId,action_accepted:true,...(tap?{tap}:{})});
    const settled=await settle(target);
    // Controlled fields may restore their previous value on the next render. Verify
    // after page handlers settle, without echoing the entered text into observations.
    if(input && (!input.element.isConnected || !retains(input.element,input.text))) {
      return {...snapshot(),ok:false,action_accepted:false,input_verified:false,requires_user:true,
        error:'입력한 내용이 편집기에 유지되지 않았어요. 반복 입력이나 제출을 멈췄습니다. 화면에서 입력칸을 확인해 주세요.'};
    }
    return {...snapshot(), action_accepted:true,settle:settled.info,
      action_effect:actionEffect(a.action,before,settled.state,settled.info,input),...(input?{input_verified:true,characters:input.text.length}:{}),
      ...(pressed?{key:a.text,key_effect:pressed.effect,...(pressed.copied!==undefined?{copied:pressed.copied}:{})}:{}),
      ...(pressed?.scroll && !scrolled?{scroll:pressed.scroll.moved?pressed.scroll:{...pressed.scroll,note:'Nothing moved: there is no more to scroll this way.'}}:{}),
      ...(scrolled?{scroll:scrolled.moved?scrolled:{...scrolled,note:'Nothing moved: there is no more to scroll this way. Controls off screen are already listed with offscreen:true; click or type them directly.'}}:{})};
  }
  function fingerprint(e) { return [e.tagName,e.type,e.getAttribute('href'),e.getAttribute('aria-label'),e.innerText,e.getAttribute('placeholder'),e.getAttribute('role'),e.getAttribute('aria-selected'),e.getAttribute('aria-current'),e.getAttribute('aria-checked'),e.checked,e.readOnly].join('\u001f'); }
  function fill(e, value) {
    e.focus();
    if(editable(e)) {
      // Use Gecko's editing transaction so rich editors receive a real input event
      // and retain their document/undo state. Replacing textContent bypasses that.
      const range=document.createRange();range.selectNodeContents(e);
      const selection=e.getRootNode().getSelection?.() || document.getSelection();
      selection.removeAllRanges();selection.addRange(range);
      if(!e.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,composed:true,cancelable:true,inputType:'insertText',data:value}))) return;
      if(typeof document.execCommand!=='function' || !document.execCommand('insertText',false,value))
        throw Error('이 편집기가 텍스트 입력을 받지 않았어요. 화면에서 입력칸을 확인해 주세요.');
    } else {
      const select=e.tagName==='SELECT';
      if(!select && !e.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,composed:true,cancelable:true,inputType:'insertReplacementText',data:value}))) return;
      const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:select?HTMLSelectElement.prototype:HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto,'value').set.call(e,value);
      e.dispatchEvent(select?new Event('input',{bubbles:true,composed:true}):new InputEvent('input',{bubbles:true,composed:true,inputType:'insertReplacementText',data:value}));
      e.dispatchEvent(new Event('change',{bubbles:true,composed:true}));
    }
  }
  let originalZoom, originalZoomPriority, baseZoom = 1;
  // Page zoom as Chrome does it (준: "같은 페이지배율 80퍼 보기인데 크롬에 비해 우리껀 너무 엉성해"): a mobile page is laid out
  // for a screen wider (or narrower) by the zoom and drawn to fit, through the page's own viewport line, so 100vh still fills
  // the screen and nothing is cut at the side; CSS zoom (the page shrunk as drawn, its full-height parts short and its side
  // panels cut) is kept only for 데스크톱 보기, where the viewport line is not read. The site's own line comes back at 100%
  // and is put back to ours when the page rewrites it.
  let zoomOriginalMeta, zoomContent = '', zoomWatch = null;
  function viewportMeta() { return document.querySelector('meta[name="viewport"]'); }
  function cssZoom(percent) {
    const root = document.documentElement;
    if (!root || !CSS.supports('zoom', '1')) throw Error('이 페이지는 확대·축소를 지원하지 않아요.');
    if (originalZoom === undefined) {
      originalZoom = root.style.getPropertyValue('zoom');
      originalZoomPriority = root.style.getPropertyPriority('zoom');
      baseZoom = parseFloat(getComputedStyle(root).zoom) || 1;
    }
    // Preserve the site's authored zoom at reset. CSS zoom recalculates layout,
    // unlike transform:scale; it does not touch fields, cookies or history.
    if (percent === 100) {
      if (originalZoom) root.style.setProperty('zoom', originalZoom, originalZoomPriority);
      else root.style.removeProperty('zoom');
    } else root.style.setProperty('zoom', String(baseZoom * percent / 100), 'important');
  }
  function viewportZoom(percent) {
    let meta = viewportMeta();
    if (zoomOriginalMeta === undefined) zoomOriginalMeta = meta ? meta.getAttribute('content') || '' : null;
    if (zoomWatch) { zoomWatch.disconnect(); zoomWatch = null; }
    if (percent === 100) {
      zoomContent = '';
      if (zoomOriginalMeta === null) { if (meta && meta.dataset.gyeotZoom) meta.remove(); }
      else if (meta) meta.setAttribute('content', zoomOriginalMeta);
      return;
    }
    if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; meta.dataset.gyeotZoom = '1'; (document.head || document.documentElement).appendChild(meta); }
    const scale = percent / 100, wide = Math.round((screen.width || innerWidth) / scale);
    // The site's own parts that are not about size stay (viewport-fit, interactive-widget …).
    const keep = String(zoomOriginalMeta || '').split(',').map(x => x.trim()).filter(x => x && !/^(width|height|initial-scale|minimum-scale|maximum-scale|user-scalable)\s*=/i.test(x));
    zoomContent = ['width=' + wide, 'initial-scale=' + scale, 'minimum-scale=' + Math.min(scale, 0.25), 'user-scalable=yes'].concat(keep).join(', ');
    meta.setAttribute('content', zoomContent);
    // A page that writes its own viewport line again (a single-page app moving on) gets ours back.
    zoomWatch = new MutationObserver(() => {
      if (!zoomContent) return;
      let m = viewportMeta();
      if (!m) { m = document.createElement('meta'); m.name = 'viewport'; m.dataset.gyeotZoom = '1'; (document.head || document.documentElement).appendChild(m); }
      if (m.getAttribute('content') !== zoomContent) m.setAttribute('content', zoomContent);
    });
    zoomWatch.observe(document.head || document.documentElement, {subtree: true, childList: true, attributes: true, attributeFilter: ['content', 'name']});
  }
  // 데스크톱 보기: a page at its top stays at its top when the page area changes size (준: "데스크탑모드에서 위가 잘려"). The
  // page is drawn shrunk to fit its 980px width; when the area changes (the keyboard closed after typing the address, the AI
  // panel opened or folded beside it, the split), the phone kept the middle of what was seen in place, so the page's top went
  // under the toolbar, out of reach on a page that does not scroll (google.com). Only a page that was at its top and that the
  // user has not moved since; a keyboard opening (the area only shorter) is left to the page and its field.
  let desktopView = false, viewTop = 0, viewW = 0, viewH = 0;
  const viewport = window.visualViewport;
  function viewSize() { return viewport ? [viewport.width * viewport.scale, viewport.height * viewport.scale] : [innerWidth, innerHeight]; }
  function viewNote() { const [w, h] = viewSize(); viewW = w; viewH = h; viewTop = viewport ? viewport.pageTop : scrollY; }
  function viewChanged() {
    const [w, h] = viewSize(), was = viewTop, top = viewport ? viewport.pageTop : scrollY;
    if (desktopView && was <= 1 && (Math.abs(w - viewW) > 1 || h > viewH + 1) && top > 1 && top < 400) {
      try { document.documentElement.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' }); } catch (e) {}
      if (scrollY) scrollTo(scrollX, 0);
    }
    viewNote();
  }
  if (viewport) {
    viewNote();
    viewport.addEventListener('resize', viewChanged);
    // A scroll that comes with a new size is the size change's (it may come first); any other is the user's or the page's.
    const moved = () => { const [w, h] = viewSize(); if (Math.abs(w - viewW) > 1 || Math.abs(h - viewH) > 1) viewChanged(); else viewNote(); };
    viewport.addEventListener('scroll', moved); addEventListener('scroll', moved, { passive: true });
  }
  function pageZoom(percent, desktop) {
    if (!Number.isInteger(percent) || percent < 25 || percent > 300) throw Error('확대 비율은 25~300%예요.');
    if (!document.documentElement) throw Error('이 페이지는 확대·축소를 지원하지 않아요.');
    desktopView = !!desktop;
    if (desktop) { if (zoomContent) viewportZoom(100); cssZoom(percent); }
    else { if (originalZoom !== undefined) cssZoom(100); viewportZoom(percent); }
    refs = new Map(); revision++;
  }
  // 마이튜브's player (only when the app asks, in its own player session): the page shows only the video (mytube.css under
  // html.gyeot-mytube), YouTube's own player is started (a video element alone can still be empty before its first play),
  // the chosen picture quality is applied, and the page tells when it plays, pauses and ends (an ad's end is not the
  // video's), or that it did not start, and why.
  let mytubeBrowse=false, mytubeOn=false, mytubeVideo=null, mytubeWant='play', mytubeQuality='auto', mytubeFor=null, mytubeStarted=false,
    mytubeSince=0, mytubeStuck=false, mytubeShown='', mytubeQualityFor='', mytubeWhy='', mytubeTouch=0, mytubeWaitSince=0, mytubeWaitSaid=false;
  // A touch on the page (YouTube's own controls): a pause right after one is the user's, any other the page's own.
  function mytubeTouched() { mytubeTouch=Date.now(); }
  // Seconds of video loaded ahead of where it plays (0 when nothing is): how a stall is told from a stop.
  function mytubeAhead(v) { try { for (let i=0; i<v.buffered.length; i++) if (v.buffered.start(i)<=v.currentTime+0.5 && v.buffered.end(i)>=v.currentTime) return Math.max(0, v.buffered.end(i)-v.currentTime); } catch(_) {} return 0; }
  function mytubeState(v) { return {ready:v?v.readyState:-1, net:v?v.networkState:-1, ahead:v?Math.round(mytubeAhead(v)*10)/10:0, why:mytubeWhy}; }
  const MYTUBE_ORDER=['highres','hd2880','hd2160','hd1440','hd1080','hd720','large','medium','small','tiny'];
  function mytubeSay(connection, event, more) {
    const v=mytubeVideo;
    try { connection.postMessage({mytube:{event, url:location.href, time:v?v.currentTime||0:0, duration:v&&isFinite(v.duration)?v.duration:0, title:document.title, ...(more||{})}}); } catch(_) {}
  }
  function mytubeAd() { return !!document.querySelector('.ad-showing, .ad-interrupting'); }
  function mytubeId() {
    try { const u=new URL(location.href); return u.searchParams.get('v') || (u.pathname.match(/^\/shorts\/([\w-]{6,})/)||[])[1] || ''; } catch(_) { return ''; }
  }
  // The page's own player (its functions live in the page, past the isolated world).
  function mytubeApi() { const p=document.getElementById('movie_player'); if(!p) return null; try { return p.wrappedJSObject || p; } catch(_) { return p; } }
  function mytubeCall(api, name, ...args) { try { return api && typeof api[name]==='function' ? api[name](...args) : undefined; } catch(_) { return undefined; } }
  function mytubeLevels(api) { const l=mytubeCall(api,'getAvailableQualityLevels'); const out=[]; try { for (let i=0; l && i<l.length; i++) out.push(String(l[i])); } catch(_) {} return out; }
  // The wished quality, or the best one under it this video has (최고: the best it has).
  function mytubeLevel(levels, wish) {
    const known=levels.filter(l=>MYTUBE_ORDER.includes(l)).sort((a,b)=>MYTUBE_ORDER.indexOf(a)-MYTUBE_ORDER.indexOf(b));
    if (!known.length) return '';
    if (wish==='max') return known[0];
    const at=MYTUBE_ORDER.indexOf(wish); if (at<0) return '';
    return known.find(l=>MYTUBE_ORDER.indexOf(l)>=at) || known[known.length-1];
  }
  function mytubeApplyQuality(connection) {
    const api=mytubeApi(); if (!api) return;
    const levels=mytubeLevels(api);
    if (mytubeQuality==='auto') mytubeCall(api,'setPlaybackQualityRange','auto','auto');
    else { const q=mytubeLevel(levels, mytubeQuality); if (q) { mytubeCall(api,'setPlaybackQualityRange',q,q); mytubeCall(api,'setPlaybackQuality',q); } }
    mytubeQualityFor=mytubeFor; mytubeReport(connection);
  }
  function mytubeReport(connection) {
    const api=mytubeApi(); if (!api) return;
    const now=String(mytubeCall(api,'getPlaybackQuality')||''), levels=mytubeLevels(api), said=now+'|'+levels.join(',');
    if (said!==mytubeShown) { mytubeShown=said; mytubeSay(connection,'quality',{quality:now, levels, settable:typeof api.setPlaybackQualityRange==='function'}); }
  }
  function mytubeStart(v) {
    const api=mytubeApi();
    if (api) {
      if (mytubeCall(api,'isMuted')) mytubeCall(api,'unMute');
      const state=mytubeCall(api,'getPlayerState');
      if (state!==1 && state!==3) mytubeCall(api,'playVideo');
    }
    if (v && v.paused && !v.ended && v.readyState>0) { v.muted=false; const p=v.play(); if (p && p.catch) p.catch(e=>{ mytubeWhy=String((e&&e.name)||e); }); }
  }
  function mytubeAttach(connection) {
    const id=mytubeId();
    if (id!==mytubeFor) { mytubeFor=id; mytubeStarted=false; mytubeStuck=false; mytubeShown=''; mytubeSince=Date.now(); mytubeWhy=''; }
    // Only the video, filling the app's player: on a watch page (a sign-in page stays whole).
    document.documentElement.classList.toggle('gyeot-mytube', !!id);
    const v=document.querySelector('video');
    if (v && v!==mytubeVideo) {
      mytubeVideo=v;
      v.addEventListener('ended',()=>{ if(!mytubeAd()) mytubeSay(connection,'ended'); });
      v.addEventListener('playing',()=>{
        mytubeStarted=true;
        if (!mytubeAd() && mytubeQualityFor!==mytubeFor) mytubeApplyQuality(connection);
        mytubeSay(connection,'playing',{ad:mytubeAd()});
      });
      // self: the page stopped by itself while the app wanted it playing (not the app's pause, not a touch on the page),
      // as when the video's data stops coming; the app plays it again (MyTubePlayer).
      v.addEventListener('pause',()=>{ if(!v.ended) mytubeSay(connection,'paused',{ad:mytubeAd(), self:mytubeWant==='play' && Date.now()-mytubeTouch>1500, ...mytubeState(v)}); });
      v.addEventListener('playing',()=>{ mytubeWaitSince=0; mytubeWaitSaid=false; });
      v.addEventListener('error',()=>mytubeSay(connection,'error'));
    }
    // Already playing when the listener came (YouTube started it by itself first): told now, so the app does not wait for a
    // 'playing' that has passed and call it stuck.
    if (!mytubeStarted) {
      const api=mytubeApi(), state=api?mytubeCall(api,'getPlayerState'):null;
      if ((v && !v.paused && !v.ended && v.readyState>2) || state===1) {
        mytubeStarted=true; mytubeStuck=false;
        if (!mytubeAd() && mytubeQualityFor!==mytubeFor) mytubeApplyQuality(connection);
        mytubeSay(connection,'playing',{ad:mytubeAd()});
      }
    }
    // Playing but waiting for data (the round spinner) for 12 seconds: told once, with how much is loaded.
    if (v && mytubeWant==='play' && mytubeStarted && !v.paused && !v.ended && v.readyState<3) {
      if (!mytubeWaitSince) mytubeWaitSince=Date.now();
      else if (!mytubeWaitSaid && Date.now()-mytubeWaitSince>12000) { mytubeWaitSaid=true; mytubeSay(connection,'stalling',mytubeState(v)); }
    } else if (v && v.readyState>=3) { mytubeWaitSince=0; mytubeWaitSaid=false; }
    if (mytubeWant!=='play' || mytubeStarted) { if (mytubeStarted && !mytubeAd()) mytubeReport(connection); return; }
    mytubeStart(v);
    if (!mytubeStuck && Date.now()-mytubeSince>15000) {
      mytubeStuck=true;
      const api=mytubeApi();
      mytubeSay(connection,'stuck',{page:location.hostname+location.pathname, player:!!api, state:api?mytubeCall(api,'getPlayerState'):null, video:!!v, ready:v?v.readyState:-1, why:mytubeWhy});
    }
  }
  function mytube(message, connection) {
    const command=typeof message==='string' ? message : message && message.command;
    if (message && typeof message==='object' && typeof message.quality==='string') mytubeQuality=message.quality;
    // A YouTube page in the app's player (a channel, Shorts): a Short drops YouTube's top bar so it fills the player.
    if (command==='browse') {
      const mark=()=>document.documentElement.classList.toggle('gyeot-shorts', /^\/shorts\//.test(location.pathname));
      mark(); if(!mytubeBrowse){mytubeBrowse=true;setInterval(mark,700);} return;
    }
    if (command==='watch') {
      mytubeWant='play'; mytubeSince=Date.now(); mytubeStuck=false;
      if(!mytubeOn){mytubeOn=true;setInterval(()=>mytubeAttach(connection),700);document.addEventListener('pointerdown',mytubeTouched,true);document.addEventListener('touchstart',mytubeTouched,true);} mytubeAttach(connection); return;
    }
    const v=document.querySelector('video');
    if (command==='play') { mytubeWant='play'; mytubeStarted=false; mytubeSince=Date.now(); mytubeStuck=false; mytubeStart(v); }
    else if (command==='pause') { mytubeWant='pause'; mytubeCall(mytubeApi(),'pauseVideo'); if(v) v.pause(); }
    else if (command==='quality') mytubeApplyQuality(connection);
    else if (command==='account') mytubeAccount(connection, 0);
    else if (command==='frame') mytubeFrame(connection);
    // 앞뒤 이동 (seconds from where it plays, or to a second: 영상 요약's times) and 배속 (0.25–2, kept for the next videos: the app sends it again as each one plays).
    else if (command==='seek') {
      const api=mytubeApi(), by=Number(message.by)||0, now=v?v.currentTime:(Number(mytubeCall(api,'getCurrentTime'))||0), to=Math.max(0,typeof message.to==='number'?message.to:now+by);
      if (api && typeof api.seekTo==='function') mytubeCall(api,'seekTo',to,true); else if (v) v.currentTime=to;
    }
    else if (command==='rate') {
      const r=Math.min(2,Math.max(0.25,Number(message.rate)||1));
      mytubeCall(mytubeApi(),'setPlaybackRate',r); if (v) v.playbackRate=r;
    }
  }
  // The frame on screen now, at the video's own size (JPEG); the app saves it in 들퍼레/마이튜브 캡처.
  function mytubeFrame(connection) {
    const v=document.querySelector('video');
    if (!v || !v.videoWidth || !v.videoHeight) { mytubeSay(connection,'frame',{error:'영상이 아직 화면에 없어요'}); return; }
    try {
      const c=document.createElement('canvas'); c.width=v.videoWidth; c.height=v.videoHeight;
      c.getContext('2d').drawImage(v,0,0,c.width,c.height);
      const data=c.toDataURL('image/jpeg',0.95), at=data.indexOf(',');
      if (at<0 || data.length<2000) { mytubeSay(connection,'frame',{error:'장면을 그리지 못했어요'}); return; }
      mytubeSay(connection,'frame',{data:data.slice(at+1), width:c.width, height:c.height, ad:mytubeAd()});
    } catch(e) { mytubeSay(connection,'frame',{error:String((e&&e.name)||e)}); }
  }
  // Whether YouTube is signed in on this page (its own page settings say so), asked again for a few seconds while they load.
  // YouTube's own setting first; else its sign-in cookie (YouTube's scripts read it too), which only a signed-in browser holds.
  function mytubeAccount(connection, tries) {
    let known=false, loggedIn=false;
    try {
      const page=window.wrappedJSObject || window, cfg=page.ytcfg;
      const value=cfg && (typeof cfg.get==='function' ? cfg.get('LOGGED_IN') : (cfg.data_ && cfg.data_.LOGGED_IN));
      if (typeof value==='boolean') { known=true; loggedIn=value; }
    } catch(_) {}
    const youtube=/(^|\.)youtube\.com$/.test(location.hostname);
    let cookie=false; try { cookie=/(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID|__Secure-1PAPISID)=/.test(document.cookie); } catch(_) {}
    if (!known && youtube && cookie) { known=true; loggedIn=true; }
    if (!known && tries<10) { setTimeout(()=>mytubeAccount(connection, tries+1), 500); return; }
    if (!known && youtube) known=true;
    mytubeSay(connection,'account',{known, loggedIn});
  }
  function connect() {
  const connection=browser.runtime.connectNative('gyeot_browser'); port=connection;
  connection.onMessage.addListener(async message => {
    if (Object.hasOwn(message, 'mytube')) { mytube(message.mytube, connection); return; }
    if (Object.hasOwn(message, 'page_zoom')) {
      try { pageZoom(message.page_zoom, !!message.desktop); connection.postMessage({page_zoom:true,ok:true,percent:message.page_zoom}); }
      catch(e) { connection.postMessage({page_zoom:true,ok:false,error:tidy(e.message,250)}); }
      return;
    }
    // A file the app sends for put_file, piece by piece: kept under its token (only the newest two), never an action itself.
    if (Object.hasOwn(message, 'upload_chunk')) {
      const piece=message.upload_chunk; let upload=uploads.get(piece.token);
      if (!upload) { upload={parts:[],total:-1}; uploads.set(piece.token,upload); while (uploads.size>2) uploads.delete(uploads.keys().next().value); }
      const text=atob(String(piece.data||'')), bytes=new Uint8Array(text.length);
      for (let i=0;i<text.length;i++) bytes[i]=text.charCodeAt(i);
      // Kept as a Blob piece: the File made of them later reuses these pieces instead of copying a 120 MB video once more.
      upload.parts[piece.index]=new Blob([bytes]); return;
    }
    if (Object.hasOwn(message, 'upload_done')) { const upload=uploads.get(message.upload_done.token); if (upload) upload.total=message.upload_done.total; return; }
    if (working) { connection.postMessage({id:message.id,result:{ok:false,error:'이전 동작을 처리 중이에요.'}}); return; }
    working=true;
    try { connection.postMessage({id:message.id,result:await perform(message.args,receipt=>{
      try { connection.postMessage({action_receipt:{id:message.id,...receipt}}); } catch(_) { }
    })}); }
    catch(e) { try { connection.postMessage({id:message.id,result:{ok:false,error:tidy(e.message,250),...(e.staleObservation?{stale_observation:true}:{})}}); } catch(_) {} }
    finally { working=false; }
  });
  connection.onDisconnect?.addListener(()=>{ if(port!==connection)return;port=null;if(!away && retries++<3)setTimeout(()=>{if(!away&&!port)connect()},500); });
  connection.postMessage({ready:true,document:documentId});
  }
  addEventListener('pagehide',()=>{away=true});
  // A page restored from the back-forward cache gets a fresh bridge: the native side dropped the old one when it left.
  addEventListener('pageshow',event=>{away=false;retries=0;if(event.persisted&&port){const old=port;port=null;try{old.disconnect()}catch(_){}}if(!port)connect()});
  // At document_end, interactive can mean DOMContentLoaded already fired while
  // slow images are still loading. Do not wait for a past event or window load.
  // Connecting is not a page observation. Existing snapshot, authentication
  // and document/reference checks remain in place.
  const ready=()=>{if(!away&&!port)connect()};
  // Deferred page scripts run just before DOMContentLoaded and often fill the page in (links, states): when it has not
  // begun yet, wait for it (or a short while), so the first read sees the page as its scripts left it.
  const begun=()=>{try{const n=performance.getEntriesByType('navigation')[0];return !n||n.domContentLoadedEventStart>0}catch(_){return true}};
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',ready,{once:true});
  else if(!begun()){document.addEventListener('DOMContentLoaded',ready,{once:true});setTimeout(ready,1200);}
  else ready();
})();

