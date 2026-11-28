/*
  The AI 브라우저's page bridge (app/src/main/assets/browser/content.js) in Chromium, its native port stubbed as GeckoView
  gives it (browser.runtime.connectNative): the keys press takes (준: "쓸데 없는 제한이 너무 많아") — ArrowUp/Down/Left/Right,
  Backspace, Delete, Space, Home, End, PageUp, PageDown and Control/Meta with a, c, v, x, z and Enter — each sent as the page's
  own key events and doing what the browser would: a letter deleted, a caret moved, a list's choice changed, a box ticked, the
  page scrolled, a field selected, a copy this task pastes (never the phone's clipboard), an undo. Enter and Tab still work as
  before, a key with no ref goes to what has focus, and a key the bridge does not know is refused.
  Run: node content.test.js   (BRIDGE_FILE=<file> checks another copy of the bridge)
*/
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const BRIDGE = fs.readFileSync(process.env.BRIDGE_FILE || path.join(__dirname, '../../app/src/main/assets/browser/content.js'), 'utf8');
let failures = 0;
const check = (yes, what) => { console.log((yes ? 'ok   ' : 'FAIL ') + what); if (!yes) failures++; };

const PAGE = `<!doctype html><html><head><title>keys</title></head><body style="height:4000px">
<form id="f" onsubmit="event.preventDefault(); window.submitted=(window.submitted||0)+1"><input id="name" aria-label="이름" value="hello"><button type="submit">보내기</button></form>
<select id="size" aria-label="크기"><option value="s">작게</option><option value="m" disabled>보통</option><option value="l">크게</option></select>
<label><input id="agree" type="checkbox"> 동의</label>
<textarea id="note" aria-label="메모"></textarea>
<div id="combo" role="combobox" tabindex="0" aria-label="고르기">고르기</div>
</body></html>`;

/** The native side, as GeckoView's port: the page's messages kept, a command sent and its answer awaited. */
const STUB = () => {
  const said = []; let listener = null;
  const port = { postMessage: m => said.push(JSON.parse(JSON.stringify(m))), onMessage: { addListener: f => { listener = f; } }, onDisconnect: { addListener() {} } };
  window.browser = { runtime: { connectNative: () => port } };
  window.__said = said;
  window.__send = async (id, args) => { await listener({ id, args }); return said.filter(m => m.id === id && m.result).pop().result; };
  window.keys = [];
  document.addEventListener('keydown', e => window.keys.push({ key: e.key, code: e.code, ctrl: e.ctrlKey, meta: e.metaKey, target: e.target.id || e.target.tagName }), true);
};

