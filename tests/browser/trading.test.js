/*
  트레이딩 (app/src/main/assets/projects/trading.html) with the user's own TradingView (준: "Ai 브라우저 구글 로그인으로 트레이딩뷰도
  로그인 연동해 줄 수 있어?", "프로젝트에 트뷰"): the page as ProjectActivity serves it, its port to the app given here as
  connectApp gives it (a "gyeot-app" message with a MessagePort), TradingView's widget scripts answered empty (no network).
  Checked: the page says it takes the app's chart (tv_ready, with the symbol and interval it would show); told mine, the page keeps
  its own layout (the cards with their widget charts) and a card's 크게 보기 asks for the user's own chart of it (tv_focus; an
  interval goes with the picked symbol), its sheets tell the app to step aside (tv_sheet); told not mine, 크게 보기 is the widget's.
  And the bridge's reader (app/src/main/assets/browser/content.js tradingviewAccount), on stand-ins of TradingView's pages: only
  the is-… class words and two yes/no answers leave a page, never a name or an address; nothing is read off TradingView.
  Run: node trading.test.js   (TRADING_SHOTS=<dir> also saves trading_mine.png and trading_widgets.png at a phone's width)
*/
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

// TRADING_PAGE=<file> checks another copy of the page (an older template fails the tv_* checks).
const PAGE = process.env.TRADING_PAGE || path.join(__dirname, '../../app/src/main/assets/projects/trading.html');
const BASE = 'https://projects.gyeot.invalid/trading/';
const shots = process.env.TRADING_SHOTS;
let failures = 0;
const check = (yes, what) => { console.log((yes ? 'ok   ' : 'FAIL ') + what); if (!yes) failures++; };

// content.js's tradingviewAccount, run on stand-ins of a page (its <html> class, its own is_authenticated and user).
function accountReader() {
  const src = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/browser/content.js'), 'utf8');
  const body = src.slice(src.indexOf('  function tradingviewAccount('), src.indexOf('  function connect()'));
  return (host, cls, page) => {
    const said = [];
    const read = new Function('location', 'document', 'window', 'setTimeout', body + '; return tradingviewAccount;')(
      { hostname: host }, { documentElement: { className: cls } }, { wrappedJSObject: page }, fn => fn());
    read({ postMessage: m => said.push(m) }, 0);
    return said.map(m => m.tradingview);
  };
}
{
  const read = accountReader();
  const guest = read('www.tradingview.com', 'is-not-authenticated is-not-pro  theme-light', { is_authenticated: false, user: { username: 'Guest' } });
  check(guest.length === 1 && guest[0].known && guest[0].classes === 'is-not-authenticated is-not-pro' && guest[0].flag === false && guest[0].guest === true, 'reader: a guest page (as TradingView serves it)');
  const member = read('kr.tradingview.com', 'is-authenticated is-pro theme-dark', { is_authenticated: true, user: { username: 'someone', email: 'someone@example.com' } });
  check(member.length === 1 && member[0].classes === 'is-authenticated is-pro' && member[0].flag === true && member[0].guest === false, 'reader: a signed-in page');
  check(!/someone/.test(JSON.stringify(member)), 'reader: no name or address leaves the page');
  const quiet = read('www.tradingview.com', 'theme-dark', {});
  check(quiet.length === 1 && quiet[0].known === false, 'reader: a page that never says: asked again, then once unknown');
  const other = read('tradingview.com.example', 'is-authenticated', { is_authenticated: true });
  check(other.length === 1 && other[0].known === false && other[0].classes === undefined, 'reader: not TradingView: nothing read');
}

