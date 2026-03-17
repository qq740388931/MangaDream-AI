(function () {
  'use strict';

  var textarea = document.getElementById('feedbackContent');
  var btn = document.getElementById('btnSubmitFeedback');

  if (!btn || !textarea) return;

  btn.addEventListener('click', function () {
    var content = textarea.value || '';
    if (!content.trim()) {
      alert('请先填写意见内容');
      return;
    }

    var stored = null;
    try {
      var raw = window.localStorage.getItem('mangadream.user');
      if (raw) stored = JSON.parse(raw);
    } catch (e) {}

    var username = stored && stored.profile && (stored.profile.name || stored.profile.email);
    var email = stored && stored.profile && stored.profile.email;

    fetch('/api/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username || '匿名用户',
        email: email || null,
        content: content
      })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200) {
          alert('感谢你的意见！');
          textarea.value = '';
        } else {
          alert(res && res.msg ? res.msg : '提交失败，请稍后重试');
        }
      })
      .catch(function () {
        alert('网络错误，稍后再试');
      });
  });
})();

