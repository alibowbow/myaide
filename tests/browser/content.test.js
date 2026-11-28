/*
  The AI 브라우저's page bridge (app/src/main/assets/browser/content.js) in Chromium, its native port played here (the
  extension's browser.runtime.connectNative stubbed; each read or step goes in as BrowserEngine sends it).
  Checked (준: needless limits off, the essential ones as strong): a page with a small login box in its sidebar keeps its
  article text and links readable and usable, while the login form itself is hidden from the AI and cannot be clicked or
  typed into, and its password field is never shown; a page that is only a sign-in page, accounts.google.com and an OAuth
  callback stay blanked; Google's account chooser shows only the signed-in accounts; a reCAPTCHA badge in a corner no longer
  blanks an article; an anonymous comment form with a password still hides only itself; a submit button tells which address
  its form goes to (the money gate reads it).
  The keys press takes (준: "쓸데 없는 제한이 너무 많아") — ArrowUp/Down/Left/Right, Backspace, Delete, Space, Home, End,
  PageUp, PageDown and Control/Meta with a, c, v, x, z and Enter — each sent as the page's own key events and doing what the
  browser would: a letter deleted, a caret moved, a list's choice changed, a box ticked, the page scrolled, a field selected, a
  copy this task pastes (never the phone's clipboard), an undo; Enter and Tab work as before, a key with no ref goes to what has
  focus, and a key the bridge does not know is refused.
  Run: node content.test.js
*/
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
// CONTENT_JS=<file> (or BRIDGE_FILE) checks another copy of the bridge (an older one, to see these checks fail on it).
const CONTENT = fs.readFileSync(process.env.CONTENT_JS || process.env.BRIDGE_FILE || path.join(__dirname, '../../app/src/main/assets/browser/content.js'), 'utf8');
let failures = 0;
const check = (yes, what) => { console.log((yes ? 'ok   ' : 'FAIL ') + what); if (!yes) failures++; };

const LONG = '이번 개편으로 동네 도서관의 운영 시간이 저녁 10시까지 늘어났어요. 주말에는 아이들을 위한 그림책 읽기 모임이 새로 열리고, '
  + '평일 오후에는 어르신 스마트폰 교실이 이어져요. 시는 내년까지 작은 도서관 열두 곳을 더 열 계획이라고 밝혔어요. ';
const page = (title, body) => '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>' + title + '</title></head><body>' + body + '</body></html>';
const SIDEBAR = page('동네 소식', '<header><a href="https://news.example.com/">동네 소식</a></header>'
  + '<main><article><h1>도서관이 밤 10시까지 열어요</h1><p>' + LONG + '</p><p>' + LONG + '</p>'
  + '<a id="next" href="https://news.example.com/article/2">다음 기사 읽기</a> <button id="like" type="button" onclick="window.liked=(window.liked||0)+1">좋아요</button></article></main>'
  + '<aside><div class="login_box"><form id="login" action="https://news.example.com/member/login" method="post" onsubmit="window.submitted=true;return false">'
  + '<input id="uid" name="userid" placeholder="아이디"><input id="pw" type="password" name="pw" placeholder="비밀번호">'
  + '<label><input type="checkbox" name="keep"> 로그인 유지</label><button id="go" type="submit">로그인</button></form>'
  + '<a href="https://news.example.com/join">회원가입</a></div></aside>');
const SIGN_IN = page('로그인', '<main><h1>로그인</h1><form action="/login" method="post"><input name="id" placeholder="아이디">'
  + '<input type="password" name="pw" placeholder="비밀번호"><button>로그인</button></form><a href="/find">아이디 찾기</a> <a href="/join">회원가입</a></main>'
  + '<footer>(주)예시 · 사업자등록번호 000-00-00000 · 서울시 어딘가</footer>');
