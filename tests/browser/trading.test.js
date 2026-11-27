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
