/**
 * 进入站点语义审计（与 app.js 独立，须在其之前加载）：
 * - 无 token：本标签页首次打开 → FIRST_URL_ENTRY（匿名首次进入），用 sessionStorage 去重，避免同页刷新刷爆）
 * - 有 token：每次页面加载 → TOKEN_RESUME（含关页再开、localStorage 自动恢复会话）；与匿名去重分开，避免被「已上报过」挡住
 * Google / 开发登录成功由服务端写入 GOOGLE_LOGIN / DEV_LOGIN。
 */
(function () {
  'use strict';
  var ANON_FLAG = 'mangadream.entryAuditAnonSent';
  try {
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

    if (hadToken && token) {
      fetch('/api/audit/entry', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Session-Token': token },
        body: JSON.stringify({ eventType: 'TOKEN_RESUME' }),
        credentials: 'same-origin'
      }).catch(function () {});
      return;
    }

    if (sessionStorage.getItem(ANON_FLAG) === '1') {
      return;
    }
    sessionStorage.setItem(ANON_FLAG, '1');

    fetch('/api/audit/entry', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ eventType: 'FIRST_URL_ENTRY' }),
      credentials: 'same-origin'
    }).catch(function () {});
  } catch (e) {
    console.warn('entry-audit', e);
  }
})();
