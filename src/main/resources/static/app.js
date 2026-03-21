(function () {
  'use strict';

  // --- DOM refs (all ids from index.html) ---
  var uploadRow = document.getElementById('uploadRow');
  var fileInput = document.getElementById('fileInput');
  var fileHint = document.getElementById('fileHint');
  var uploadPreviewWrap = document.getElementById('uploadPreviewWrap');
  var uploadPreviewImg = document.getElementById('uploadPreviewImg');
  var btnReselect = document.getElementById('btnReselect');
  var btnRandom = document.getElementById('btnRandom');
  var resultSection = document.getElementById('resultSection');
  var resultWrap = document.getElementById('resultWrap');
  var inspirationGrid = document.getElementById('inspirationGrid');
  var imagePreviewModal = document.getElementById('imagePreviewModal');
  var imagePreviewImg = document.getElementById('imagePreviewImg');
  var imagePreviewLink = document.getElementById('imagePreviewLink');
  var confirmModal = document.getElementById('confirmModal');
  var modalCancel = document.getElementById('modalCancel');
  var modalConfirm = document.getElementById('modalConfirm');
  var loadingIndicator = document.getElementById('loadingIndicator');
  var loginModal = document.getElementById('loginModal');
  var loginCloseBtn = document.getElementById('loginCloseBtn');
  var googleLoginBtn = document.getElementById('googleLoginBtn');
  var userInfo = document.getElementById('userInfo');
  var btnUseSample = document.getElementById('btnUseSample');

  var imagePreviewCloseBtn = imagePreviewModal && imagePreviewModal.querySelector('.image-preview-modal__close');
  var imagePreviewBackdrop = imagePreviewModal && imagePreviewModal.querySelector('.image-preview-modal__backdrop');
  var confirmModalBackdrop = confirmModal && confirmModal.querySelector('.modal__backdrop');

  /** 与后端 app.google.client-id 一致；优先使用 GET /api/auth/config 返回值 */
  var DEFAULT_GOOGLE_CLIENT_ID = '1047046505874-3qj543ddivppa34lag0a6k0u9od7stnk.apps.googleusercontent.com';

  // --- State ---
  var currentImageBase64 = null;
  var selectedTemplateId = null;
  var currentUser = null;

  // --- Helpers ---
  function toAbsoluteImageUrl(url) {
    if (!url || typeof url !== 'string') return url;
    if (url.startsWith('/images/')) {
      return window.location.origin + url;
    }
    return url;
  }

  function placeholderUrl(title) {
    var text = title ? String(title).replace(/</g, '&lt;').replace(/>/g, '&gt;') : 'Image';
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200">' +
      '<rect fill="#eee" width="200" height="200"/>' +
      '<text x="50%" y="50%" dominant-baseline="middle" text-anchor="middle" fill="#999" font-size="14" font-family="sans-serif">' + text + '</text>' +
      '</svg>';
    return 'data:image/svg+xml,' + encodeURIComponent(svg);
  }

  function getListFromResponse(res) {
    if (!res || !res.data) return [];
    if (Array.isArray(res.data.list)) return res.data.list;
    if (Array.isArray(res.data)) return res.data;
    return [];
  }

  function loadUserFromStorage() {
    try {
      var raw = window.localStorage.getItem('mangadream.user');
      if (!raw) return;
      var obj = JSON.parse(raw);
      if (obj && obj.token && obj.profile) {
        currentUser = obj;
      }
    } catch (e) {
      console.warn('load user failed', e);
    }
  }

  function saveUserToStorage(user) {
    currentUser = user;
    try {
      window.localStorage.setItem('mangadream.user', JSON.stringify(user));
    } catch (e) {
      console.warn('save user failed', e);
    }
  }

  function renderUserInfo() {
    if (!userInfo) return;
    userInfo.innerHTML = '';
    if (currentUser && currentUser.profile) {
      var avatar = document.createElement('img');
      avatar.className = 'header-user__avatar';
      if (currentUser.profile.avatarUrl) {
        avatar.src = currentUser.profile.avatarUrl;
      } else {
        avatar.src = 'https://www.gravatar.com/avatar/?d=identicon';
      }
      var nameSpan = document.createElement('span');
      nameSpan.className = 'header-user__name';
      nameSpan.textContent = currentUser.profile.name || currentUser.profile.email || 'Signed in';
      userInfo.appendChild(nameSpan);
      userInfo.appendChild(avatar);

      var popover = document.createElement('div');
      popover.className = 'header-user__popover';
      var displayName = currentUser.profile.name || currentUser.profile.email || 'Signed in';
      var nameDiv = document.createElement('div');
      nameDiv.className = 'header-user__popover-item';
      nameDiv.textContent = displayName;
      popover.appendChild(nameDiv);

      var pts = currentUser.profile.points;
      if (typeof pts !== 'number') {
        pts = 0;
      }
      var ptsDiv = document.createElement('div');
      ptsDiv.className = 'header-user__popover-item header-user__popover-item--muted';
      ptsDiv.textContent = 'Points: ' + pts;
      popover.appendChild(ptsDiv);

      var vipDays = currentUser.profile.vipDaysLeft;
      if (typeof vipDays === 'number' && vipDays > 0) {
        var vipDiv = document.createElement('div');
        vipDiv.className = 'header-user__popover-item header-user__popover-item--muted';
        vipDiv.textContent = 'VIP: ' + vipDays + ' days left';
        popover.appendChild(vipDiv);
      }

      var divider = document.createElement('div');
      divider.className = 'header-user__popover-divider';
      popover.appendChild(divider);

      var logoutBtn = document.createElement('button');
      logoutBtn.type = 'button';
      logoutBtn.textContent = 'Sign out';
      logoutBtn.className = 'header-user__logout-btn';
      logoutBtn.addEventListener('click', function () {
        try {
          window.localStorage.removeItem('mangadream.user');
        } catch (e) {}
        currentUser = null;
        renderUserInfo();
      });
      popover.appendChild(logoutBtn);

      popover.style.display = 'none';
      userInfo.appendChild(popover);

      avatar.addEventListener('click', function () {
        popover.style.display = popover.style.display === 'none' ? 'block' : 'none';
      });
    } else {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.id = 'headerLoginBtn';
      btn.className = 'btn btn--outline header-user__login-btn';
      btn.textContent = 'Sign in';
      btn.addEventListener('click', function () {
        openLoginModal();
      });
      userInfo.appendChild(btn);
    }
  }

  function getAnonGenerateCount() {
    try {
      var raw = window.localStorage.getItem('mangadream.anonCount');
      return raw ? parseInt(raw, 10) || 0 : 0;
    } catch (e) {
      return 0;
    }
  }

  function increaseAnonGenerateCount() {
    var n = getAnonGenerateCount() + 1;
    try {
      window.localStorage.setItem('mangadream.anonCount', String(n));
    } catch (e) {
      // ignore
    }
    return n;
  }

  function openLoginModal() {
    if (!loginModal) return;
    loginModal.style.display = 'flex';
    loginModal.setAttribute('aria-hidden', 'false');
  }

  function closeLoginModal() {
    if (!loginModal) return;
    loginModal.style.display = 'none';
    loginModal.setAttribute('aria-hidden', 'true');
  }

  function ensureCanGenerateOrAskLogin() {
    if (currentUser && currentUser.token) {
      return true;
    }
    var count = getAnonGenerateCount();
    if (count >= 2) {
      openLoginModal();
      return false;
    }
    increaseAnonGenerateCount();
    return true;
  }

  function handleGoogleCredentialResponse(credential) {
    if (!credential) {
      alert('系统繁忙请稍后再试');
      return;
    }
    fetch('/api/auth/google', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken: credential })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200 && res.data) {
          saveUserToStorage(res.data);
          closeLoginModal();
          renderUserInfo();
          alert('Signed in! You can keep generating.');
        } else {
          alert('系统繁忙请稍后再试');
        }
      })
      .catch(function (err) {
        console.error('google login error', err);
        alert('系统繁忙请稍后再试');
      });
  }

  function getGoogleClientId() {
    return window.__MANGADREAM_GOOGLE_CLIENT_ID__ || DEFAULT_GOOGLE_CLIENT_ID;
  }

  function loadAuthConfig() {
    return fetch('/api/auth/config')
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200 && res.data && res.data.googleClientId) {
          window.__MANGADREAM_GOOGLE_CLIENT_ID__ = res.data.googleClientId;
        }
      })
      .catch(function (err) {
        console.warn('auth config load failed', err);
      });
  }

  function initGoogleButton() {
    if (!googleLoginBtn) return;
    googleLoginBtn.addEventListener('click', function () {
      if (!window.google || !window.google.accounts || !window.google.accounts.id) {
        alert('系统繁忙请稍后再试');
        return;
      }
      window.google.accounts.id.initialize({
        client_id: getGoogleClientId(),
        callback: function (resp) {
          if (resp && resp.credential) {
            handleGoogleCredentialResponse(resp.credential);
          } else {
            alert('系统繁忙请稍后再试');
          }
        }
      });
      window.google.accounts.id.prompt();
    });
  }

  // --- Dev auto login (for local simulation) ---
  function autoDevLoginIfNeeded() {
    if (currentUser && currentUser.token) {
      return;
    }
    var host = window.location.hostname;
    if (host !== 'localhost' && host !== '127.0.0.1') {
      return;
    }
    fetch('/api/auth/dev-login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200 && res.data) {
          saveUserToStorage(res.data);
          renderUserInfo();
          console.log('Dev auto-login success');
        } else {
          console.warn('Dev login failed', res);
        }
      })
      .catch(function (err) {
        console.error('Dev login API error', err);
      });
  }

  // --- Loading state ---
  var isGenerating = false;

  function updateUserPointsIfAvailable(remainingPoints) {
    if (!currentUser || !currentUser.profile) return;
    if (typeof remainingPoints !== 'number') return;
    currentUser.profile.points = remainingPoints;
    saveUserToStorage(currentUser);
    renderUserInfo();
  }

  function setGenerating(flag) {
    isGenerating = !!flag;
    if (loadingIndicator) {
      loadingIndicator.style.display = isGenerating ? 'inline-flex' : 'none';
    }
    if (btnRandom) {
      btnRandom.disabled = isGenerating;
    }
    if (modalConfirm) {
      modalConfirm.disabled = isGenerating;
    }
  }

  // --- File upload ---
  function onFileChange() {
    var file = fileInput && fileInput.files && fileInput.files[0];
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var dataUrl = reader.result;
      if (typeof dataUrl === 'string' && dataUrl.indexOf('base64,') !== -1) {
        currentImageBase64 = dataUrl;
      } else {
        currentImageBase64 = null;
      }
      if (uploadPreviewImg) uploadPreviewImg.src = dataUrl || placeholderUrl('Preview');
      if (uploadRow) uploadRow.style.display = 'none';
      if (uploadPreviewWrap) uploadPreviewWrap.style.display = 'flex';
      if (fileHint) fileHint.textContent = file.name || 'Selected';
    };
    reader.readAsDataURL(file);
  }

  function onReselect() {
    currentImageBase64 = null;
    if (fileInput) {
      fileInput.value = '';
    }
    if (uploadPreviewImg) uploadPreviewImg.src = '';
    if (uploadPreviewWrap) uploadPreviewWrap.style.display = 'none';
    if (uploadRow) uploadRow.style.display = '';
    if (fileHint) fileHint.textContent = 'No photo selected';
  }

  function useSampleImage() {
    fetch('/images/22222.png')
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
        var reader = new FileReader();
        reader.onload = function () {
          var dataUrl = reader.result;
          if (typeof dataUrl === 'string' && dataUrl.indexOf('base64,') !== -1) {
            currentImageBase64 = dataUrl;
          } else {
            currentImageBase64 = null;
          }
          if (uploadPreviewImg) uploadPreviewImg.src = dataUrl || placeholderUrl('Sample');
          if (uploadRow) uploadRow.style.display = 'none';
          if (uploadPreviewWrap) uploadPreviewWrap.style.display = 'flex';
          if (fileHint) fileHint.textContent = 'Using sample image 22222.png';
        };
        reader.readAsDataURL(blob);
      })
      .catch(function (err) {
        console.error('Failed to load sample image', err);
        alert('系统繁忙请稍后再试');
      });
  }

  function submitRandomGenerate() {
    if (!currentImageBase64) {
      alert('系统繁忙请稍后再试');
      return;
    }
    if (!ensureCanGenerateOrAskLogin()) {
      return;
    }
    setGenerating(true);
    var headers = { 'Content-Type': 'application/json' };
    if (currentUser && currentUser.token) {
      headers['X-Session-Token'] = currentUser.token;
    }
    fetch('/api/generate-random', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({ imageBase64: currentImageBase64 })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200 && res.data) {
          var historyId = res.data.historyId;
          var pollIntervalMs = res.data.pollIntervalMs || 1500;
          if (typeof res.data.remainingPoints === 'number') {
            console.log('remainingPoints=', res.data.remainingPoints);
            updateUserPointsIfAvailable(res.data.remainingPoints);
          }
          pollHistoryThenShow(historyId, pollIntervalMs, currentImageBase64);
        } else {
          alert('系统繁忙请稍后再试');
          setGenerating(false);
        }
      })
      .catch(function (err) {
        console.error('generate-random error', err);
        alert('系统繁忙请稍后再试');
        setGenerating(false);
      });
  }

  function submitGenerateWithTemplate(templateId) {
    if (!currentImageBase64) {
      alert('系统繁忙请稍后再试');
      return;
    }
    if (!ensureCanGenerateOrAskLogin()) {
      return;
    }
    setGenerating(true);
    var headers = { 'Content-Type': 'application/json' };
    if (currentUser && currentUser.token) {
      headers['X-Session-Token'] = currentUser.token;
    }
    fetch('/api/generate', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({ templateId: templateId, imageBase64: currentImageBase64 })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200 && res.data) {
          var historyId = res.data.historyId;
          var pollIntervalMs = res.data.pollIntervalMs || 1500;
          if (typeof res.data.remainingPoints === 'number') {
            updateUserPointsIfAvailable(res.data.remainingPoints);
          }
          pollHistoryThenShow(historyId, pollIntervalMs, currentImageBase64);
        } else {
          alert('系统繁忙请稍后再试');
          setGenerating(false);
        }
      })
      .catch(function (err) {
        console.error('generate error', err);
        alert('系统繁忙请稍后再试');
        setGenerating(false);
      });
  }

  var MAX_POLL_COUNT = 20;

  function pollHistoryThenShow(historyId, pollIntervalMs, originalBase64) {
    if (!historyId) return;
    var count = 0;
    var interval = setInterval(function () {
      count++;
      if (count > MAX_POLL_COUNT) {
        clearInterval(interval);
        setGenerating(false);
        return;
      }
      fetch('/api/raphael-history/' + encodeURIComponent(historyId))
        .then(function (r) { return r.json(); })
        .then(function (res) {
          if (res && res.code === 200 && res.data !== undefined) {
            var data = res.data;
            if (typeof data === 'string' && data.length > 0) {
              clearInterval(interval);
              appendResultRow(originalBase64, data);
              setGenerating(false);
              var logHeaders = { 'Content-Type': 'application/json' };
              if (currentUser && currentUser.token) {
                logHeaders['X-Session-Token'] = currentUser.token;
              }
              fetch('/api/generate-log/result', {
                method: 'POST',
                headers: logHeaders,
                body: JSON.stringify({ historyId: historyId, resultUrl: data })
              }).catch(function (err) { console.warn('generate-log/result', err); });
            }
          }
        })
        .catch(function (err) {
          console.error('raphael-history poll error', err);
        });
    }, pollIntervalMs);
  }

  function appendResultRow(originalBase64, resultUrl) {
    if (!resultWrap || !resultUrl) return;

    var row = document.createElement('div');
    row.className = 'result-row';

    var colLeft = document.createElement('div');
    colLeft.className = 'result-row__col';
    var leftLabel = document.createElement('div');
    leftLabel.className = 'result-row__label';
    leftLabel.textContent = 'Original';
    var leftWrap = document.createElement('div');
    leftWrap.className = 'result-row__img-wrap';
    var leftImg = document.createElement('img');
    leftImg.className = 'result-row__img result-section__img';
    leftImg.src = originalBase64 || placeholderUrl('Original');
    leftImg.alt = 'Original';
    leftWrap.appendChild(leftImg);
    colLeft.appendChild(leftLabel);
    colLeft.appendChild(leftWrap);

    var colRight = document.createElement('div');
    colRight.className = 'result-row__col';
    var rightLabel = document.createElement('div');
    rightLabel.className = 'result-row__label';
    rightLabel.textContent = 'Result';
    var rightWrap = document.createElement('div');
    rightWrap.className = 'result-row__img-wrap result-row__img-wrap--clickable';
    var rightImg = document.createElement('img');
    rightImg.className = 'result-row__img result-section__img';
    rightImg.src = toAbsoluteImageUrl(resultUrl);
    rightImg.alt = 'Result';
    rightWrap.appendChild(rightImg);
    rightWrap.addEventListener('click', function () {
      openImagePreview(resultUrl);
    });
    colRight.appendChild(rightLabel);
    colRight.appendChild(rightWrap);

    row.appendChild(colLeft);
    row.appendChild(colRight);

    resultWrap.appendChild(row);
    if (resultSection) {
      resultSection.style.display = '';
    }
  }

  // --- Image preview modal ---
  function openImagePreview(url) {
    if (!imagePreviewModal || !imagePreviewImg || !imagePreviewLink) return;
    var abs = toAbsoluteImageUrl(url);
    imagePreviewImg.src = abs;
    imagePreviewLink.href = abs;
    imagePreviewModal.style.display = 'flex';
    imagePreviewModal.setAttribute('aria-hidden', 'false');
  }

  function closeImagePreview() {
    if (!imagePreviewModal) return;
    imagePreviewModal.style.display = 'none';
    imagePreviewModal.setAttribute('aria-hidden', 'true');
  }

  // --- Inspiration cards ---
  function renderCards(list) {
    if (!inspirationGrid) return;
    inspirationGrid.innerHTML = '';
    (list || []).forEach(function (item) {
      var card = document.createElement('div');
      card.className = 'inspiration-card';
      var img = document.createElement('img');
      img.className = 'inspiration-card__img';
      img.src = item.imageUrl ? toAbsoluteImageUrl(item.imageUrl) : placeholderUrl(item.title);
      img.alt = item.title || '';
      img.loading = 'lazy';
      img.onerror = function () { this.src = placeholderUrl(item.title || ''); };
      card.appendChild(img);
      card.addEventListener('click', function () {
        openConfirmModal(item.id);
      });
      inspirationGrid.appendChild(card);
    });
  }

  function openConfirmModal(templateId) {
    selectedTemplateId = templateId;
    if (confirmModal) {
      confirmModal.style.display = 'flex';
      confirmModal.setAttribute('aria-hidden', 'false');
    }
  }

  function closeConfirmModal() {
    selectedTemplateId = null;
    if (confirmModal) {
      confirmModal.style.display = 'none';
      confirmModal.setAttribute('aria-hidden', 'true');
    }
  }

  window.onConfirmGenerate = function onConfirmGenerate() {
    if (selectedTemplateId != null) {
      submitGenerateWithTemplate(selectedTemplateId);
    }
    closeConfirmModal();
  };

  // --- Load inspiration ---
  function loadInspiration() {
    fetch('/api/inspiration')
      .then(function (r) { return r.json(); })
      .then(function (res) {
        var list = getListFromResponse(res);
        renderCards(list);
      })
      .catch(function (err) { console.error('inspiration error', err); });
  }

  // --- Event bindings ---
  if (fileInput) fileInput.addEventListener('change', onFileChange);
  if (btnReselect) btnReselect.addEventListener('click', onReselect);
  if (btnRandom) btnRandom.addEventListener('click', submitRandomGenerate);
  if (btnUseSample) btnUseSample.addEventListener('click', useSampleImage);

  if (imagePreviewCloseBtn) imagePreviewCloseBtn.addEventListener('click', closeImagePreview);
  if (imagePreviewBackdrop) imagePreviewBackdrop.addEventListener('click', closeImagePreview);
  if (imagePreviewLink) imagePreviewLink.addEventListener('click', function (e) { e.preventDefault(); });

  if (modalCancel) modalCancel.addEventListener('click', closeConfirmModal);
  if (confirmModalBackdrop) confirmModalBackdrop.addEventListener('click', closeConfirmModal);
  if (loginCloseBtn) loginCloseBtn.addEventListener('click', closeLoginModal);

  // --- Init（先拉取 /api/auth/config，保证 Google client_id 与后端一致）---
  loadAuthConfig().then(function () {
    loadUserFromStorage();
    renderUserInfo();
    initGoogleButton();
    autoDevLoginIfNeeded();
    loadInspiration();
  });
})();
