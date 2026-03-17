(function () {
  'use strict';

  var emailInput = document.getElementById('vipEmail');
  var btn = document.getElementById('btnVipSubmit');

  if (!emailInput || !btn) return;

  btn.addEventListener('click', function () {
    var email = (emailInput.value || '').trim();
    if (!email) {
      alert('请先填写邮箱');
      return;
    }
    if (email.indexOf('@') === -1) {
      alert('邮箱格式看起来不太对，请检查后再提交');
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
          alert('申请已提交，我们会通过该邮箱发送 PayPal 账单，请留意邮箱。');
          emailInput.value = '';
        } else {
          alert(res && res.msg ? res.msg : '提交失败，请稍后重试');
        }
      })
      .catch(function () {
        alert('网络错误，稍后再试');
      });
  });
})();

