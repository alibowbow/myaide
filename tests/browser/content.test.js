/*
  The AI 브라우저's page bridge (app/src/main/assets/browser/content.js) in Chromium, its native port played here (the
  extension's browser.runtime.connectNative stubbed; each read or step goes in as BrowserEngine sends it).
  Checked (준: needless limits off, the essential ones as strong): a page with a small login box in its sidebar keeps its
  article text and links readable and usable, while the login form itself is hidden from the AI and cannot be clicked or
  typed into, and its password field is never shown; a page that is only a sign-in page, accounts.google.com and an OAuth
  callback stay blanked; Google's account chooser shows only the signed-in accounts; a reCAPTCHA badge in a corner no longer
  blanks an article; an anonymous comment form with a password still hides only itself; a submit button tells which address
  its form goes to (the money gate reads it). Run: node content.test.js
*/
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
// CONTENT_JS=<file> checks another copy of the bridge (an older one, to see these checks fail on it).
const CONTENT = fs.readFileSync(process.env.CONTENT_JS || path.join(__dirname, '../../app/src/main/assets/browser/content.js'), 'utf8');
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
const SITES = {
  'https://news.example.com/article/1': SIDEBAR, 'https://shop.example.com/login': SIGN_IN, 'https://news.example.com/article/4': BADGE,
  'https://gall.example.com/board/view/?id=1': COMMENT, 'https://shop.example.com/cart': SHOP,
  'https://shop.example.com/contact': CONTACT, 'https://shop.example.com/verify': CHALLENGE,
  'https://accounts.google.com/v3/signin/identifier': page('Sign in', '<main><h1>Sign in</h1><input type="email" placeholder="Email or phone"><button>Next</button><p>' + LONG + '</p></main>'),
  'https://accounts.google.com/accountchooser': CHOOSER,
  'https://app.example.com/callback?code=abc123&state=xyz': page('Signing in', '<main><p>' + LONG + '</p><a href="https://app.example.com/">홈</a></main>'),
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

  await browser.close();
  console.log(failures ? failures + ' failed' : 'all passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