(async () => {
  const browser = await chromium.launch(process.env.CHROME ? { executablePath: process.env.CHROME } : {});
  // content.js's tradingviewChartOnly on a stand-in of TradingView's chart page (its right area and the right toolbar's pressed
  // button, as tradingview.com/chart serves them on 2026-11-27): the big chart shows the chart alone (준: "옆에 목록은 디폴트로 끈
  // 상태로 차트만"), folded by TradingView's own button; a panel that opens late is folded too; one already folded is left.
  {
    const src = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/browser/content.js'), 'utf8');
    const fn = src.slice(src.indexOf('  let chartTouched='), src.indexOf('  function connect()'));
    const stand = (late, open) => `<html><body style="margin:0"><div class="layout__area--right" style="position:fixed;right:0;top:0;bottom:0;width:${open && !late ? 346 : 45}px">
      <div data-name="right-toolbar"><button data-name="base" aria-pressed="${open && !late ? 'true' : 'false'}">w</button><button data-name="alerts" aria-pressed="false">a</button></div></div>
      <script>window.clicks = 0; const area = document.querySelector('.layout__area--right'), base = document.querySelector('[data-name=base]');
      base.onclick = () => { window.clicks++; const on = base.getAttribute('aria-pressed') !== 'true'; base.setAttribute('aria-pressed', String(on)); area.style.width = on ? '346px' : '45px'; };
      ${late ? 'setTimeout(() => base.click(), 1500);' : ''}</script></body></html>`;
    const run = async (url, html, touch) => {
      const p = await browser.newPage({ viewport: { width: 1100, height: 700 } });
      await p.route('**/*', r => r.fulfill({ body: html, contentType: 'text/html' }));
      await p.goto(url);
      await p.evaluate('(() => {' + fn + '\ntradingviewChartOnly(0); return 1; })()');
      if (touch) { await p.waitForTimeout(300); await p.mouse.click(300, 300); }
      await p.waitForTimeout(3500);
      const got = await p.evaluate(() => ({ w: document.querySelector('.layout__area--right').getBoundingClientRect().width, clicks: window.clicks }));
      await p.close(); return got;
    };
    let g = await run('https://www.tradingview.com/chart/?symbol=NASDAQ%3ANVDA&interval=D', stand(false, true));
    check(g.w === 45 && g.clicks === 1, 'chart only: the open side panel folded by its own button ' + JSON.stringify(g));
    g = await run('https://kr.tradingview.com/chart/abc/?symbol=AMEX%3ASOXL', stand(true, true));
    check(g.w === 45 && g.clicks === 2, 'chart only: a panel the saved layout opens a moment later is folded too ' + JSON.stringify(g));
    g = await run('https://www.tradingview.com/chart/?symbol=X', stand(false, false));
    check(g.w === 45 && g.clicks === 0, 'chart only: already folded, left as it is ' + JSON.stringify(g));
    g = await run('https://www.tradingview.com/chart/?symbol=X', stand(true, true), true);
    check(g.w === 346 && g.clicks === 1, 'chart only: once the user touched the page, a panel that opens is theirs ' + JSON.stringify(g));
    g = await run('https://www.tradingview.com/symbols/NVDA/', stand(false, true));
    check(g.w === 346 && g.clicks === 0, 'chart only: not a chart page, nothing touched ' + JSON.stringify(g));
  }
  const page = await browser.newPage({ viewport: { width: 411, height: 420 }, deviceScaleFactor: 2 });
  let widgetScripts = 0;
  await page.route('**/*', route => {
    const url = route.request().url();
    if (url === BASE) return route.fulfill({ status: 200, contentType: 'text/html', body: fs.readFileSync(PAGE, 'utf8') });
    if (/tradingview\.com/.test(url)) { if (/embed-widget-advanced-chart/.test(url)) widgetScripts++; return route.fulfill({ status: 200, contentType: 'text/javascript', body: '' }); }
    return route.abort();
  });
  await page.goto(BASE);
  // The app's port, as ProjectActivity.connectApp gives it; what the page says is kept in order.
  await page.evaluate(() => {
    const channel = new MessageChannel(); window.__said = []; window.__app = channel.port1;
    channel.port1.onmessage = e => window.__said.push(JSON.parse(e.data));
    window.postMessage('gyeot-app', '*', [channel.port2]);
  });
  // What the page said since the last look (a port message arrives a moment after the tap).
  const said = async () => { await page.waitForTimeout(120); return page.evaluate(() => window.__said.splice(0)); };
  const tell = m => page.evaluate(m => window.__app.postMessage(JSON.stringify(m)), m);
  await page.waitForTimeout(200);
  let first = await said();
  check(first.some(m => m.type === 'model'), 'asks the AI model as before');
  const ready = first.find(m => m.type === 'tv_ready');
  check(!!ready && ready.symbol === 'AMEX:SOXL' && ready.interval === 'D', 'tv_ready with the first card and the interval: ' + JSON.stringify(ready));
  await page.waitForTimeout(300);
  const widgetsBefore = widgetScripts;
  check(widgetsBefore >= 1, 'signed out: the cards draw widget charts (' + widgetsBefore + ')');
  if (shots) await page.screenshot({ path: path.join(shots, 'trading_widgets.png') });

  // Signed in: the page keeps its own layout, the cards with their widget charts (준: "이거 배열이 너무 이상해 원래 우리의 레이아웃으로
  // 해줘"); a card's 크게 보기 asks the app for the user's own chart of it.
  await tell({ type: 'tv', mine: true, symbol: 'AMEX:SOXL' });
  await page.waitForTimeout(300);
  check(await page.evaluate(() => document.body.classList.contains('mine')), 'mine: the page knows');
  check(await page.evaluate(() => [...document.querySelectorAll('.chart')].every(c => getComputedStyle(c).display !== 'none')), 'mine: the cards keep their chart boxes');
  const tall = await page.evaluate(() => document.querySelector('.card').getBoundingClientRect().height);
  check(tall > 300, 'mine: a card is as tall as before (' + Math.round(tall) + 'px)');
  check(await page.evaluate(() => document.querySelectorAll('.card.sel').length === 0), 'mine: no card marked');
  check(/내 트뷰/.test(await page.textContent('#count')), 'mine: the count line says 크게 보기 opens the user\'s chart');
  if (shots) await page.screenshot({ path: path.join(shots, 'trading_mine.png') });
  await said();
  await page.click('.card:nth-of-type(2) .name');
  let m = await said();
  check(m.every(x => x.type !== 'tv_symbol' && x.type !== 'tv_focus'), 'mine: a tap on a card opens nothing ' + JSON.stringify(m));
  await page.click('.card:nth-of-type(3) .small');
  m = await said();
  check(m.length === 1 && m[0].type === 'tv_focus' && m[0].symbol === 'NASDAQ:QQQ', '크게 보기: tv_focus ' + JSON.stringify(m));
  check(await page.evaluate(() => !document.querySelector('.card.focus')), '크게 보기 does not make the card full screen in the page');
  await page.click('#intervals button:nth-child(2)');
  m = await said();
  check(m.some(x => x.type === 'tv_symbol' && x.symbol === 'NASDAQ:QQQ' && x.interval === '60'), 'an interval: the picked symbol at it ' + JSON.stringify(m));
  await page.click('.card:nth-of-type(1) [data-news]');
  m = await said();
  check(m.some(x => x.type === 'news') && m.some(x => x.type === 'tv_sheet' && x.open === true) && !m.some(x => x.type === 'tv_symbol'), 'news still asks the app, and the sheet asks the chart to step aside');
  await page.click('#ai .small[data-close]');
  m = await said();
  check(m.some(x => x.type === 'tv_sheet' && x.open === false), 'the sheet closed: the chart comes back');
  await page.click('#edit');
  m = await said();
  check(m.some(x => x.type === 'tv_sheet' && x.open === true), '종목 편집 asks the chart to step aside');
  await page.evaluate(() => window.gyeotBack());
  m = await said();
  check(m.some(x => x.type === 'tv_sheet' && x.open === false), 'back closes it and the chart comes back');

  // 텍스트 복사 on the news (준: "트레이딩 뉴스 출처&링크 빼고 마크다운 없이 텍스트로 복사하기 버튼"): plain words, no source, link or Markdown.
  {
    await page.evaluate(() => { window.__copied = null; navigator.clipboard.writeText = t => { window.__copied = t; return Promise.resolve(); }; });
    await page.click('.card:nth-of-type(1) [data-news]');
    m = await said(); const id = (m.find(x => x.type === 'news') || {}).id;
    check(!!id, 'news asked: ' + JSON.stringify(m));
    check(await page.evaluate(() => document.getElementById('aiCopy').hidden), 'no 텍스트 복사 before an answer');
    const news = '## 한 줄 요약\n**반도체 강세**에 SOXL이 9% 올랐어요 [1].\n\n## 주요 뉴스\n- 엔비디아 실적이 예상을 넘었어요 (11/26, [Reuters](https://www.reuters.com/x))\n'
      + '- TSMC 증설 발표 — [Bloomberg](https://bloomberg.com/y)\n- 금리 동결 전망 (11/25, 출처: CNBC)\n* `AMD` 신제품 https://amd.com/z\n\n## X에서 오가는 이야기\n| 누가 | 말 |\n|---|---|\n| 애널리스트 | *과열* 경고 |\n\n## 출처\n- [Reuters](https://www.reuters.com/x)\n- https://bloomberg.com/y';
    await tell({ type: 'result', id, ok: true, text: news, model: 'Grok', source: 'web · X' });
    await page.waitForTimeout(150);
    check(await page.evaluate(() => !document.getElementById('aiCopy').hidden), '텍스트 복사 shows under a news answer');
    await page.click('#aiCopy'); await page.waitForTimeout(150);
    const copied = await page.evaluate(() => window.__copied);
    const want = '한 줄 요약\n반도체 강세에 SOXL이 9% 올랐어요.\n\n주요 뉴스\n• 엔비디아 실적이 예상을 넘었어요 (11/26)\n• TSMC 증설 발표\n• 금리 동결 전망 (11/25)\n• AMD 신제품\n\nX에서 오가는 이야기\n누가 · 말\n애널리스트 · 과열 경고';
    check(copied === want, 'plain words, no source, link or Markdown:\n' + copied);
    check(/복사했어요/.test(await page.textContent('#aiCopy')), 'the button says it copied');
    await page.click('#ai .small[data-close]'); await said();
  }

  // Signed out again: the widget charts are back.
  await tell({ type: 'tv', mine: false });
  await page.waitForTimeout(300);
  check(await page.evaluate(() => !document.body.classList.contains('mine') && [...document.querySelectorAll('.chart')].every(c => getComputedStyle(c).display !== 'none')), 'not mine: chart boxes there');
  await page.click('.card:nth-of-type(2) .name');
  check((await said()).every(x => x.type !== 'tv_symbol'), 'not mine: a tap on a card picks nothing');
  await browser.close();
  console.log(failures ? failures + ' failed' : 'all passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
