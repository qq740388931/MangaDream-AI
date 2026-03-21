(function () {
  'use strict';

  var emailInput = document.getElementById('vipEmail');
  var btn = document.getElementById('btnVipSubmit');

  if (!emailInput || !btn) return;

  btn.addEventListener('click', function () {
    var email = (emailInput.value || '').trim();
    if (!email) {
      alert('请输入邮箱');
      return;
    }
    if (email.indexOf('@') === -1) {
      alert('邮箱格式不正确');
      return;
    }

    var stored = null;
    try {
      var raw = window.localStorage.getItem('mangadream.user');
      if (raw) stored = JSON.parse(raw);
    } catch (e) {}

    var headers = { 'Content-Type': 'application/json' };
    if (stored && stored.token) {
      headers['X-Session-Token'] = stored.token;
    }

    fetch('/api/membership-request', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({
        email: email,
        planCode: 'monthly_1000_points'
      })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200) {
          alert('Request submitted. We\'ll send the PayPal invoice to this email. Check your inbox.');
          emailInput.value = '';
        } else {
          alert((res && res.msg) ? res.msg : '提交失败');
        }
      })
      .catch(function () {
        alert('网络错误，无法连接服务器');
      });
  });
})();