const BADGE = page('기사', '<main><article><h1>반도체 수출이 늘었어요</h1><p>' + LONG + '</p><a href="https://news.example.com/article/3">관련 기사</a></article></main>'
  + '<div class="grecaptcha-badge" style="position:fixed;right:0;bottom:14px;width:256px;height:60px"><iframe title="reCAPTCHA" width="256" height="60" '
  + 'src="https://www.google.com/recaptcha/api2/anchor?ar=1&k=key&size=invisible"></iframe></div>');
const COMMENT = page('갤러리', '<main><div class="write_div"><p>' + LONG + '</p></div>'
  + '<div class="cmt_write"><form id="cmt"><input name="name" placeholder="닉네임"><input type="password" name="password" placeholder="비밀번호">'
  + '<textarea name="memo" placeholder="댓글을 입력하세요"></textarea><button type="submit">등록</button></form></div></main>');
const SHOP = page('장바구니', '<main><h1>장바구니</h1><p>' + LONG + '</p><form action="https://shop.example.com/order/pay?token=secret" method="post">'
  + '<button id="pay" type="submit">확인</button></form><a href="https://shop.example.com/products/17">아이폰 17 1,250,000원</a></main>');
const CONTACT = page('문의하기', '<main><h1>문의하기</h1><form action="https://shop.example.com/contact" method="post"><input name="email" type="email" placeholder="이메일">'
  + '<textarea name="body" placeholder="문의 내용"></textarea><div class="g-recaptcha" data-size="invisible"><div class="grecaptcha-badge" style="width:256px;height:60px">'
  + '<iframe title="reCAPTCHA" width="256" height="60" src="https://www.google.com/recaptcha/api2/anchor?k=key&size=invisible"></iframe></div></div><button type="submit">보내기</button></form></main>');
const CHALLENGE = page('Just a moment...', '<div class="main-wrapper" role="main"><div class="main-content"><h1>shop.example.com</h1><h2>Verifying you are human. This may take a few seconds.</h2>'
  + '<div id="turnstile-wrapper"><iframe width="300" height="65" src="https://challenges.cloudflare.com/cdn-cgi/challenge-platform/turnstile"></iframe></div>'
  + '<p>shop.example.com needs to review the security of your connection before proceeding.</p></div></div><div class="footer" role="contentinfo">Ray ID: 8f · Performance &amp; security by Cloudflare</div>');
const CHOOSER = page('계정 선택', '<div data-identifier="me@example.com" role="link">나 me@example.com</div><div role="link">다른 계정 사용</div>');
const KEYS = '<!doctype html><html><head><title>keys</title></head><body style="height:4000px">'
  + '<form id="f" onsubmit="event.preventDefault(); window.submitted=(window.submitted||0)+1"><input id="name" aria-label="이름" value="hello"><button type="submit">보내기</button></form>'
  + '<select id="size" aria-label="크기"><option value="s">작게</option><option value="m" disabled>보통</option><option value="l">크게</option></select>'
  + '<label><input id="agree" type="checkbox"> 동의</label><textarea id="note" aria-label="메모"></textarea>'
  + '<div id="combo" role="combobox" tabindex="0" aria-label="고르기">고르기</div></body></html>';
const SITES = {
  'https://news.example.com/article/1': SIDEBAR, 'https://shop.example.com/login': SIGN_IN, 'https://news.example.com/article/4': BADGE,
  'https://gall.example.com/board/view/?id=1': COMMENT, 'https://shop.example.com/cart': SHOP,
  'https://shop.example.com/contact': CONTACT, 'https://shop.example.com/verify': CHALLENGE,
  'https://accounts.google.com/v3/signin/identifier': page('Sign in', '<main><h1>Sign in</h1><input type="email" placeholder="Email or phone"><button>Next</button><p>' + LONG + '</p></main>'),
  'https://accounts.google.com/accountchooser': CHOOSER,
  'https://app.example.com/callback?code=abc123&state=xyz': page('Signing in', '<main><p>' + LONG + '</p><a href="https://app.example.com/">홈</a></main>'),
  'https://keys.example.com/': KEYS,
  // 데스크톱 보기 at its top: the phone moving it a little down when the page area changes size (window.shift plays that).
  'https://desk.example.com/': '<!doctype html><html><head><title>desk</title><script>visualViewport.addEventListener("resize", () => { if (window.shift) scrollTo(0, 30); });</script></head>'
    + '<body style="height:3000px;margin:0"><header id="top" style="height:60px">Google 정보</header></body></html>',
};

