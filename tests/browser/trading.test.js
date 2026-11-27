/*
  트레이딩 (app/src/main/assets/projects/trading.html) as ProjectActivity serves it, its port to the app given here as connectApp
  gives it (a "gyeot-app" message with a MessagePort), TradingView's widget scripts answered empty (no network).
  Checked: the cards draw their widget charts; the page tells the app the picked symbol and interval for the bar's TradingView
  button (tv_symbol: the first card at first, then the card made large, an interval changed), and nothing of a 내 트뷰 mode or a
  sign-in is left (준: "내트뷰 누르면 그냥 ai브라우저로만 열리기", "로그인도 없애줘"); 텍스트 복사 on the news.
  Run: node trading.test.js   (TRADING_SHOTS=<dir> also saves trading_widgets.png at a phone's width)
*/
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

// TRADING_PAGE=<file> checks another copy of the page.
const PAGE = process.env.TRADING_PAGE || path.join(__dirname, '../../app/src/main/assets/projects/trading.html');
const BASE = 'https://projects.gyeot.invalid/trading/';
const shots = process.env.TRADING_SHOTS;
let failures = 0;
const check = (yes, what) => { console.log((yes ? 'ok   ' : 'FAIL ') + what); if (!yes) failures++; };

// No sign-in reading is left in the AI 브라우저's bridge, and the page has no 내 트뷰 mode.
{
  const bridge = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/browser/content.js'), 'utf8');
  check(!/tradingview/i.test(bridge), 'bridge: nothing reads TradingView pages');
  const html = fs.readFileSync(PAGE, 'utf8');
  check(!/내 트뷰|tv_focus|tv_sheet|tv_ready|setMine/.test(html), 'page: no 내 트뷰 mode');
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
  const ready = first.find(m => m.type === 'tv_symbol');
  check(!!ready && ready.symbol === 'AMEX:SOXL' && ready.interval === 'D', 'the first card and the interval for the TradingView button: ' + JSON.stringify(ready));
  await page.waitForTimeout(300);
  const widgetsBefore = widgetScripts;
  check(widgetsBefore >= 1, 'the cards draw widget charts (' + widgetsBefore + ')');
  if (shots) await page.screenshot({ path: path.join(shots, 'trading_widgets.png') });

  // A tap on a card's name picks nothing; 크게 보기 makes that card large in the page and picks it; an interval goes with it.
  await page.click('.card:nth-of-type(2) .name');
  let m = await said();
  check(m.every(x => x.type !== 'tv_symbol'), 'a tap on a card picks nothing ' + JSON.stringify(m));
  await page.click('.card:nth-of-type(3) .small');
  m = await said();
  check(m.length === 1 && m[0].type === 'tv_symbol' && m[0].symbol === 'NASDAQ:QQQ' && m[0].interval === 'D', '크게 보기: that card picked ' + JSON.stringify(m));
  check(await page.evaluate(() => document.querySelectorAll('.card.focus').length === 1 && document.body.classList.contains('focused')), '크게 보기: the widget large in the page, as before');
  await page.click('.card.focus .small');
  m = await said();
  check(m.length === 0 && await page.evaluate(() => !document.querySelector('.card.focus')), '작게 보기: back to the cards, the pick kept ' + JSON.stringify(m));
  await page.click('#intervals button:nth-child(2)');
  m = await said();
  check(m.some(x => x.type === 'tv_symbol' && x.symbol === 'NASDAQ:QQQ' && x.interval === '60'), 'an interval: the picked symbol at it ' + JSON.stringify(m));
  await page.click('.card:nth-of-type(1) [data-news]');
  m = await said();
  check(m.some(x => x.type === 'news') && m.every(x => x.type !== 'tv_symbol' && x.type !== 'tv_sheet'), 'news asks the app, nothing else ' + JSON.stringify(m));
  await page.click('#ai .small[data-close]'); await said();

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

  await browser.close();
  console.log(failures ? failures + ' failed' : 'all passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
