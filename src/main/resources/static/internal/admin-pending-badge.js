/**
 * 后台导航「会员充值」待处理条数角标；各 internal 页在导航后引入本脚本。
 */
(function () {
  function run() {
    fetch('/internal/db-browser/api/membership-requests/pending-count', { credentials: 'include' })
      .then(function (r) {
        if (r.status === 401) return null;
        return r.json();
      })
      .then(function (d) {
        var el = document.getElementById('navMembershipBadge');
        if (!el || !d || typeof d.pendingCount !== 'number') return;
        if (d.pendingCount > 0) {
          el.textContent = ' (' + d.pendingCount + ')';
        }
      })
      .catch(function () {});
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }
})();
