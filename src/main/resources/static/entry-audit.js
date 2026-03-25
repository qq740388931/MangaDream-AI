/**
 * 进入站点语义审计（与 app.js 独立，须在其之前加载）：
 * - 本标签页本次会话首次打开：若 localStorage 无 token → FIRST_URL_ENTRY（匿名首次从 URL 进入）
 * - 若打开前已有 token（关网页后再进、自动登录）→ TOKEN_RESUME
 * Google / 开发登录成功由服务端写入 GOOGLE_LOGIN / DEV_LOGIN。
 */
(function () {
  'use strict';
  var FLAG = 'mangadream.entryAuditSent';
  try {
    if (sessionStorage.getItem(FLAG) === '1') {
      return;
    }
    var hadToken = false;
    var token = null;
    try {
      var raw = window.localStorage.getItem('mangadream.user');
      if (raw) {
        var u = JSON.parse(raw);
        if (u && u.token) {
          hadToken = true;
          token = u.token;
        }
      }
    } catch (e) {
      hadToken = false;
    }

    sessionStorage.setItem(FLAG, '1');

    var body = JSON.stringify({
      eventType: hadToken ? 'TOKEN_RESUME' : 'FIRST_URL_ENTRY'
    });
    var headers = { 'Content-Type': 'application/json' };
    if (hadToken && token) {
      headers['X-Session-Token'] = token;
    }

    fetch('/api/audit/entry', {
      method: 'POST',
      headers: headers,
      body: body,
      credentials: 'same-origin'
    }).catch(function () {});
  } catch (e) {
    console.warn('entry-audit', e);
  }
})();
