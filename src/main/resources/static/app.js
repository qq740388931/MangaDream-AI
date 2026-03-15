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

  var imagePreviewCloseBtn = imagePreviewModal && imagePreviewModal.querySelector('.image-preview-modal__close');
  var imagePreviewBackdrop = imagePreviewModal && imagePreviewModal.querySelector('.image-preview-modal__backdrop');
  var confirmModalBackdrop = confirmModal && confirmModal.querySelector('.modal__backdrop');

  // --- State ---
  var currentImageBase64 = null;
  var selectedTemplateId = null;

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
      if (uploadPreviewImg) uploadPreviewImg.src = dataUrl || placeholderUrl('预览');
      if (uploadRow) uploadRow.style.display = 'none';
      if (uploadPreviewWrap) uploadPreviewWrap.style.display = 'flex';
      if (fileHint) fileHint.textContent = file.name || '已选择';
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
    if (fileHint) fileHint.textContent = '未选择照片';
  }

  // --- 随机生成：无模板 ---
  function submitRandomGenerate() {
    if (!currentImageBase64) {
      alert('请先上传图片');
      return;
    }
    fetch('/api/generate-random', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imageBase64: currentImageBase64 })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200 && res.data) {
          var historyId = res.data.historyId;
          var pollIntervalMs = res.data.pollIntervalMs || 1500;
          pollHistoryThenShow(historyId, pollIntervalMs);
        } else {
          alert(res && res.msg ? res.msg : '随机生成请求失败');
        }
      })
      .catch(function (err) {
        console.error('generate-random error', err);
        alert('网络错误，请稍后重试');
      });
  }

  // --- 风格生成：传模板 id，走 /api/generate ---
  function submitGenerateWithTemplate(templateId) {
    if (!currentImageBase64) {
      alert('请先上传图片');
      return;
    }
    fetch('/api/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ templateId: templateId, imageBase64: currentImageBase64 })
    })
      .then(function (r) { return r.json(); })
      .then(function (res) {
        if (res && res.code === 200 && res.data) {
          var historyId = res.data.historyId;
          var pollIntervalMs = res.data.pollIntervalMs || 1500;
          pollHistoryThenShow(historyId, pollIntervalMs);
        } else {
          alert(res && res.msg ? res.msg : '生成请求失败');
        }
      })
      .catch(function (err) {
        console.error('generate error', err);
        alert('网络错误，请稍后重试');
      });
  }

  var MAX_POLL_COUNT = 20;

  function pollHistoryThenShow(historyId, pollIntervalMs) {
    if (!historyId) return;
    var count = 0;
    var interval = setInterval(function () {
      count++;
      if (count > MAX_POLL_COUNT) {
        clearInterval(interval);
        return;
      }
      fetch('/api/raphael-history/' + encodeURIComponent(historyId))
        .then(function (r) { return r.json(); })
        .then(function (res) {
          if (res && res.code === 200 && res.data !== undefined) {
            var data = res.data;
            if (typeof data === 'string' && data.length > 0) {
              clearInterval(interval);
              appendResultImage(data);
            }
          }
        })
        .catch(function (err) {
          console.error('raphael-history poll error', err);
        });
    }, pollIntervalMs);
  }

  // --- Result section ---
  function appendResultImage(url) {
    if (!url || !resultWrap) return;
    var a = document.createElement('a');
    a.className = 'result-section__link';
    a.href = url;
    a.download = 'mangadream-result-' + Date.now() + '.png';
    var img = document.createElement('img');
    img.className = 'result-section__img';
    img.src = toAbsoluteImageUrl(url);
    img.alt = '生成结果';
    a.appendChild(img);
    a.addEventListener('click', function (e) {
      e.preventDefault();
      openImagePreview(url);
    });
    resultWrap.appendChild(a);
    if (resultSection) resultSection.style.display = '';
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

  if (imagePreviewCloseBtn) imagePreviewCloseBtn.addEventListener('click', closeImagePreview);
  if (imagePreviewBackdrop) imagePreviewBackdrop.addEventListener('click', closeImagePreview);
  if (imagePreviewLink) imagePreviewLink.addEventListener('click', function (e) { e.preventDefault(); });

  if (modalCancel) modalCancel.addEventListener('click', closeConfirmModal);
  if (confirmModalBackdrop) confirmModalBackdrop.addEventListener('click', closeConfirmModal);

  // --- Init ---
  loadInspiration();
})();
