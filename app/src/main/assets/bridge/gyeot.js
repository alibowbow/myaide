/*
  들퍼레 미니앱 다리 (window.gyeot): what a page made by speaking may ask the app for. The app puts this script first in the page
  (core/PageBridge.inject) where it can answer: a 프로젝트 (its port, as 트레이딩's), or the user's own page on 들퍼레 홈 화면 (the home
  screen relays its calls). Nowhere else is window.gyeot defined: a page checks it and works without it.

  Every call returns a Promise. A refusal rejects with an Error whose code is denied (the user said no), limit (too many),
  bad (the arguments), blocked (not for a page), off (the phone has it off), failed or unavailable, and whose message says why
  in Korean, for the page to show. The first use of each family (알림 · 일정 · 기억 · 하루 기록 · AI · 도구) asks the user.

    gyeot.notify(title, text, {at})          a notification now, or at a time (Date, ms, '2026-11-28T09:00'): {id, at}
    gyeot.notify.cancel(id)                  one set for later taken away, or all of the page's own with no id: {cancelled: n}
    gyeot.calendar.list(from, to)            [{title, start, end, allDay, place}] (from: now, to: a week later by default)
    gyeot.calendar.add({title, start, end, place})  the calendar's new-event screen, filled in; the user saves: {opened}
    gyeot.memory.recall(query)               [short notes]  ·  gyeot.memory.remember(text): {said}
    gyeot.day.recall(query)                  the 하루 기록's answer, words
    gyeot.ask(prompt, {kind: 'text'|'short'|'json'})  the app's light AI; json: the value parsed
    gyeot.tool(name, args)                   timer {seconds}, alarm {hour, minute}, open_app {app}, navigate {destination}, play
                                             {query}, weather {}, when_event {when, then, what}, schedule_job {request, time,
                                             repeat} …: {said} (weather: {temp, hi, lo, code, place})
    gyeot.permissions()                      {notify, calendar, memory, day, ai, tool}: 'allow' | 'deny' | 'ask'
*/
(function () {
  'use strict';
  if (window.gyeot) return;
  var HOME = 'https://gyeot.local';
  // On the home screen the page is a frame of the home screen's page, which hands its calls to the app.
  var viaParent = location.hostname === 'pages.gyeot.local' && window.parent !== window;
  var port = null, waiting = {}, queue = [], seq = 0;
  var CONNECT = 5000, ANSWER = 10 * 60 * 1000;
  function fail(code, message) { var e = new Error(message || '하지 못했어요.'); e.name = 'GyeotError'; e.code = code || 'failed'; return e; }
  function settle(m) {
    if (!m || m.type !== 'gyeot' || typeof m.id !== 'string') return;
    var w = waiting[m.id]; if (!w) return;
    delete waiting[m.id]; clearTimeout(w.timer);
    if (m.ok) w.resolve(m.value === undefined ? null : m.value); else w.reject(fail(m.code, m.error));
  }
  function send(m) {
    if (viaParent) { try { window.parent.postMessage({ gyeot: 'call', message: JSON.stringify(m) }, HOME); return true; } catch (e) { return false; } }
    if (!port) return false;
    try { port.postMessage(JSON.stringify(m)); return true; } catch (e) { return false; }
  }
  function sent(id) {
    var w = waiting[id]; if (!w) return;
    clearTimeout(w.timer);
    w.timer = setTimeout(function () { if (waiting[id]) { delete waiting[id]; w.reject(fail('unavailable', '앱이 답하지 않았어요. 잠시 뒤에 다시 해 주세요.')); } }, ANSWER);
  }
  function flush() { while (queue.length) { var m = queue[0]; if (!send(m)) return; queue.shift(); sent(m.id); } }
  addEventListener('message', function (e) {
    if (viaParent) {
      if (e.source !== window.parent || e.origin !== HOME || !e.data || e.data.gyeot !== 'reply' || typeof e.data.message !== 'string') return;
      try { settle(JSON.parse(e.data.message)); } catch (err) {}
      return;
    }
    if (e.data !== 'gyeot-app' || !e.ports || !e.ports[0]) return;
    // The app's own message has no source frame; a frame inside the page never stands in for the app.
    if (e.source && e.source !== window) return;
    port = e.ports[0];
    port.addEventListener('message', function (x) { try { settle(JSON.parse(x.data)); } catch (err) {} });
    port.start();
    flush();
  });
  function call(name, args) {
    return new Promise(function (resolve, reject) {
      var id = 'gy-' + (++seq) + '-' + Math.random().toString(36).slice(2, 8);
      var m = { type: 'gyeot', id: id, call: name, args: args || {} };
      waiting[id] = { resolve: resolve, reject: reject, timer: 0 };
      if (send(m)) { sent(id); return; }
      queue.push(m);
      waiting[id].timer = setTimeout(function () {
        if (!waiting[id]) return;
        delete waiting[id]; queue = queue.filter(function (q) { return q.id !== id; });
        reject(fail('unavailable', '들퍼레 앱 안에서만 쓸 수 있어요.'));
      }, CONNECT);
    });
  }
  function when(v) { return v instanceof Date ? v.getTime() : v; }
  function notify(title, text, options) { return call('notify', { title: title, text: text, at: options ? when(options.at) : undefined }); }
  notify.cancel = function (id) { return call('notify.cancel', { id: id }); };
  var gyeot = {
    version: 1,
    notify: Object.freeze(notify),
    calendar: Object.freeze({
      list: function (from, to) { return call('calendar.list', { from: when(from), to: when(to) }); },
      add: function (event) { var e = event || {}; return call('calendar.add', { title: e.title, start: when(e.start), end: when(e.end), place: e.place, allDay: e.allDay }); }
    }),
    memory: Object.freeze({
      recall: function (query) { return call('memory.recall', { query: query }); },
      remember: function (text) { return call('memory.remember', { text: text }); }
    }),
    day: Object.freeze({ recall: function (query) { return call('day.recall', { query: query }); } }),
    ask: function (prompt, options) { return call('ask', { prompt: prompt, kind: options ? options.kind : undefined }); },
    tool: function (name, args) { return call('tool', { name: name, args: args || {} }); },
    permissions: function () { return call('permissions', {}); }
  };
  Object.defineProperty(window, 'gyeot', { value: Object.freeze(gyeot), writable: false, configurable: false, enumerable: true });
})();
