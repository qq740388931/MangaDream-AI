(function () {
  'use strict';

  var textarea = document.getElementById('feedbackContent');
  var btn = document.getElementById('btnSubmitFeedback');

  if (!btn || !textarea) return;

  btn.addEventListener('click', function () {
    var content = textarea.value || '';
    if (!content.trim()) {
      alert('Please enter your feedback');
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
        username: username || 'Anonymous',
        email: email || null,
        content: content
      })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200) {
          alert('Thanks for your feedback!');
          textarea.value = '';
        } else {
          alert((res && res.msg) ? res.msg : 'Submit failed');
        }
      })
      .catch(function () {
        alert('Network error: could not reach the server');
      });
  });
})();