/** The page with the bridge put in as the extension puts it, its native port kept in the page (window.__port). */
async function open(browser, url) {
  const p = await browser.newPage({ viewport: { width: 411, height: 1400 } });
  p.on('pageerror', e => console.log('page error: ' + e.message));
  await p.route('**/*', route => {
    const want = route.request().url();
    if (SITES[want]) return route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: SITES[want] });
    if (/recaptcha|challenges\.cloudflare/.test(want)) return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><p>r</p>' });
    return route.fulfill({ status: 404, body: '' });
  });
  await p.goto(url);
  await p.evaluate(() => {
    window.__said = []; window.__listeners = [];
    const port = { postMessage: m => window.__said.push(m), onMessage: { addListener: f => window.__listeners.push(f) }, onDisconnect: { addListener() {} } };
    window.browser = { runtime: { connectNative: () => port } };
  });
  await p.addScriptTag({ content: CONTENT });
  return p;
}
let asked = 0;
/** One request through the port, as BrowserEngine sends it ({id, args}); its result. */
async function ask(p, args) {
  const id = ++asked;
  return p.evaluate(async ({ id, args }) => {
    for (const f of window.__listeners) f({ id, args });
    for (let i = 0; i < 100; i++) {
      const m = window.__said.find(x => x.id === id && x.result);
      if (m) return m.result;
      await new Promise(r => setTimeout(r, 50));
    }
    return { timeout: true };
  }, { id, args });
}
const labels = read => (read.elements || []).map(e => e.label);
const find = (read, label) => (read.elements || []).find(e => e.label === label);

