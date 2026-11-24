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
    return actual===expected || editable(e) && children.at(-1)?.nodeName==='BR' &&
      (actual===expected+'\n' || expected==='' && children.length===1);
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
  const accountChooser = () => location.hostname === 'accounts.google.com' && /accountchooser|signinchooser/i.test(location.pathname)
    && !all('input[type="password"],input[autocomplete="one-time-code"]').some(visible)
    && !all('iframe[src]').some(e => visible(e) && /recaptcha|hcaptcha|challenges.cloudflare/.test(e.src));
  const guardReason = () => accountChooser() ? '' : location.hostname === 'accounts.google.com' ? 'auth_host'
    : /(?:^|[?&])(?:code|access_token|id_token|oauth_token)=/i.test(location.search) ? 'oauth_callback'
    : all('iframe[src]').some(e => visible(e) && /recaptcha|hcaptcha|challenges.cloudflare/.test(e.src)) ? 'captcha'
    : all('input[autocomplete="one-time-code"]').some(visible) ? 'otp'
    : all('input[type="password"]').some(e=>visible(e) && !commentRegion(e)) ? 'password' : '';
  const loginPage = () => !!guardReason();
  // Read-only, coarse evidence for long-running work. These are page claims,
  // never authorization or proof that a generation has completed. A missing
  // indicator is UNKNOWN: Canvas can keep working without changing visible text.
  function pageActivity() {
    const evidence = [];
    const documentLoading = document.readyState === 'loading';
    if (documentLoading) evidence.push('document_loading');
    const regions=commentRegions();
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
  function snapshot() {
    const guard = guardReason();
    if (guard) { refs = new Map(); revision++; return {ok:true, requires_user:true, error_code:'authentication_required', guard_reason:guard,
      error:guard==='password' ? '비밀번호 입력 영역이 있어 페이지 조작을 멈췄어요. 인증은 직접 진행하거나, 뒤로 가기 또는 공개 HTTPS 홈페이지로 이동을 새로 지시해 주세요.' : '인증·보안 확인은 직접 진행해 주세요. 이 화면에서 나가려면 뒤로 가기 또는 공개 HTTPS 홈페이지로 이동을 새로 지시해 주세요.',
      title:'보호된 입력 · 사용자 확인 필요', url:location.origin, document:documentId, elements:[], text:'보호된 화면의 내용은 AI에게 전달하지 않습니다.'}; }
    if (accountChooser()) return chooserSnapshot();
    refs = new Map(); revision++;
    const elements = [], regions=commentRegions();
    // Menu items, list options and checkboxes too: YouTube Studio's account and channel menu, its category and language
    // lists and its checkboxes are made of these, not of buttons or links.
    for (const e of all('a[href],button,input,textarea,select,[role="button"],[role="link"],[role="combobox"],[role="searchbox"],[role="tab"],[role="switch"],[role="radio"],[role="textbox"],[role="menuitem"],[role="menuitemradio"],[role="menuitemcheckbox"],[role="option"],[role="checkbox"],[role="treeitem"],[contenteditable]:not([contenteditable="false"])')) {
      if (elements.length >= 100) break;
      if (!inViewport(e) || secret(e) || protectedComment(e,regions)) continue;
      const ref = revision + ':' + elements.length;
      refs.set(ref, {element:e, fingerprint:fingerprint(e)});
      const safeLabel = node => node && !protectedComment(node,regions) && !secret(node) ? node.textContent : '';
      const labelled = (e.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean).map(id=>safeLabel(document.getElementById(id))).join(' ').trim();
      // The first source with visible words: a link holding only an icon and a no-break space is named by its title or icon text.
      const label = [labelled, e.getAttribute('aria-label'), safeLabel(e.labels?.[0]), e.innerText, e.getAttribute('placeholder'), e.getAttribute('title'),
        [...e.querySelectorAll('img[alt]')].map(i=>i.getAttribute('alt')).join(' ')].map(v=>tidy(v)).find(Boolean) || '';
      const publicHref = e.tagName === 'A' && !e.hasAttribute('download') && sameTab(e) ? anchorReadUrl(e) : '';
      const navigationKind=e.tagName!=='A' ? '' : publicHref ? 'public_get' : /^javascript:/i.test(e.getAttribute('href') || '') ? 'scripted' : (e.getAttribute('href') || '').startsWith('#') ? 'fragment' : 'other';
      elements.push({...(publicHref ? {href:publicHref} : {}), ...(navigationKind ? {navigation_kind:navigationKind} : {}), ref, tag:e.tagName.toLowerCase(), type:e.type || '', label, disabled:!!e.disabled, role:e.getAttribute('role') || '',
        editable:e.matches('textarea,input:not([type]),input[type="text"],input[type="search"],input[type="email"],input[type="url"]') || editable(e), readonly:!!e.readOnly,
        selected:e.getAttribute('aria-selected')==='true' || e.tagName==='OPTION' && !!e.selected,
        current:['page','step','location','date','time','true'].includes(e.getAttribute('aria-current')),
        // A visual class is a hint, not an ARIA selection or a completed goal.
        selection_hint:[e,e.parentElement].some(n=>n && /(?:^|\s)(?:on|active|selected|current)(?:\s|$)/i.test(n.className || '')),
        checked:e.getAttribute('aria-checked')==='true' || !!e.checked,
        placeholder:tidy(e.getAttribute('placeholder')),
        // An input inside a search landmark is a search box for the automation gate (BrowserDecisionEvidence.fieldKind).
        ...(e.tagName === 'INPUT' && e.closest('[role="search"],search') ? {in_search:true} : {}),
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
      text:chunks.join('\n').slice(0,12000), elements, frames:document.querySelectorAll('iframe').length, activity:pageActivity()};
  }
  // Sample meaningful rendered content rather than waiting for every advertisement,
  // animation or network resource. No page script is patched or evaluated.
  function renderState(target) {
    if (away) throw stale('페이지가 이동 중이에요. 새 페이지를 읽어 주세요.');
    if (loginPage()) return {protected:true, signature:'protected', busy:false};
    const regions=commentRegions(), chunks=[];
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
      return {ok:true, document:documentId, attached:file.name, size:file.size, via:'file_field', action_executed:true};
    }
    if (clicked) {
      const dt=put(); for (const type of ['dragenter','dragover','drop']) clicked.dispatchEvent(new DragEvent(type,{bubbles:true,cancelable:true,composed:true,dataTransfer:dt}));
      return {ok:true, document:documentId, attached:file.name, size:file.size, via:'drop', action_executed:true};
    }
    throw Error('이 페이지에서 파일을 넣을 곳을 찾지 못했어요. 업로드 버튼이 보이는 화면에서 다시 해 주세요.');
  }
  async function perform(a, acknowledge=()=>{}) {
    let input, target;
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
    if (a.action === 'scroll') { scrollBy({top:(a.direction==='up'?-1:1)*innerHeight*.8,behavior:'instant'}); }
    else {
      if (a.document !== documentId) throw stale('페이지가 바뀌었어요. 다시 읽어 주세요.');
      const entry=refs.get(a.ref), e=entry?.element;
      if (!e || !e.isConnected || !visible(e) || e.disabled || e.readOnly || secret(e) || protectedComment(e,commentRegions()) || fingerprint(e)!==entry.fingerprint) throw stale('요소가 바뀌었거나 입력할 수 없어요. 다시 읽어 주세요.');
      const proposal=navigationProposal(a,e);
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
        if(!['Enter','Escape','Tab'].includes(a.text)) throw Error('지원하지 않는 키예요.');
        e.focus();
        const form=e.form; let submitted=false;
        const mark=()=>{submitted=true}; form?.addEventListener('submit',mark,true);
        const keyCode={Enter:13,Escape:27,Tab:9}[a.text];
        let accepted;
        try {
          accepted=e.dispatchEvent(new KeyboardEvent('keydown',{key:a.text,code:a.text,keyCode,which:keyCode,bubbles:true,cancelable:true,composed:true}));
          if(accepted && !submitted && a.text==='Enter' && e.matches('input') && form) form.requestSubmit();
          else if(accepted && !submitted && a.text==='Enter' && e.matches('button,a[href]')) e.click();
        } finally { form?.removeEventListener('submit',mark,true); }
        if(accepted && a.text==='Tab') { const regions=commentRegions(); const fields=all('a[href],button,input,textarea,select,[tabindex]').filter(x=>inViewport(x)&&!secret(x)&&!protectedComment(x,regions)&&!x.disabled); const next=fields[fields.indexOf(e)+1]; if(next) next.focus(); }
        e.dispatchEvent(new KeyboardEvent('keyup',{key:a.text,code:a.text,bubbles:true,composed:true}));
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
      action_effect:actionEffect(a.action,before,settled.state,settled.info,input),...(input?{input_verified:true,characters:input.text.length}:{})};
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
  function pageZoom(percent) {
    if (!Number.isInteger(percent) || percent < 50 || percent > 200) throw Error('확대 비율은 50~200%예요.');
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
    refs = new Map(); revision++;
  }
  // 마이튜브's player (only when the app asks, in its own player session): the page shows only the video (mytube.css under
  // html.gyeot-mytube), YouTube's own player is started (a video element alone can still be empty before its first play),
  // the chosen picture quality is applied, and the page tells when it plays, pauses and ends (an ad's end is not the
  // video's), or that it did not start, and why.
  let mytubeBrowse=false, mytubeOn=false, mytubeVideo=null, mytubeWant='play', mytubeQuality='auto', mytubeFor=null, mytubeStarted=false,
    mytubeSince=0, mytubeStuck=false, mytubeShown='', mytubeQualityFor='', mytubeWhy='';
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
      v.addEventListener('pause',()=>{ if(!v.ended) mytubeSay(connection,'paused',{ad:mytubeAd()}); });
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
      if(!mytubeOn){mytubeOn=true;setInterval(()=>mytubeAttach(connection),700);} mytubeAttach(connection); return;
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
      try { pageZoom(message.page_zoom); connection.postMessage({page_zoom:true,ok:true,percent:message.page_zoom}); }
      catch(e) { connection.postMessage({page_zoom:true,ok:false,error:tidy(e.message,250)}); }
      return;
    }
    // A file the app sends for put_file, piece by piece: kept under its token (only the newest two), never an action itself.
    if (Object.hasOwn(message, 'upload_chunk')) {
      const piece=message.upload_chunk; let upload=uploads.get(piece.token);
      if (!upload) { upload={parts:[],total:-1}; uploads.set(piece.token,upload); while (uploads.size>2) uploads.delete(uploads.keys().next().value); }
      const text=atob(String(piece.data||'')), bytes=new Uint8Array(text.length);
      for (let i=0;i<text.length;i++) bytes[i]=text.charCodeAt(i);
      upload.parts[piece.index]=bytes; return;
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