(async () => {
  setTimeout(() => { console.log('FAIL the run did not finish in 60 s'); process.exit(1); }, 60000).unref();
  const browser = await chromium.launch(process.env.CHROME ? { executablePath: process.env.CHROME } : {});
  const page = await browser.newPage({ viewport: { width: 411, height: 760 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.route('**/*', route => route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: PAGE }));
  await page.goto('https://keys.example.com/');
  await page.evaluate(STUB);
  await page.addScriptTag({ content: BRIDGE });
  await page.waitForFunction(() => window.__said.some(m => m.ready));
  let n = 0;
  const send = args => page.evaluate(([id, a]) => window.__send(id, a), [String(++n), args]);
  const read = () => send({ action: 'read' });
  const ref = (page, label) => (page.elements.find(e => e.label === label) || {}).ref;
  const press = async (label, text, more = {}) => {
    const seen = await read();
    return send(Object.assign({ action: 'press', document: seen.document, ref: label ? ref(seen, label) : undefined, text }, more));
  };
  const value = id => page.evaluate(id => document.getElementById(id).value, id);

  // Backspace and Delete delete a letter, as the browser does: the old bridge refused both ("지원하지 않는 키예요").
  await page.evaluate(() => { const e = document.getElementById('name'); e.focus(); e.setSelectionRange(5, 5); });
  let r = await press('이름', 'Backspace');
  check(r.ok !== false && r.key_effect === 'deleted' && await value('name') === 'hell', 'Backspace deletes the letter before the caret: ' + JSON.stringify([r.error, r.key_effect, await value('name')]));
  await page.evaluate(() => document.getElementById('name').setSelectionRange(0, 0));
  r = await press('이름', 'Delete');
  check(r.key_effect === 'deleted' && await value('name') === 'ell', 'Delete deletes the letter after it');
  check(await page.evaluate(() => window.keys.slice(-1)[0].key === 'Delete' && window.keys.slice(-1)[0].code === 'Delete'), 'the page heard the key itself (keydown Delete)');

  // Arrows, Home and End move the caret in a field.
  r = await press('이름', 'End');
  check(r.key_effect === 'caret_moved' && await page.evaluate(() => document.getElementById('name').selectionStart) === 3, 'End: the caret to the end');
  r = await press('이름', 'ArrowLeft');
  check(await page.evaluate(() => document.getElementById('name').selectionStart) === 2, 'ArrowLeft: a letter back');
  r = await press('이름', 'Home');
  check(await page.evaluate(() => document.getElementById('name').selectionStart) === 0, 'Home: the caret to the start');

  // Space types a space in a field, ticks a box.
  await page.evaluate(() => document.getElementById('name').setSelectionRange(3, 3));
  r = await press('이름', 'Space');
  check(await value('name') === 'ell ' && r.key_effect === 'typed', 'Space in a field types a space');
  const box = (await read()).elements.find(e => e.type === 'checkbox');
  r = await send({ action: 'press', document: (await read()).document, ref: (await read()).elements.find(e => e.type === 'checkbox').ref, text: 'Space' });
  check(box && await page.evaluate(() => document.getElementById('agree').checked) && r.key_effect === 'clicked', 'Space on a checkbox ticks it');

  // ArrowDown changes a list's choice, past a disabled one, and tells the page (change).
  await page.evaluate(() => { window.changed = 0; document.getElementById('size').addEventListener('change', () => window.changed++); });
  r = await press('크기', 'ArrowDown');
  check(await value('size') === 'l' && await page.evaluate(() => window.changed) === 1 && r.key_effect === 'option_changed', 'ArrowDown picks the next choice, skipping a disabled one');
  r = await press('크기', 'ArrowUp');
  check(await value('size') === 's', 'ArrowUp the one before');

  // A widget that handles keys itself (a combobox) gets the key; nothing else happens to it.
  await page.evaluate(() => document.getElementById('combo').addEventListener('keydown', e => { if (e.key === 'ArrowDown') { e.preventDefault(); e.target.textContent = '열림'; } }));
  r = await press('고르기', 'ArrowDown');
  check(await page.evaluate(() => document.getElementById('combo').textContent) === '열림' && r.key_effect === 'page_handled', 'a page widget handles ArrowDown itself (preventDefault kept)');

  // PageDown, End and Home scroll a page; with no ref the key goes to what has focus (the page).
  await page.evaluate(() => { document.activeElement.blur(); scrollTo(0, 0); });
  r = await press(null, 'PageDown');
  check(r.ok !== false && r.scroll && r.scroll.moved && await page.evaluate(() => scrollY) > 300, 'PageDown with no ref scrolls the page: ' + JSON.stringify([r.error, r.scroll]));
  r = await press(null, 'End');
  check(await page.evaluate(() => scrollY + innerHeight >= document.documentElement.scrollHeight - 2), 'End to the bottom');
  r = await press(null, 'Home');
  check(await page.evaluate(() => scrollY) === 0, 'Home to the top');

  // Control+a selects a field, Control+c copies it for this task (the app keeps it), Control+v pastes what the app gives.
  r = await press('메모', 'Control+a');
  check(r.key_effect === 'selected_all', 'Control+a selects all');
  await page.evaluate(() => { const e = document.getElementById('name'); e.focus(); e.select(); });
  r = await press('이름', 'Control+c');
  check(r.copied === 'ell ' && r.key_effect === 'copied', 'Control+c copies the selection, given to the app: ' + JSON.stringify(r.copied));
  check(await page.evaluate(() => { const k = window.keys.slice(-1)[0]; return k.key === 'c' && k.code === 'KeyC' && k.ctrl && !k.meta; }), 'the page heard Control+c (ctrlKey)');
  r = await press('메모', 'Control+v', { paste: '붙여 넣은 글' });
  check(await value('note') === '붙여 넣은 글' && r.key_effect === 'pasted', 'Control+v pastes the words the app gave (this task\'s copy)');
  r = await press('메모', 'Control+v');
  check(r.key_effect === 'nothing_to_paste' && await value('note') === '붙여 넣은 글', 'with nothing copied, Control+v pastes nothing (never the phone\'s clipboard)');
  r = await press('메모', 'Control+z');
  check(r.ok !== false, 'Control+z runs (undo)');
  await page.evaluate(() => { const e = document.getElementById('name'); e.focus(); e.setSelectionRange(0, 3); });
  r = await press('이름', 'Meta+x');
  check(r.copied === 'ell' && await value('name') === ' ' && await page.evaluate(() => window.keys.slice(-1)[0].meta), 'Meta+x cuts (copied, then deleted)');

  // Enter still submits the form, Control+Enter is the page's own (its listeners hear it), Tab still moves focus.
  r = await press('이름', 'Enter');
  check(await page.evaluate(() => window.submitted) === 1, 'Enter still submits the form');
  r = await press('메모', 'Control+Enter');
  check(await page.evaluate(() => { const k = window.keys.slice(-1)[0]; return k.key === 'Enter' && k.ctrl; }) && await page.evaluate(() => window.submitted) === 1, 'Control+Enter goes to the page as a key, without the form\'s own submit');
  r = await press('이름', 'Tab');
  check(r.key_effect === 'focus_moved', 'Tab moves focus');

  // A key the bridge does not take is refused, and so is a protected field.
  r = await press('이름', 'F5');
  check(r.ok === false && /지원하지 않는 키/.test(r.error), 'an unknown key is refused');
  check(errors.length === 0, 'no page errors ' + JSON.stringify(errors));

  await browser.close();
  console.log(failures ? failures + ' failed' : 'all passed');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