(async () => {
  setTimeout(() => { console.log('FAIL the run did not finish in 120 s'); process.exit(1); }, 120000).unref();
  const browser = await chromium.launch(process.env.CHROME ? { executablePath: process.env.CHROME } : {});

  // A small login box in the sidebar: the article stays readable and usable; the login form is hidden and refused.
  {
    const p = await open(browser, 'https://news.example.com/article/1');
    const read = await ask(p, { action: 'read' });
    check(read.ok && !read.requires_user, 'sidebar login: the page is read, not blanked ' + JSON.stringify({ requires_user: read.requires_user, guard: read.guard_reason }));
    check(/도서관이 밤 10시까지 열어요/.test(read.text || '') && (read.text || '').includes('그림책 읽기 모임'), 'sidebar login: the article text is there');
    check(!!find(read, '다음 기사 읽기') && !!find(read, '좋아요') && !!find(read, '회원가입'), 'sidebar login: the article\'s links and buttons are there: ' + JSON.stringify(labels(read)));
    check(!(read.elements || []).some(e => e.type === 'password' || /비밀번호|아이디/.test(e.label + e.placeholder) || e.label === '로그인' || /로그인 유지/.test(e.label)),
      'sidebar login: nothing of the login form is shown (id, password, keep, submit): ' + JSON.stringify(labels(read)));
    check(!/비밀번호|로그인 유지/.test(read.text || ''), 'sidebar login: none of its words are in the text');
    check(read.protected_form && read.protected_form.reason === 'password', 'sidebar login: the page says a login form is hidden: ' + JSON.stringify(read.protected_form));
    // The article's own controls work.
    const like = find(read, '좋아요');
    const clicked = like ? await ask(p, { action: 'click', document: read.document, ref: like.ref }) : {};
    check(clicked.ok && await p.evaluate(() => window.liked === 1), 'sidebar login: a button of the article is clicked ' + JSON.stringify({ ok: clicked.ok, error: clicked.error }));
    // A ref the read never gave (as a page or a model could make one up) types nothing; the form is never filled or sent.
    const typed = await ask(p, { action: 'type', document: read.document, ref: 'x:1', text: 'hunter2' });
    check(!typed.ok && typed.action_accepted !== true, 'sidebar login: an unknown ref types nothing');
    check(await p.evaluate(() => document.getElementById('pw').value === '' && document.getElementById('uid').value === '' && !window.submitted), 'sidebar login: the form was never filled or sent');
    await p.close();
  }
  // A login form that shows only after the page was read: a ref read before it showed is refused once it is there.
  {
    const p = await open(browser, 'https://news.example.com/article/1');
    await p.evaluate(() => { document.getElementById('pw').style.display = 'none'; });
    const read = await ask(p, { action: 'read' });
    const go = find(read, '로그인');
    check(!!go, 'late login: before the password shows, its button is an ordinary button');
    await p.evaluate(() => { document.getElementById('pw').style.display = ''; });
    const pressed = go ? await ask(p, { action: 'click', document: read.document, ref: go.ref }) : { ok: true };
    check(pressed.action_accepted !== true && !(await p.evaluate(() => window.submitted)), 'late login: once the password shows, its form\'s button is not pressed ' + JSON.stringify({ ok: pressed.ok, error: pressed.error }));
    check(!pressed.ok && pressed.stale_observation === true && /로그인/.test(pressed.error || ''), 'late login: refused before anything ran, saying why: ' + pressed.error);
    const uid = find(read, '아이디');
    const typed = uid ? await ask(p, { action: 'type', document: read.document, ref: uid.ref, text: 'me' }) : { ok: true };
    check(typed.action_accepted !== true && await p.evaluate(() => document.getElementById('uid').value === ''), 'late login: nor is its id field typed into');
    // The rest of the page still works after the form showed.
    const next = await ask(p, { action: 'read' });
    check(next.ok && !next.requires_user && !!find(next, '좋아요') && !find(next, '로그인'), 'late login: a new read shows the page without the form');
    await p.close();
  }
  // A page that is only a sign-in page stays as it was: nothing of it reaches the AI.
  {
    const p = await open(browser, 'https://shop.example.com/login');
    const read = await ask(p, { action: 'read' });
    check(read.requires_user && read.guard_reason === 'password' && (read.elements || []).length === 0 && !/아이디 찾기/.test(read.text || ''), 'sign-in page: blanked as before ' + JSON.stringify({ requires_user: read.requires_user, guard: read.guard_reason, n: (read.elements || []).length }));
    await p.close();
  }
  // accounts.google.com and an OAuth callback stay blanked; the account chooser shows only the signed-in accounts.
  {
    let p = await open(browser, 'https://accounts.google.com/v3/signin/identifier');
    let read = await ask(p, { action: 'read' });
    check(read.requires_user && read.guard_reason === 'auth_host' && (read.elements || []).length === 0, 'accounts.google.com: blanked ' + JSON.stringify({ guard: read.guard_reason }));
    await p.close();
    p = await open(browser, 'https://app.example.com/callback?code=abc123&state=xyz');
    read = await ask(p, { action: 'read' });
    check(read.requires_user && read.guard_reason === 'oauth_callback' && (read.elements || []).length === 0 && !/abc123/.test(JSON.stringify(read)), 'OAuth callback: blanked, its code never shown');
    await p.close();
    p = await open(browser, 'https://accounts.google.com/accountchooser');
    read = await ask(p, { action: 'read' });
    check(read.account_chooser && labels(read).length === 1 && /me@example.com/.test(labels(read)[0]), 'account chooser: only the signed-in account ' + JSON.stringify(labels(read)));
    await p.close();
  }
  // A reCAPTCHA badge in the corner of an article blanks nothing; the badge itself is not shown.
  {
    const p = await open(browser, 'https://news.example.com/article/4');
    const read = await ask(p, { action: 'read' });
    check(read.ok && !read.requires_user && /반도체 수출이 늘었어요/.test(read.text || '') && !!find(read, '관련 기사'), 'captcha badge: the article is read ' + JSON.stringify({ requires_user: read.requires_user, guard: read.guard_reason }));
    await p.close();
  }
  // A contact form with an invisible reCAPTCHA stays usable; a full-page "Verifying you are human" check stays the user's.
  {
    let p = await open(browser, 'https://shop.example.com/contact');
    let read = await ask(p, { action: 'read' });
    check(read.ok && !read.requires_user && !!find(read, '보내기') && !!(read.elements || []).find(e => e.placeholder === '이메일') && !read.protected_form,
      'invisible reCAPTCHA: the contact form is there ' + JSON.stringify(labels(read)));
    await p.close();
    p = await open(browser, 'https://shop.example.com/verify');
    read = await ask(p, { action: 'read' });
    check(read.requires_user && read.guard_reason === 'captcha' && (read.elements || []).length === 0, 'full-page captcha check: blanked as before ' + JSON.stringify({ guard: read.guard_reason }));
    await p.close();
  }
  // An anonymous comment form with a password: only the form is hidden (as before).
  {
    const p = await open(browser, 'https://gall.example.com/board/view/?id=1');
    const read = await ask(p, { action: 'read' });
    check(read.ok && !read.requires_user && (read.text || '').includes('그림책') && !labels(read).some(l => /등록|닉네임|댓글/.test(l)), 'comment form: the post is read, the form hidden ' + JSON.stringify(labels(read)));
    await p.close();
  }
  // A submit button says where its form goes (its address without the query), for the money gate.
  {
    const p = await open(browser, 'https://shop.example.com/cart');
    const read = await ask(p, { action: 'read' });
    const pay = find(read, '확인');
    check(pay && pay.form_action === 'https://shop.example.com/order/pay', 'submit button: its form\'s address ' + JSON.stringify(pay));
    check(!/secret/.test(JSON.stringify(read)), 'submit button: never the query of that address');
    await p.close();
  }

  // The keys press takes, each as the page's own key events plus what the browser would do (the old bridge took only
  // Enter, Escape and Tab: "지원하지 않는 키예요").
  {
    const p = await open(browser, 'https://keys.example.com/');
    await p.evaluate(() => { window.keys = []; document.addEventListener('keydown', e => window.keys.push({ key: e.key, code: e.code, ctrl: e.ctrlKey, meta: e.metaKey }), true); });
    const errors = []; p.on('pageerror', e => errors.push(e.message));
    const read = () => ask(p, { action: 'read' });
    const press = async (label, text, more = {}) => {
      const seen = await read();
      const ref = label ? ((seen.elements || []).find(e => e.label === label) || {}).ref : undefined;
      return ask(p, Object.assign({ action: 'press', document: seen.document, ref, text }, more));
    };
    const value = id => p.evaluate(id => document.getElementById(id).value, id);
    const caretAt = () => p.evaluate(() => document.getElementById('name').selectionStart);
    const lastKey = () => p.evaluate(() => window.keys.slice(-1)[0] || {});

    // Backspace and Delete delete a letter.
    await p.evaluate(() => { const e = document.getElementById('name'); e.focus(); e.setSelectionRange(5, 5); });
    let r = await press('이름', 'Backspace');
    check(r.ok !== false && r.key_effect === 'deleted' && await value('name') === 'hell', 'keys: Backspace deletes the letter before the caret ' + JSON.stringify([r.error, r.key_effect, await value('name')]));
    await p.evaluate(() => document.getElementById('name').setSelectionRange(0, 0));
    r = await press('이름', 'Delete');
    check(r.key_effect === 'deleted' && await value('name') === 'ell', 'keys: Delete deletes the letter after it');
    const del = await lastKey();
    check(del.key === 'Delete' && del.code === 'Delete', 'keys: the page heard the key itself (keydown Delete)');

    // Arrows, Home and End move the caret in a field.
    r = await press('이름', 'End');
    check(r.key_effect === 'caret_moved' && await caretAt() === 3, 'keys: End puts the caret at the end');
    await press('이름', 'ArrowLeft');
    check(await caretAt() === 2, 'keys: ArrowLeft moves it a letter back');
    await press('이름', 'Home');
    check(await caretAt() === 0, 'keys: Home puts it at the start');

    // Space types a space in a field and ticks a box.
    await p.evaluate(() => document.getElementById('name').setSelectionRange(3, 3));
    r = await press('이름', 'Space');
    check(await value('name') === 'ell ' && r.key_effect === 'typed', 'keys: Space in a field types a space');
    const seen = await read(), box = (seen.elements || []).find(e => e.type === 'checkbox');
    r = box ? await ask(p, { action: 'press', document: seen.document, ref: box.ref, text: 'Space' }) : {};
    check(!!box && await p.evaluate(() => document.getElementById('agree').checked) && r.key_effect === 'clicked', 'keys: Space on a checkbox ticks it');

    // ArrowDown changes a list's choice, past a disabled one, and tells the page (change).
    await p.evaluate(() => { window.changed = 0; document.getElementById('size').addEventListener('change', () => window.changed++); });
    r = await press('크기', 'ArrowDown');
    check(await value('size') === 'l' && await p.evaluate(() => window.changed) === 1 && r.key_effect === 'option_changed', 'keys: ArrowDown picks the next choice, skipping a disabled one');
    await press('크기', 'ArrowUp');
    check(await value('size') === 's', 'keys: ArrowUp the one before');

    // A widget that handles keys itself (a combobox) gets the key, and its preventDefault is kept.
    await p.evaluate(() => document.getElementById('combo').addEventListener('keydown', e => { if (e.key === 'ArrowDown') { e.preventDefault(); e.target.textContent = '열림'; } }));
    r = await press('고르기', 'ArrowDown');
    check(await p.evaluate(() => document.getElementById('combo').textContent) === '열림' && r.key_effect === 'page_handled', 'keys: a page widget handles ArrowDown itself');

    // PageDown, End and Home scroll the page; with no ref the key goes to what has focus (the page).
    await p.evaluate(() => { document.activeElement.blur(); scrollTo(0, 0); });
    r = await press(null, 'PageDown');
    check(r.ok !== false && r.scroll && r.scroll.moved && await p.evaluate(() => scrollY) > 300, 'keys: PageDown with no ref scrolls the page ' + JSON.stringify([r.error, r.scroll]));
    await press(null, 'End');
    check(await p.evaluate(() => scrollY + innerHeight >= document.documentElement.scrollHeight - 2), 'keys: End scrolls to the bottom');
    await press(null, 'Home');
    check(await p.evaluate(() => scrollY) === 0, 'keys: Home scrolls to the top');

    // Control+a selects, Control+c copies for this task (the app keeps it), Control+v pastes only what the app gives.
    r = await press('메모', 'Control+a');
    check(r.key_effect === 'selected_all', 'keys: Control+a selects all');
    await p.evaluate(() => { const e = document.getElementById('name'); e.focus(); e.select(); });
    r = await press('이름', 'Control+c');
    check(r.copied === 'ell ' && r.key_effect === 'copied', 'keys: Control+c copies the selection, given to the app ' + JSON.stringify(r.copied));
    const copy = await lastKey();
    check(copy.key === 'c' && copy.code === 'KeyC' && copy.ctrl && !copy.meta, 'keys: the page heard Control+c (ctrlKey)');
    r = await press('메모', 'Control+v', { paste: '붙여 넣은 글' });
    check(await value('note') === '붙여 넣은 글' && r.key_effect === 'pasted', 'keys: Control+v pastes the words the app gave (this task\'s copy)');
    r = await press('메모', 'Control+v');
    check(r.key_effect === 'nothing_to_paste' && await value('note') === '붙여 넣은 글', 'keys: with nothing copied, Control+v pastes nothing (never the phone\'s clipboard)');
    r = await press('메모', 'Control+z');
    check(r.ok !== false, 'keys: Control+z runs (undo)');
    await p.evaluate(() => { const e = document.getElementById('name'); e.focus(); e.setSelectionRange(0, 3); });
    r = await press('이름', 'Meta+x');
    check(r.copied === 'ell' && await value('name') === ' ' && (await lastKey()).meta, 'keys: Meta+x cuts (copied, then deleted)');

    // Enter still submits the form, Control+Enter is the page's own, Tab still moves focus.
    await press('이름', 'Enter');
    check(await p.evaluate(() => window.submitted) === 1, 'keys: Enter still submits the form');
    await press('메모', 'Control+Enter');
    const enter = await lastKey();
    check(enter.key === 'Enter' && enter.ctrl && await p.evaluate(() => window.submitted) === 1, 'keys: Control+Enter goes to the page as a key, without the form\'s own submit');
    r = await press('이름', 'Tab');
    check(r.key_effect === 'focus_moved', 'keys: Tab moves focus');

    // A key the bridge does not take is refused.
    r = await press('이름', 'F5');
    check(r.ok === false && /지원하지 않는 키/.test(r.error), 'keys: an unknown key is refused');
    check(errors.length === 0, 'keys: no page errors ' + JSON.stringify(errors));
    await p.close();
  }
  // A page key never goes into a sign-in box, even when it has focus: the key goes to the page instead.
  {
    const p = await open(browser, 'https://news.example.com/article/1');
    await p.evaluate(() => { const pw = document.getElementById('uid'); pw.value = 'me'; pw.focus(); });
    const read = await ask(p, { action: 'read' });
    const r = await ask(p, { action: 'press', document: read.document, text: 'Backspace' });
    check(r.ok !== false && await p.evaluate(() => document.getElementById('uid').value) === 'me', 'keys: Backspace with no ref leaves a focused login field alone ' + JSON.stringify([r.error, r.key_effect]));
    await p.close();
  }

  // 데스크톱 보기 (준: "데스크탑모드에서 위가 잘려"): a page at its top stays at its top when the page area changes size; a page
  // the user scrolled, and the phone's own view (not desktop), are left where they are.
  {
    const p = await open(browser, 'https://desk.example.com/');
    const zoom = desktop => p.evaluate(d => { for (const f of window.__listeners) f({ page_zoom: 100, desktop: d }); }, desktop);
    const settle = () => p.waitForTimeout(250);
    await zoom(true); await p.evaluate(() => { window.shift = true; }); await settle();
    await p.setViewportSize({ width: 600, height: 1400 }); await settle();
    check(await p.evaluate(() => scrollY) === 0, 'desktop view: wider, the page stays at its top ' + await p.evaluate(() => scrollY));
    await p.setViewportSize({ width: 600, height: 1500 }); await settle();
    check(await p.evaluate(() => scrollY) === 0, 'desktop view: taller (the keyboard closed), still at its top');
    await p.evaluate(() => { window.shift = false; scrollTo(0, 200); }); await settle();
    await p.evaluate(() => { window.shift = true; }); await p.setViewportSize({ width: 411, height: 1400 }); await settle();
    check(await p.evaluate(() => scrollY) === 30, 'desktop view: a page the user scrolled is left where the change put it ' + await p.evaluate(() => scrollY));
    await zoom(false); await p.evaluate(() => { window.shift = false; scrollTo(0, 0); }); await settle();
    await p.evaluate(() => { window.shift = true; }); await p.setViewportSize({ width: 600, height: 1400 }); await settle();
    check(await p.evaluate(() => scrollY) === 30, 'phone view: left to the page');
    await p.close();
  }

  await browser.close();
  console.log(failures ? failures + ' failed' : 'all passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
