// === QuickNote v6 — UI Overhaul ===

(function () {
  'use strict';

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  // --- Native Android bridge ---
  const isNative = typeof window.NativeBridge !== 'undefined';
  function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result.split(',')[1]);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  // Wrap NativeBridge.buildZipAndSave in a Promise
  function nativeBuildZip(notesMd, claudeJson, audioFilename, transcriptText, zipFilename) {
    return new Promise((resolve, reject) => {
      const cb = 'qnZip' + Date.now();
      window[cb] = (size) => { delete window[cb]; size > 0 ? resolve(size) : reject(new Error('ZIP build failed')); };
      NativeBridge.buildZipAndSave(notesMd, claudeJson, audioFilename || '', transcriptText || '', zipFilename, cb);
    });
  }

  // --- State ---
  let mediaRecorder = null;
  let audioChunks = [];
  let recordingStartTime = null;
  let timerInterval = null;
  let currentSession = null;
  let sessions = [];
  let waveformInterval = null;
  let autoStopTimer = null;

  // Transcription state
  let selectedModelId      = null;
  let transcriptionKey     = null;
  let transcriptionPoll    = null;
  let progressTimer        = null;
  let progressStartMs      = 0;
  let progressEstimatedMs  = 120000;
  let mergedDocFilename    = null; // last saved merged doc for sharing

  // Download progress tracking
  const downloadProgress = {};

  const dom = {
    statusDot:      $('#status-dot'),
    timer:          $('#timer'),
    menuBtn:        $('#menu-btn'),
    menuDropdown:   $('#menu-dropdown'),
    menuHistory:    $('#menu-history'),
    menuExports:    $('#menu-exports'),
    menuSettings:   $('#menu-settings'),
    startScreen:    $('#start-screen'),
    notesScreen:    $('#notes-screen'),
    reviewScreen:   $('#review-screen'),
    historyScreen:  $('#history-screen'),
    exportsScreen:  $('#exports-screen'),
    settingsScreen: $('#settings-screen'),
    meetingTitle:   $('#meeting-title'),
    startBtn:       $('#start-btn'),
    sessionList:    $('#session-list'),
    currentTitle:   $('#current-title'),
    notesEntries:   $('#notes-entries'),
    emptyHint:      $('#empty-hint'),
    noteInput:      $('#note-input'),
    sendBtn:        $('#send-btn'),
    stopBtn:        $('#stop-btn'),
    recordingBar:   $('#recording-bar'),
    miniWaveform:   $('#mini-waveform'),
    reviewTitle:    $('#review-title'),
    reviewDuration: $('#review-duration'),
    reviewCount:    $('#review-count'),
    reviewNotes:    $('#review-notes'),
    exportBtn:      $('#export-btn'),
    shareBtn:       $('#share-btn'),
    newBtn:         $('#new-btn'),
    backBtn:        $('#back-btn'),
    historyList:    $('#history-list'),
    backFromExports:  $('#back-from-exports'),
    exportsList:      $('#exports-list'),
    backFromSettings: $('#back-from-settings'),
    settingsModelList:$('#settings-model-list'),
    viewAllBtn:     $('#view-all-btn'),
    // Settings controls
    settingAudioQuality:  $('#setting-audio-quality'),
    settingAutoStop:      $('#setting-auto-stop'),
    settingExportFormat:  $('#setting-export-format'),
    storagePathDisplay:   $('#storage-path-display'),
    // Transcription
    transcribeActionRow:      $('#transcribe-action-row'),
    transcribeEntryBtn:       $('#transcribe-entry-btn'),
    transcriptionScreen:      $('#transcription-screen'),
    backFromTranscription:    $('#back-from-transcription'),
    transcriptionNoModel:     $('#transcription-no-model'),
    transcriptionHasModel:    $('#transcription-has-model'),
    transcriptionModelSelect: $('#transcription-model-select'),
    whisperLangSelect:        $('#whisper-lang-select'),
    diarizeToggleRow:         $('#diarize-toggle-row'),
    diarizeToggle:            $('#diarize-toggle'),
    transcribeBtn:            $('#transcribe-btn'),
    cancelTranscribeBtn:      $('#cancel-transcribe-btn'),
    transcriptionProgressWrap:$('#transcription-progress-wrap'),
    transcriptionProgressBar: $('#transcription-progress-bar'),
    transcriptionStatus:      $('#transcription-status'),
    transcriptionResult:      $('#transcription-result'),
    transcriptText:           $('#transcript-text'),
    copyTranscriptBtn:        $('#copy-transcript-btn'),
    mergeNotesBtn:            $('#merge-notes-btn'),
    shareMergedBtn:           $('#share-merged-btn'),
    goToSettingsBtn:          $('#go-to-settings-btn'),
  };

  // --- Settings ---
  const DEFAULT_SETTINGS = {
    audioQuality: 'standard',
    autoStop: '0',
    exportFormat: 'zip',
    stealthMode: 'off',
  };

  function loadSettings() {
    try {
      const saved = JSON.parse(localStorage.getItem('quicknote_settings'));
      return { ...DEFAULT_SETTINGS, ...saved };
    } catch {
      return { ...DEFAULT_SETTINGS };
    }
  }

  function saveSettings(settings) {
    localStorage.setItem('quicknote_settings', JSON.stringify(settings));
  }

  function applySettingsToUI() {
    const s = loadSettings();
    if (dom.settingAudioQuality) dom.settingAudioQuality.value = s.audioQuality;
    if (dom.settingAutoStop) dom.settingAutoStop.value = s.autoStop;
    if (dom.settingExportFormat) dom.settingExportFormat.value = s.exportFormat;
    const stealthSel = $('#setting-stealth-mode');
    if (stealthSel) stealthSel.value = s.stealthMode || 'off';
    // Storage path
    if (dom.storagePathDisplay) {
      if (isNative && typeof NativeBridge.getStoragePath === 'function') {
        try { dom.storagePathDisplay.textContent = NativeBridge.getStoragePath(); } catch { dom.storagePathDisplay.textContent = '/sdcard/QuickNote/'; }
      } else {
        dom.storagePathDisplay.textContent = 'IndexedDB (浏览器本地)';
      }
    }
  }

  function onSettingChange() {
    const stealthSel = $('#setting-stealth-mode');
    const settings = {
      audioQuality: dom.settingAudioQuality?.value || '44100',
      autoStop: dom.settingAutoStop?.value || '0',
      exportFormat: dom.settingExportFormat?.value || 'zip',
      stealthMode: stealthSel?.value || 'off',
    };
    saveSettings(settings);
  }

  // --- Utilities ---
  function formatTime(ms) {
    const s = Math.floor(ms / 1000);
    return `${String(Math.floor(s / 60)).padStart(2,'0')}:${String(s % 60).padStart(2,'0')}`;
  }
  function formatTimestamp(ms) {
    // Guard: if ms is unreasonably large (>24 hours), it's likely a bug
    // (e.g. Date.now() - 0 when recordingStartTime was lost)
    // Show as HH:MM clock time instead
    if (ms > 86400000) {
      const d = new Date(ms + (recordingStartTime || 0));
      return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
    }
    const s = Math.floor(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2,'0')}`;
  }
  function formatTimestampSecs(secs) {
    const s = Math.floor(secs);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2,'0')}`;
  }
  function formatDate(ts) {
    const d = new Date(ts);
    return `${d.getMonth()+1}/${d.getDate()} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
  }
  function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
  }
  function generateId() { return Date.now().toString(36) + Math.random().toString(36).substr(2,5); }
  function sanitizeFilename(n) { return (n||'meeting').replace(/[^\w\u4e00-\u9fff-]/g,'_').substring(0,50); }
  function escapeHtml(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; }

  function showToast(msg, ms = 2000) {
    const t = document.createElement('div');
    t.className = 'toast'; t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(() => {
      t.style.animation = 'none';
      t.style.opacity = '0';
      t.style.transform = 'translateX(-50%) translateY(12px)';
      t.style.transition = 'opacity 0.15s ease-in, transform 0.15s ease-in';
      setTimeout(() => t.remove(), 160);
    }, ms);
  }

  // --- Storage: sessions ---
  function loadSessions() {
    try { sessions = JSON.parse(localStorage.getItem('quicknote_sessions') || '[]'); }
    catch { sessions = []; }
  }
  function saveSessions() {
    localStorage.setItem('quicknote_sessions', JSON.stringify(
      sessions.map(s => ({
        id:s.id, title:s.title, startTime:s.startTime, duration:s.duration,
        notes: s.notes.map(n => {
          const { imageDataUrl, ...rest } = n; // strip dataUrl (too large for localStorage)
          return rest;
        }),
        hasAudio:s.hasAudio||false,
        nativeAudioFile:s.nativeAudioFile||null,
        transcription:s.transcription||null,
      }))
    ));
  }

  // --- IndexedDB ---
  function openDB(name, version, upgrade) {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(name, version);
      req.onupgradeneeded = upgrade;
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  function openAudioDB() {
    return openDB('quicknote_audio', 1, e => e.target.result.createObjectStore('audio', { keyPath: 'id' }));
  }
  function openExportsDB() {
    return openDB('quicknote_exports', 1, e => e.target.result.createObjectStore('exports', { keyPath: 'id' }));
  }
  async function idbPut(db, store, obj) {
    return new Promise((res, rej) => {
      const tx = db.transaction(store, 'readwrite');
      tx.objectStore(store).put(obj);
      tx.oncomplete = res; tx.onerror = () => rej(tx.error);
    });
  }
  async function idbGet(db, store, key) {
    return new Promise((res, rej) => {
      const tx = db.transaction(store, 'readonly');
      const r = tx.objectStore(store).get(key);
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
    });
  }
  async function idbGetAll(db, store) {
    return new Promise((res, rej) => {
      const tx = db.transaction(store, 'readonly');
      const r = tx.objectStore(store).getAll();
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
    });
  }
  async function idbDelete(db, store, key) {
    return new Promise((res, rej) => {
      const tx = db.transaction(store, 'readwrite');
      tx.objectStore(store).delete(key);
      tx.oncomplete = res; tx.onerror = () => rej(tx.error);
    });
  }
  async function saveAudio(id, blob) { const db = await openAudioDB(); return idbPut(db, 'audio', { id, blob, type: blob.type }); }
  async function getAudio(id) { const db = await openAudioDB(); return idbGet(db, 'audio', id); }
  async function deleteAudio(id) { const db = await openAudioDB(); return idbDelete(db, 'audio', id); }

  // --- Screens with animation ---
  let currentScreenId = 'start-screen';
  const screenHistory = ['start-screen'];

  function showScreen(id, reverse) {
    if (id === currentScreenId) return;
    const outEl = $(`#${currentScreenId}`);
    const inEl = $(`#${id}`);
    if (!inEl) return;

    dom.menuDropdown.classList.add('hidden');

    if (outEl && !reverse) {
      outEl.classList.remove('active');
      inEl.classList.add('active', 'entering');
      inEl.addEventListener('animationend', function handler() {
        inEl.removeEventListener('animationend', handler);
        inEl.classList.remove('entering');
      });
    } else if (outEl && reverse) {
      outEl.classList.remove('active');
      inEl.classList.add('active');
    } else {
      $$('.screen').forEach(s => s.classList.remove('active'));
      inEl.classList.add('active');
    }

    currentScreenId = id;

    // Show/hide global recording banner when not on notes screen
    updateRecordingBanner();
  }

  function updateRecordingBanner() {
    let banner = $('#recording-global-banner');
    const isRecording = !!recordingStartTime;
    const onNotesScreen = currentScreenId === 'notes-screen';

    if (isRecording && !onNotesScreen) {
      // Show banner: "Recording in progress — tap to return"
      if (!banner) {
        banner = document.createElement('div');
        banner.id = 'recording-global-banner';
        banner.className = 'recording-global-banner';
        banner.textContent = '记录进行中 · 点击返回';
        banner.addEventListener('click', () => {
          showScreen('notes-screen', false);
          screenHistory.push('notes-screen');
        });
        document.getElementById('main-content')?.prepend(banner);
      }
      banner.style.display = '';
    } else if (banner) {
      banner.style.display = 'none';
    }
  }

  function navigateTo(id) {
    screenHistory.push(id);
    showScreen(id, false);
  }

  function navigateBack(fallback) {
    screenHistory.pop();
    const prev = screenHistory[screenHistory.length - 1] || fallback || 'start-screen';
    showScreen(prev, true);
  }

  // --- Waveform ---
  function initWaveformBars() {
    if (!dom.miniWaveform) return;
    dom.miniWaveform.innerHTML = '';
    const barCount = 30;
    for (let i = 0; i < barCount; i++) {
      const bar = document.createElement('div');
      bar.className = 'waveform-bar';
      bar.style.height = '3px';
      dom.miniWaveform.appendChild(bar);
    }
  }

  function startWaveformAnimation() {
    if (!dom.miniWaveform) return;
    const bars = dom.miniWaveform.querySelectorAll('.waveform-bar');
    if (!bars.length) return;
    waveformInterval = setInterval(() => {
      bars.forEach(bar => {
        const h = Math.floor(Math.random() * 18) + 3;
        bar.style.height = h + 'px';
      });
    }, 150);
  }

  function stopWaveformAnimation() {
    clearInterval(waveformInterval);
    waveformInterval = null;
    if (dom.miniWaveform) {
      dom.miniWaveform.querySelectorAll('.waveform-bar').forEach(bar => {
        bar.style.height = '3px';
      });
    }
  }

  // --- Session List ---
  function renderSessionList(container, showDelete, limit) {
    container.innerHTML = '';
    const sorted = [...sessions].sort((a,b) => b.startTime - a.startTime);
    const items = limit ? sorted.slice(0, limit) : sorted;
    if (!items.length) {
      container.innerHTML = '<p class="empty-list-hint">\u6682\u65E0\u8BB0\u5F55</p>';
      return;
    }
    items.forEach(s => {
      const card = document.createElement('div');
      card.className = 'session-card';
      card.innerHTML = `
        <div class="session-info">
          <span class="session-name">${escapeHtml(s.title||'\u672A\u547D\u540D\u4F1A\u8BAE')}</span>
          <span class="session-meta">${formatDate(s.startTime)} \u00B7 ${formatTime(s.duration)} \u00B7 ${s.notes.length}\u6761\u7B14\u8BB0</span>
        </div>
        <div class="session-actions">
          ${showDelete ? `<button class="delete-btn" data-id="${s.id}">\u2715</button>` : ''}
          <span class="session-arrow">\u203A</span>
        </div>`;
      card.addEventListener('click', e => {
        if (e.target.classList.contains('delete-btn')) { e.stopPropagation(); deleteSession(s.id); return; }
        openReview(s);
      });
      container.appendChild(card);
    });
  }

  function deleteSession(id) {
    if (!confirm('\u786E\u5B9A\u5220\u9664\u6B64\u8BB0\u5F55\uFF1F')) return;
    sessions = sessions.filter(s => s.id !== id);
    saveSessions();
    deleteAudio(id).catch(() => {});
    renderSessionList(dom.sessionList, false, 5);
    renderSessionList(dom.historyList, true);
  }

  // --- Active session persistence (crash recovery) ---
  const ACTIVE_SESSION_KEY = 'quicknote_active_session';
  const ACTIVE_STATE_KEY   = 'quicknote_active_state';

  function saveActiveSession() {
    if (!currentSession) return;
    const toSave = {
      ...currentSession,
      duration: recordingStartTime ? Date.now() - recordingStartTime : currentSession.duration,
      notes: currentSession.notes.map(n => { const { imageDataUrl, ...rest } = n; return rest; }),
    };
    const json = JSON.stringify(toSave);
    // Write to localStorage (fast but may be lost on crash)
    try { localStorage.setItem(ACTIVE_SESSION_KEY, json); } catch(e) {}
    // Also write to native filesystem (survives WebView crash/kill)
    if (isNative) {
      try { NativeBridge.saveText('_active_session.json', json); } catch(e) {}
    }
  }

  function saveActiveState(recording) {
    try {
      localStorage.setItem(ACTIVE_STATE_KEY, JSON.stringify({
        recording: !!recording,
        recordingStartTime: recordingStartTime,
        screenId: currentScreenId,
      }));
    } catch(e) {}
  }

  function clearActiveSession() {
    localStorage.removeItem(ACTIVE_SESSION_KEY);
    localStorage.removeItem(ACTIVE_STATE_KEY);
    if (isNative) { try { NativeBridge.saveText('_active_session.json', ''); } catch(e) {} }
  }

  function checkForRecovery() {
    let saved = localStorage.getItem(ACTIVE_SESSION_KEY);
    // If localStorage was wiped (crash/kill), try native filesystem fallback
    if (!saved && isNative) {
      try { saved = NativeBridge.readText('_active_session.json'); } catch(e) {}
    }
    const state = localStorage.getItem(ACTIVE_STATE_KEY);
    if (!saved || saved.length < 10) return;
    try {
      const session = JSON.parse(saved);
      const st = state ? JSON.parse(state) : {};
      if (!session || !session.id || !session.notes) { clearActiveSession(); return; }
      // Check if this session is already in the finished sessions list
      if (sessions.find(s => s.id === session.id)) { clearActiveSession(); return; }
      // Found an interrupted session — offer recovery
      const noteCount = session.notes.length;
      const dur = session.duration ? formatTime(session.duration) : '未知';
      if (confirm(`发现未保存的记录「${session.title}」（${noteCount}条笔记，${dur}）。\n\n是否恢复？`)) {
        // Recover: add to sessions and open review
        sessions.push(session);
        saveSessions();
        clearActiveSession();
        openReview(session);
        showToast('记录已恢复');
      } else {
        clearActiveSession();
      }
    } catch(e) { clearActiveSession(); }
  }

  let autoSaveInterval = null;

  function startAutoSave() {
    stopAutoSave();
    autoSaveInterval = setInterval(() => {
      saveActiveSession();
      saveActiveState(true);
    }, 5000); // every 5 seconds
  }

  function stopAutoSave() {
    if (autoSaveInterval) { clearInterval(autoSaveInterval); autoSaveInterval = null; }
  }

  // --- Recording ---
  async function startRecording() {
    // Guard: if a recording is already in progress, save it first
    if (currentSession && recordingStartTime) {
      currentSession.duration = Date.now() - recordingStartTime;
      const snapshot = JSON.parse(JSON.stringify(currentSession));
      delete snapshot.imageDataUrl; // strip large data
      snapshot.notes = snapshot.notes.map(n => { const { imageDataUrl, ...rest } = n; return rest; });
      sessions.push(snapshot);
      saveSessions();
      stopAutoSave();
      clearInterval(timerInterval);
      clearTimeout(autoStopTimer);
      if (isNative) NativeBridge.stopNativeRecording();
      else if (mediaRecorder && mediaRecorder.state !== 'inactive') { try { mediaRecorder.stop(); } catch(e) {} }
      mediaRecorder = null; audioChunks = [];
      recordingStartTime = null;
    }
    clearActiveSession();

    const id = generateId();
    const settings = loadSettings();
    const sampleRate = parseInt(settings.audioQuality) || 44100;

    currentSession = {
      id,
      title: dom.meetingTitle.value.trim() || `会议 ${formatDate(Date.now())}`,
      startTime: Date.now(), duration: 0, notes: [], hasAudio: false,
      nativeAudioFile: null,
    };

    if (isNative) {
      const audioFilename = id + '_recording.m4a';
      const recQuality = settings.audioQuality || 'standard';
      NativeBridge.startNativeRecordingWithQuality(audioFilename, recQuality);
      currentSession.nativeAudioFile = audioFilename;
      currentSession.hasAudio = true;
    } else {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, sampleRate: sampleRate }
        });
        let mimeType = '';
        for (const mt of ['audio/mp4','audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus']) {
          if (MediaRecorder.isTypeSupported(mt)) { mimeType = mt; break; }
        }
        audioChunks = [];
        mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
        mediaRecorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data); };
        // Capture session reference NOW so async onstop doesn't use a replaced currentSession
        const sessionRef = currentSession;
        mediaRecorder.onstop = async () => {
          stream.getTracks().forEach(t => t.stop());
          if (audioChunks.length) {
            const blob = new Blob(audioChunks, { type: mediaRecorder.mimeType || 'audio/webm' });
            await saveAudio(sessionRef.id, blob);
            sessionRef.hasAudio = true;
          }
          // Only call finishRecording if this session is still the current one
          if (currentSession && currentSession.id === sessionRef.id) {
            finishRecording();
          } else {
            // Session was replaced (user started new recording) — save orphaned session
            if (!sessions.find(s => s.id === sessionRef.id)) {
              sessions.push(JSON.parse(JSON.stringify(sessionRef)));
              saveSessions();
            }
          }
        };
        mediaRecorder.start(1000);
      } catch (err) {
        console.warn('Mic denied, notes-only mode:', err);
        showToast('\u9EA6\u514B\u98CE\u6743\u9650\u88AB\u62D2\u7EDD\uFF0C\u4EC5\u7B14\u8BB0\u6A21\u5F0F');
      }
    }

    recordingStartTime = Date.now();
    const stealth = loadSettings().stealthMode === 'on';
    timerInterval = setInterval(updateTimer, 1000);
    if (stealth) {
      // Stealth: hide all recording indicators, stop button looks like a normal nav
      dom.statusDot.classList.add('hidden');
      dom.timer.classList.add('hidden');
      dom.stopBtn.classList.remove('hidden');
      dom.stopBtn.textContent = '完成';
      dom.stopBtn.style.cssText = 'opacity:0.5;font-size:13px;padding:4px 10px;border-color:transparent;';
    } else {
      dom.statusDot.classList.remove('hidden');
      dom.timer.classList.remove('hidden');
      dom.stopBtn.classList.remove('hidden');
      dom.stopBtn.textContent = '完成';
      dom.stopBtn.style.cssText = '';
    }
    dom.currentTitle.textContent = stealth ? '' : currentSession.title;
    dom.notesEntries.innerHTML = '';
    dom.notesEntries.appendChild(dom.emptyHint);
    dom.emptyHint.classList.remove('hidden');
    dom.noteInput.value = '';
    dom.sendBtn.disabled = true;

    // Start real-time auto-save
    saveActiveSession();
    saveActiveState(true);
    startAutoSave();

    navigateTo('notes-screen');
    setTimeout(() => dom.noteInput.focus(), 300);
    requestWakeLock();

    // Auto-stop timer
    const autoStopMin = parseInt(settings.autoStop) || 0;
    if (autoStopMin > 0) {
      clearTimeout(autoStopTimer);
      autoStopTimer = setTimeout(() => {
        if (recordingStartTime) {
          showToast(`\u5DF2\u81EA\u52A8\u505C\u6B62\u5F55\u97F3 (${autoStopMin}\u5206\u949F)`);
          stopRecording();
        }
      }, autoStopMin * 60 * 1000);
    }
  }

  function updateTimer() {
    if (recordingStartTime) dom.timer.textContent = formatTime(Date.now() - recordingStartTime);
  }

  function stopRecording() {
    clearInterval(timerInterval); timerInterval = null;
    clearTimeout(autoStopTimer); autoStopTimer = null;
    if (currentSession) currentSession.duration = Date.now() - recordingStartTime;
    saveActiveSession(); // save final state before finishing
    dom.statusDot.classList.add('hidden');
    dom.timer.classList.add('hidden');
    dom.stopBtn.classList.add('hidden');

    releaseWakeLock();
    if (isNative) {
      NativeBridge.stopNativeRecording();
      finishRecording();
    } else if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      mediaRecorder.stop();
    } else {
      finishRecording();
    }
  }

  function finishRecording() {
    stopAutoSave();
    recordingStartTime = null;
    // Deep copy to prevent reference sharing between sessions
    const finishedSession = JSON.parse(JSON.stringify({
      ...currentSession,
      notes: currentSession.notes.map(n => { const { imageDataUrl, ...rest } = n; return rest; }),
    }));
    // Avoid duplicate: check if already in sessions array
    if (!sessions.find(s => s.id === finishedSession.id)) {
      sessions.push(finishedSession);
    } else {
      // Update existing entry in place
      const idx = sessions.findIndex(s => s.id === finishedSession.id);
      if (idx >= 0) sessions[idx] = finishedSession;
    }
    saveSessions();
    clearActiveSession();
    openReview(finishedSession); // this sets currentSession = finishedSession
    // Do NOT null out currentSession here — Review screen needs it for export/share
    mediaRecorder = null; audioChunks = [];
    renderSessionList(dom.sessionList, false, 5);
  }

  // --- Notes ---
  function addImageNote(file) {
    if (!currentSession || !file) return;
    const timestamp = recordingStartTime ? Date.now() - recordingStartTime : (currentSession.duration || 0);
    const imgFilename = `${currentSession.id}_img_${Date.now()}.jpg`;

    // Read file, compress, save
    const reader = new FileReader();
    reader.onload = async (e) => {
      // Compress image
      const img = new Image();
      img.onload = async () => {
        const canvas = document.createElement('canvas');
        const maxDim = 1600;
        let w = img.width, h = img.height;
        if (w > maxDim || h > maxDim) {
          if (w > h) { h = h * maxDim / w; w = maxDim; }
          else { w = w * maxDim / h; h = maxDim; }
        }
        canvas.width = w; canvas.height = h;
        canvas.getContext('2d').drawImage(img, 0, 0, w, h);

        const dataUrl = canvas.toDataURL('image/jpeg', 0.8);
        const b64 = dataUrl.split(',')[1];

        // Save to disk via NativeBridge
        if (isNative) {
          NativeBridge.saveFile(imgFilename, b64);
        }

        // Save to IDB for web fallback
        try {
          const db = await openAudioDB();
          await idbPut(db, 'audio', { id: imgFilename, blob: dataUrlToBlob(dataUrl), type: 'image/jpeg' });
        } catch(e) { console.warn('idb image save:', e); }

        const note = {
          timestamp,
          text: `![${imgFilename}]`,
          type: 'image',
          imageFile: imgFilename,
          imageDataUrl: dataUrl, // for display
          createdAt: Date.now(),
        };
        currentSession.notes.push(note);
        saveSessions();
        saveActiveSession(); // real-time save
        dom.emptyHint.classList.add('hidden');
        renderNoteEntry(note);
        dom.notesEntries.scrollTop = dom.notesEntries.scrollHeight;
      };
      img.src = e.target.result;
    };
    reader.readAsDataURL(file);
  }

  function dataUrlToBlob(dataUrl) {
    const parts = dataUrl.split(',');
    const mime = parts[0].match(/:(.*?);/)[1];
    const b64 = atob(parts[1]);
    const arr = new Uint8Array(b64.length);
    for (let i = 0; i < b64.length; i++) arr[i] = b64.charCodeAt(i);
    return new Blob([arr], { type: mime });
  }

  function addNote(text) {
    if (!text.trim() || !currentSession) return;
    const elapsed = recordingStartTime ? Date.now() - recordingStartTime : (currentSession.duration || 0);
    const note = { timestamp: elapsed, text: text.trim(), createdAt: Date.now() };
    currentSession.notes.push(note);
    dom.emptyHint.classList.add('hidden');
    renderNoteEntry(note);
    dom.noteInput.value = '';
    dom.sendBtn.disabled = true;
    autoResize();
    // Scroll to bottom smoothly, then refocus input without losing position
    requestAnimationFrame(() => {
      dom.notesEntries.scrollTop = dom.notesEntries.scrollHeight;
      dom.noteInput.focus();
    });
    saveActiveSession();
  }

  /** Render note text with inline markdown formatting */
  function renderFormattedText(raw) {
    let html = escapeHtml(raw);
    // **bold**
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    // ==highlight==
    html = html.replace(/==(.+?)==/g, '<span class="note-highlight">$1</span>');
    // Lines starting with "- " → bullet
    html = html.replace(/^- (.+)$/gm, '<span class="note-bullet">•</span>$1');
    // Lines starting with "[] " or "[ ] " → action item
    html = html.replace(/^\[\s?\] (.+)$/gm, '<span class="note-action">$1</span>');
    // Lines starting with "# " → heading
    html = html.replace(/^# (.+)$/gm, '<span class="note-heading">$1</span>');
    return html;
  }

  function renderNoteEntry(note) {
    const entry = document.createElement('div');
    entry.className = 'note-entry';
    if (note.type === 'image' && (note.imageDataUrl || note.imageFile)) {
      const imgSrc = note.imageDataUrl || '';
      entry.innerHTML = `
        <span class="note-timestamp">${formatTimestamp(note.timestamp)}</span>
        <div class="note-image">
          ${imgSrc ? `<img src="${imgSrc}" alt="">` : `<span style="color:var(--text-muted)">📷</span>`}
          <div class="note-img-actions"><span class="img-action-btn" data-action="edit">标注</span></div>
        </div>`;
      // Tap image → view full screen
      const img = entry.querySelector('img');
      if (img && note.imageFile && isNative) {
        img.addEventListener('click', () => NativeBridge.openImageViewer(note.imageFile));
        // Long-press → edit/annotate
        let longTimer;
        img.addEventListener('touchstart', () => {
          longTimer = setTimeout(() => {
            const cb = 'qnEdit' + Date.now();
            window[cb] = function(result) {
              delete window[cb];
              if (result && result.dataUrl) { img.src = result.dataUrl; note.imageDataUrl = result.dataUrl; }
            };
            NativeBridge.openImageEditor(note.imageFile, cb);
          }, 500);
        }, { passive: true });
        img.addEventListener('touchend', () => clearTimeout(longTimer), { passive: true });
        img.addEventListener('touchmove', () => clearTimeout(longTimer), { passive: true });
      }
      // "标注" link
      entry.querySelector('[data-action="edit"]')?.addEventListener('click', e => {
        e.stopPropagation();
        if (!note.imageFile || !isNative) return;
        const cb = 'qnEdit' + Date.now();
        window[cb] = function(result) {
          delete window[cb];
          if (result && result.dataUrl) { const im = entry.querySelector('img'); if (im) im.src = result.dataUrl; note.imageDataUrl = result.dataUrl; }
        };
        NativeBridge.openImageEditor(note.imageFile, cb);
      });
    } else {
      entry.innerHTML = `
        <span class="note-timestamp">${formatTimestamp(note.timestamp)}</span>
        <span class="note-text">${renderFormattedText(note.text)}</span>`;
    }
    let startX = 0, curX = 0;
    entry.addEventListener('touchstart', e => { startX = e.touches[0].clientX; }, { passive: true });
    entry.addEventListener('touchmove', e => {
      curX = e.touches[0].clientX - startX;
      if (curX < 0) { entry.style.transform = `translateX(${Math.max(curX,-120)}px)`; entry.style.opacity = Math.max(0.3, 1 + curX/120); }
    }, { passive: true });
    entry.addEventListener('touchend', () => {
      if (curX < -80) {
        entry.style.transition = 'transform 0.2s, opacity 0.2s';
        entry.style.transform = 'translateX(-100%)'; entry.style.opacity = '0';
        setTimeout(() => {
          entry.remove();
          if (currentSession) {
            currentSession.notes = currentSession.notes.filter(n => n.timestamp !== note.timestamp);
            if (!currentSession.notes.length) dom.emptyHint.classList.remove('hidden');
          }
        }, 200);
      } else {
        entry.style.transform = ''; entry.style.opacity = '';
      }
      curX = 0;
    });
    dom.notesEntries.appendChild(entry);
  }

  // --- Review ---
  function openReview(session) {
    dom.reviewTitle.textContent = session.title || '\u672A\u547D\u540D\u4F1A\u8BAE';
    dom.reviewDuration.textContent = formatTime(session.duration);
    dom.reviewCount.textContent = `${session.notes.length} \u6761\u7B14\u8BB0`;
    dom.reviewNotes.innerHTML = '';
    if (!session.notes.length) {
      dom.reviewNotes.innerHTML = '<p class="empty-list-hint">\u6CA1\u6709\u7B14\u8BB0</p>';
    }
    session.notes.forEach(n => {
      const e = document.createElement('div');
      e.className = 'review-note-entry';
      if (n.type === 'image' && n.imageFile) {
        let imgSrc = n.imageDataUrl || '';
        if (!imgSrc && isNative) {
          try {
            const b64 = NativeBridge.readFileBase64(n.imageFile);
            if (b64) imgSrc = 'data:image/jpeg;base64,' + b64;
          } catch(err) { console.warn('img load:', err); }
        }
        e.innerHTML = `<span class="review-timestamp">${formatTimestamp(n.timestamp)}</span><div class="review-note-image">${imgSrc ? `<img src="${imgSrc}" alt="photo">` : '<span style="color:var(--text-muted)">[图片]</span>'}</div>`;
      } else {
        e.innerHTML = `<span class="review-timestamp">${formatTimestamp(n.timestamp)}</span><span class="review-text">${renderFormattedText(n.text)}</span>`;
      }
      dom.reviewNotes.appendChild(e);
    });
    if (dom.transcribeActionRow) {
      const showTranscribe = isNative && !!session.nativeAudioFile;
      dom.transcribeActionRow.classList.toggle('hidden', !showTranscribe);
      if (showTranscribe) {
        dom.transcribeActionRow.style.display = 'contents';
      }
      if (showTranscribe && dom.transcribeEntryBtn) {
        dom.transcribeEntryBtn.textContent = session.transcription ? '\u91CD\u65B0\u8F6C\u5F55' : '\u672C\u5730\u8F6C\u5F55';
      }
    }
    currentSession = session;
    navigateTo('review-screen');
  }

  // --- ZIP builder ---
  function buildZip(files) {
    const enc = new TextEncoder();
    function u16(n) { const b = new Uint8Array(2); new DataView(b.buffer).setUint16(0,n,true); return b; }
    function u32(n) { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0,n,true); return b; }
    function crc32(data) {
      if (!crc32.t) { crc32.t = new Uint32Array(256); for (let i=0;i<256;i++){let c=i;for(let j=0;j<8;j++)c=c&1?0xEDB88320^(c>>>1):c>>>1;crc32.t[i]=c;} }
      let c=0xFFFFFFFF; for(let i=0;i<data.length;i++) c=crc32.t[(c^data[i])&0xFF]^(c>>>8); return (c^0xFFFFFFFF)>>>0;
    }
    function cat(arrays) { const n=arrays.reduce((s,a)=>s+a.length,0); const o=new Uint8Array(n); let p=0; for(const a of arrays){o.set(a,p);p+=a.length;} return o; }
    const parts=[], cd=[]; let off=0;
    for (const f of files) {
      const nb=enc.encode(f.name), crc=crc32(f.data);
      const lh=cat([new Uint8Array([0x50,0x4B,0x03,0x04]),u16(20),u16(0),u16(0),u16(0),u16(0),u32(crc),u32(f.data.length),u32(f.data.length),u16(nb.length),u16(0),nb]);
      cd.push({nb,crc,sz:f.data.length,off}); off+=lh.length+f.data.length;
      parts.push(lh,f.data);
    }
    const cdStart=off;
    for (const {nb,crc,sz,off:fo} of cd) {
      parts.push(cat([new Uint8Array([0x50,0x4B,0x01,0x02]),u16(20),u16(20),u16(0),u16(0),u16(0),u16(0),u32(crc),u32(sz),u32(sz),u16(nb.length),u16(0),u16(0),u16(0),u16(0),u32(0),u32(fo),nb]));
    }
    const cdSz=parts.slice(files.length * 2).reduce((s,a)=>s+a.length,0);
    parts.push(cat([new Uint8Array([0x50,0x4B,0x05,0x06]),u16(0),u16(0),u16(files.length),u16(files.length),u32(cdSz),u32(cdStart),u16(0)]));
    return cat(parts);
  }

  async function buildSessionZip(session) {
    const prefix = sanitizeFilename(session.title);
    const zipFilename = `${prefix}_quicknote.zip`;
    let md = `# ${session.title||'\u672A\u547D\u540D\u4F1A\u8BAE'}\n\n`;
    md += `- \u65E5\u671F: ${new Date(session.startTime).toLocaleString('zh-CN')}\n`;
    md += `- \u65F6\u957F: ${formatTime(session.duration)}\n`;
    md += `- \u7B14\u8BB0\u6570: ${session.notes.length}\n\n## \u7B14\u8BB0\n\n`;
    const imageFiles = [];
    session.notes.forEach(n => {
      if (n.type === 'image' && n.imageFile) {
        md += `**[${formatTimestamp(n.timestamp)}]** 📷 (见附件 images/${n.imageFile})\n\n`;
        imageFiles.push(n.imageFile);
      } else {
        md += `**[${formatTimestamp(n.timestamp)}]** ${n.text}\n\n`;
      }
    });
    const analysis = JSON.stringify({
      session: { title:session.title, date:new Date(session.startTime).toISOString(), duration:formatTime(session.duration) },
      notes: session.notes.map(n => ({
        t: formatTimestamp(n.timestamp),
        text: n.type === 'image' ? `[图片: ${n.imageFile}]` : n.text,
        type: n.type || 'text',
        imageFile: n.imageFile || null,
      })),
      images: imageFiles,
      instructions: ['1. 将录音转为完整transcript','2. 与笔记按时间戳对齐','3. 笔记是重点，transcript是上下文','4. 图片附件（白板/幻灯片等）请描述关键内容并融入纪要','输出: 会议纪要 + 重点标注 + 笔记未记但重要的内容 + 图片内容解读']
    }, null, 2);
    if (isNative) {
      // Use new method with image filenames
      const imageList = imageFiles.join(',');
      const result = await new Promise((resolve, reject) => {
        const cb = 'qnZip' + Date.now();
        window[cb] = (resp) => {
          delete window[cb];
          if (typeof resp === 'object' && resp.parts) {
            // Split ZIP response
            resolve({ size: resp.size, parts: resp.parts });
          } else if (typeof resp === 'number' && resp > 0) {
            resolve({ size: resp, parts: 1 });
          } else {
            reject(new Error('ZIP build failed'));
          }
        };
        NativeBridge.buildZipAndSaveWithImages(md, analysis, session.nativeAudioFile || '', session.transcription || '', imageList, zipFilename, cb);
      });
      return { zipFilename, fileSize: result.size, parts: result.parts, native: true };
    }
    const enc = new TextEncoder();
    const files = [
      { name: `${prefix}_notes.md`, data: enc.encode(md) },
      { name: `${prefix}_for_claude.json`, data: enc.encode(analysis) },
    ];
    try {
      const audioData = await getAudio(session.id);
      if (audioData && audioData.blob) {
        const buf = await audioData.blob.arrayBuffer();
        const ext = audioData.type.includes('mp4') ? 'm4a' : audioData.type.includes('webm') ? 'webm' : 'ogg';
        files.push({ name: `${prefix}_recording.${ext}`, data: new Uint8Array(buf) });
      }
    } catch(e) { console.warn('No audio:', e); }
    const zip = buildZip(files);
    return { blob: new Blob([zip], { type:'application/zip' }), zipFilename, fileCount: files.length };
  }

  async function buildNotesText(session) {
    let md = `# ${session.title||'\u672A\u547D\u540D\u4F1A\u8BAE'}\n\n`;
    md += `\u65E5\u671F: ${new Date(session.startTime).toLocaleString('zh-CN')}\n`;
    md += `\u65F6\u957F: ${formatTime(session.duration)}\n`;
    md += `\u7B14\u8BB0\u6570: ${session.notes.length}\n\n`;
    session.notes.forEach(n => { md += `[${formatTimestamp(n.timestamp)}] ${n.text}\n\n`; });
    if (session.transcription) {
      md += `---\n\n## \u8F6C\u5F55\u6587\u5B57\u7A3F\n\n${session.transcription}\n`;
    }
    return md;
  }

  async function exportSession() {
    if (!currentSession) return;
    const settings = loadSettings();

    if (settings.exportFormat === 'text') {
      showToast('\u6B63\u5728\u5BFC\u51FA...', 60000);
      try {
        const text = await buildNotesText(currentSession);
        document.querySelector('.toast')?.remove();
        const filename = sanitizeFilename(currentSession.title) + '_notes.md';
        if (isNative) {
          NativeBridge.saveText(filename, text);
          showToast('\u5DF2\u4FDD\u5B58: ' + filename);
        } else {
          const blob = new Blob([text], { type: 'text/markdown' });
          await downloadBlob(blob, filename);
        }
      } catch(e) {
        document.querySelector('.toast')?.remove();
        showToast('\u5BFC\u51FA\u5931\u8D25: ' + e.message);
      }
      return;
    }

    showToast('正在打包...', 60000);
    try {
      const result = await buildSessionZip(currentSession);
      document.querySelector('.toast')?.remove();
      if (result.native) {
        if (result.parts > 1) {
          showToast(`文件已分为 ${result.parts} 份 (每份≤95MB)`, 3000);
          for (let i = 1; i <= result.parts; i++) {
            const partName = result.zipFilename.replace('.zip', `_part${i}.zip`);
            await saveExportRecord(currentSession, partName, 0);
          }
        } else {
          await saveExportRecord(currentSession, result.zipFilename, result.fileSize);
          showToast('已保存: ' + result.zipFilename);
        }
      } else {
        await saveExport(currentSession, result.blob, result.zipFilename);
        await downloadBlob(result.blob, result.zipFilename);
      }
    } catch(e) {
      document.querySelector('.toast')?.remove();
      showToast('导出失败: ' + e.message);
      console.error(e);
    }
  }

  async function shareSession() {
    if (!currentSession) return;
    const settings = loadSettings();

    // Text-only mode: share markdown file
    if (settings.exportFormat === 'text') {
      showToast('正在导出...', 60000);
      try {
        const text = await buildNotesText(currentSession);
        document.querySelector('.toast')?.remove();
        const filename = sanitizeFilename(currentSession.title) + '_notes.md';
        if (isNative) {
          NativeBridge.saveText(filename, text);
          NativeBridge.shareTextFile(filename);
        } else {
          const blob = new Blob([text], { type: 'text/markdown' });
          await downloadBlob(blob, filename);
        }
      } catch(e) {
        document.querySelector('.toast')?.remove();
        showToast('分享失败: ' + e.message);
      }
      return;
    }

    showToast('正在打包...', 60000);
    try {
      const result = await buildSessionZip(currentSession);
      document.querySelector('.toast')?.remove();
      if (result.native) {
        if (result.parts > 1) {
          for (let i = 1; i <= result.parts; i++) {
            const partName = result.zipFilename.replace('.zip', `_part${i}.zip`);
            await saveExportRecord(currentSession, partName, 0);
            showToast(`分享第 ${i}/${result.parts} 份...`, 5000);
            NativeBridge.shareFile(partName);
            if (i < result.parts) await new Promise(r => setTimeout(r, 2000));
          }
          return;
        }
        await saveExportRecord(currentSession, result.zipFilename, result.fileSize);
        NativeBridge.shareFile(result.zipFilename);
        return;
      }
      await saveExport(currentSession, result.blob, result.zipFilename);
      if (navigator.canShare && navigator.canShare({ files: [new File([result.blob], result.zipFilename, { type:'application/zip' })] })) {
        const file = new File([result.blob], result.zipFilename, { type:'application/zip' });
        await navigator.share({ files: [file], title: currentSession.title || 'QuickNote', text: '\u4F1A\u8BAE\u8BB0\u5F55' });
      } else if (navigator.share) {
        await navigator.share({ title: currentSession.title || 'QuickNote', text: '\u4F1A\u8BAE\u8BB0\u5F55\u5DF2\u51C6\u5907\u597D\uFF0C\u8BF7\u4F7F\u7528\u5BFC\u51FA\u529F\u80FD\u4E0B\u8F7D\u3002' });
      } else {
        await downloadBlob(result.blob, result.zipFilename);
        showToast('\u5DF2\u4E0B\u8F7D (\u5206\u4EAB\u529F\u80FD\u4E0D\u652F\u6301\u6B64\u6D4F\u89C8\u5668)');
      }
    } catch(e) {
      document.querySelector('.toast')?.remove();
      if (e.name !== 'AbortError') showToast('\u64CD\u4F5C\u5931\u8D25: ' + e.message);
    }
  }

  async function saveExportRecord(session, filename, fileSize) {
    try {
      const db = await openExportsDB();
      await idbPut(db, 'exports', {
        id: generateId(), sessionId: session.id,
        title: session.title || '\u672A\u547D\u540D\u4F1A\u8BAE',
        filename, blob: null, nativeFile: true,
        exportedAt: Date.now(), fileSize: fileSize || 0,
      });
    } catch(e) { console.warn('Could not save export record:', e); }
  }

  async function saveExport(session, blob, filename) {
    try {
      const db = await openExportsDB();
      await idbPut(db, 'exports', {
        id: generateId(), sessionId: session.id,
        title: session.title || '\u672A\u547D\u540D\u4F1A\u8BAE',
        filename, blob, exportedAt: Date.now(), fileSize: blob.size,
      });
    } catch(e) { console.warn('Could not save export:', e); }
  }

  async function renderExportsList() {
    dom.exportsList.innerHTML = '<p class="empty-list-hint">\u52A0\u8F7D\u4E2D...</p>';
    try {
      const db = await openExportsDB();
      const exports = await idbGetAll(db, 'exports');
      exports.sort((a,b) => b.exportedAt - a.exportedAt);
      dom.exportsList.innerHTML = '';
      if (!exports.length) { dom.exportsList.innerHTML = '<p class="empty-list-hint">\u8FD8\u6CA1\u6709\u5BFC\u51FA\u8BB0\u5F55</p>'; return; }
      for (const ex of exports) {
        const card = document.createElement('div');
        card.className = 'export-card';
        const kb = (ex.fileSize / 1024).toFixed(0);
        card.innerHTML = `
          <div class="export-info">
            <span class="export-name">${escapeHtml(ex.title)}</span>
            <span class="export-meta">${formatDate(ex.exportedAt)} \u00B7 ${kb} KB</span>
          </div>
          <div class="export-actions">
            <button class="export-action-btn share-export-btn" data-id="${ex.id}">\u5206\u4EAB</button>
            <button class="export-action-btn dl-export-btn" data-id="${ex.id}">\u2193</button>
            <button class="export-action-btn del-export-btn" data-id="${ex.id}">\u2715</button>
          </div>`;
        card.querySelector('.share-export-btn').addEventListener('click', async (e) => {
          e.stopPropagation();
          const data = await idbGet(await openExportsDB(), 'exports', ex.id);
          if (!data) { showToast('\u6587\u4EF6\u5DF2\u4E22\u5931'); return; }
          if (data.nativeFile) { NativeBridge.shareFile(data.filename); return; }
          if (isNative && data.blob) {
            const b64 = await blobToBase64(data.blob);
            NativeBridge.saveFile(data.filename, b64);
            NativeBridge.shareFile(data.filename);
            return;
          }
          const file = new File([data.blob], data.filename, { type:'application/zip' });
          if (navigator.canShare && navigator.canShare({ files:[file] })) {
            await navigator.share({ files:[file], title: data.title });
          } else {
            await downloadBlob(data.blob, data.filename);
            showToast('\u5DF2\u4E0B\u8F7D\uFF08\u5F53\u524D\u6D4F\u89C8\u5668\u4E0D\u652F\u6301\u6587\u4EF6\u5206\u4EAB\uFF09');
          }
        });
        card.querySelector('.dl-export-btn').addEventListener('click', async (e) => {
          e.stopPropagation();
          const data = await idbGet(await openExportsDB(), 'exports', ex.id);
          if (!data) { showToast('\u6587\u4EF6\u5DF2\u4E22\u5931'); return; }
          if (data.nativeFile) { NativeBridge.shareFile(data.filename); return; }
          await downloadBlob(data.blob, data.filename);
        });
        card.querySelector('.del-export-btn').addEventListener('click', async (e) => {
          e.stopPropagation();
          if (!confirm('\u5220\u9664\u6B64\u5BFC\u51FA\u8BB0\u5F55\uFF1F')) return;
          const db2 = await openExportsDB();
          await idbDelete(db2, 'exports', ex.id);
          card.remove();
          if (!dom.exportsList.querySelectorAll('.export-card').length)
            dom.exportsList.innerHTML = '<p class="empty-list-hint">\u8FD8\u6CA1\u6709\u5BFC\u51FA\u8BB0\u5F55</p>';
        });
        dom.exportsList.appendChild(card);
      }
    } catch(e) {
      dom.exportsList.innerHTML = '<p class="empty-list-hint">\u52A0\u8F7D\u5931\u8D25</p>';
      console.error(e);
    }
  }

  // == Settings Screen ==

  function openSettingsScreen() {
    applySettingsToUI();
    renderSettingsModels();
    navigateTo('settings-screen');
  }

  /** Renders model cards (Whisper x N + Diarization x 1). Listener is on the container, set once in init(). */
  function renderSettingsModels() {
    const container = dom.settingsModelList;
    if (!container || !isNative) return;

    let whisperModels;
    try { whisperModels = JSON.parse(NativeBridge.getWhisperModels()); } catch { whisperModels = []; }

    let diarStatus;
    try { diarStatus = JSON.parse(NativeBridge.getDiarizationModelStatus()); } catch { diarStatus = { downloaded: false, sizeMb: 37 }; }

    container.innerHTML = '';

    // -- Section: Whisper models --
    const h1 = document.createElement('p');
    h1.className = 'section-title'; h1.style.marginBottom = '8px'; h1.textContent = '\u8BED\u97F3\u8F6C\u5F55\u6A21\u578B (Whisper)';
    container.appendChild(h1);

    whisperModels.forEach(m => {
      const card = document.createElement('div');
      card.className = 'model-card';
      card.dataset.modelId = m.id;

      // Check if there's an active download for this model
      const dlState = downloadProgress[m.id];
      let actionHtml = '';
      let progressHtml = '';

      if (dlState && dlState.active) {
        actionHtml = `<button class="model-dl-btn" data-action="download-whisper" data-mid="${m.id}" disabled>\u4E0B\u8F7D\u4E2D...</button>`;
        progressHtml = buildModelProgressHtml(dlState);
      } else if (m.downloaded) {
        actionHtml = `<button class="model-del-btn" data-action="delete-whisper" data-mid="${m.id}">\u5220\u9664\u6A21\u578B</button>`;
      } else {
        actionHtml = `<button class="model-dl-btn" data-action="download-whisper" data-mid="${m.id}">\u4E0B\u8F7D (~${m.sizeMb}MB)</button>`;
      }

      card.innerHTML = `
        <div class="model-card-header">
          <span class="model-card-name">${escapeHtml(m.name)}</span>
          <span class="model-badge ${m.downloaded ? 'downloaded' : ''}">${m.downloaded ? '\u2713 \u5DF2\u4E0B\u8F7D' : '\u672A\u4E0B\u8F7D'}</span>
        </div>
        <div class="model-action-row">${actionHtml}</div>
        <div class="model-dl-progress" id="dl-progress-${m.id}">${progressHtml}</div>`;
      container.appendChild(card);
    });

    // -- Section: Diarization model --
    const h2 = document.createElement('p');
    h2.className = 'section-title'; h2.style.cssText = 'margin-top:20px;margin-bottom:8px'; h2.textContent = '\u8BF4\u8BDD\u4EBA\u8BC6\u522B\u6A21\u578B';
    container.appendChild(h2);

    const hint = document.createElement('p');
    hint.className = 'settings-hint'; hint.style.marginBottom = '12px';
    hint.textContent = '\u8F6C\u5F55\u65F6\u81EA\u52A8\u533A\u5206\u4E0D\u540C\u8BF4\u8BDD\u4EBA\uFF08A/B/C...\uFF09\u3002\u9700\u914D\u5408Whisper\u6A21\u578B\u4F7F\u7528\uFF0C\u7EA637MB\u3002';
    container.appendChild(hint);

    const dlStateDiar = downloadProgress['diarization'];
    let diarActionHtml = '';
    let diarProgressHtml = '';

    if (dlStateDiar && dlStateDiar.active) {
      diarActionHtml = `<button class="model-dl-btn" data-action="download-diarize" disabled>\u4E0B\u8F7D\u4E2D...</button>`;
      diarProgressHtml = buildModelProgressHtml(dlStateDiar);
    } else if (diarStatus.downloaded) {
      diarActionHtml = `<button class="model-del-btn" data-action="delete-diarize">\u5220\u9664\u6A21\u578B</button>`;
    } else {
      diarActionHtml = `<button class="model-dl-btn" data-action="download-diarize">\u4E0B\u8F7D (~37MB)</button>`;
    }

    const dc = document.createElement('div');
    dc.className = 'model-card';
    dc.innerHTML = `
      <div class="model-card-header">
        <span class="model-card-name">\u8BF4\u8BDD\u4EBA\u8BC6\u522B \u00B7 ~37MB</span>
        <span class="model-badge ${diarStatus.downloaded ? 'downloaded' : ''}">${diarStatus.downloaded ? '\u2713 \u5DF2\u4E0B\u8F7D' : '\u672A\u4E0B\u8F7D'}</span>
      </div>
      <div class="model-action-row">${diarActionHtml}</div>
      <div class="model-dl-progress" id="dl-progress-diarization">${diarProgressHtml}</div>`;
    container.appendChild(dc);
  }

  function buildModelProgressHtml(dlState) {
    const pct = dlState.percent || 0;
    const downloaded = dlState.bytesDownloaded || 0;
    const total = dlState.bytesTotal || 0;
    const speed = dlState.speed || '';
    // ETA calculation
    let eta = '';
    if (dlState.speedBps > 0 && total > downloaded) {
      const remainSecs = Math.ceil((total - downloaded) / dlState.speedBps);
      if (remainSecs < 60) eta = `${remainSecs}秒`;
      else if (remainSecs < 3600) eta = `${Math.ceil(remainSecs / 60)}分钟`;
      else eta = `${Math.floor(remainSecs / 3600)}小时${Math.ceil((remainSecs % 3600) / 60)}分`;
    }
    const etaText = eta ? ` · 约${eta}` : '';
    return `
      <div class="model-progress-bar-track">
        <div class="model-progress-bar-fill" style="width:${pct.toFixed(1)}%"></div>
      </div>
      <div class="model-progress-info">
        <span>${pct.toFixed(1)}% · ${formatFileSize(downloaded)} / ${total > 0 ? formatFileSize(total) : '?'}${etaText}</span>
        <span class="model-progress-speed">${speed}</span>
      </div>`;
  }

  function updateModelDownloadProgress(modelKey, data) {
    const progressEl = document.getElementById('dl-progress-' + modelKey);
    if (!progressEl) return;

    const dlState = downloadProgress[modelKey];
    if (!dlState) return;

    progressEl.innerHTML = buildModelProgressHtml(dlState);
  }

  // == Transcription ==

  function openTranscriptionScreen() {
    if (!isNative || !currentSession) return;
    mergedDocFilename = null;
    dom.transcribeBtn.disabled = true;
    dom.transcribeBtn.textContent = '\u5F00\u59CB\u8F6C\u5F55';
    dom.cancelTranscribeBtn.style.display = 'none';
    dom.transcriptionProgressWrap.classList.add('hidden');
    dom.transcriptionStatus.textContent = '';

    if (currentSession.transcription) {
      dom.transcriptionResult.classList.remove('hidden');
      dom.transcriptText.textContent = currentSession.transcription;
      dom.shareMergedBtn.classList.add('hidden');
    } else {
      dom.transcriptionResult.classList.add('hidden');
    }

    if (transcriptionKey) {
      dom.transcriptionProgressWrap.classList.remove('hidden');
      dom.cancelTranscribeBtn.style.display = '';
      dom.transcribeBtn.textContent = '\u8F6C\u5F55\u4E2D...';
    }

    populateModelSelect();
    navigateTo('transcription-screen');
  }

  function populateModelSelect() {
    if (!isNative) return;
    let models;
    try { models = JSON.parse(NativeBridge.getWhisperModels()); } catch { models = []; }
    const downloaded = models.filter(m => m.downloaded);

    if (!downloaded.length) {
      dom.transcriptionNoModel.classList.remove('hidden');
      dom.transcriptionHasModel.classList.add('hidden');
      selectedModelId = null;
      dom.transcribeBtn.disabled = true;
      return;
    }

    dom.transcriptionNoModel.classList.add('hidden');
    dom.transcriptionHasModel.classList.remove('hidden');

    dom.transcriptionModelSelect.innerHTML = '';
    downloaded.forEach(m => {
      const opt = document.createElement('option');
      opt.value = m.id; opt.textContent = m.name;
      dom.transcriptionModelSelect.appendChild(opt);
    });

    if (selectedModelId && downloaded.some(m => m.id === selectedModelId)) {
      dom.transcriptionModelSelect.value = selectedModelId;
    } else {
      selectedModelId = downloaded[0].id;
      dom.transcriptionModelSelect.value = selectedModelId;
    }

    // Show diarization toggle only if diarization model is downloaded
    let diarStatus;
    try { diarStatus = JSON.parse(NativeBridge.getDiarizationModelStatus()); } catch { diarStatus = { downloaded: false }; }
    dom.diarizeToggleRow.classList.toggle('hidden', !diarStatus.downloaded);

    dom.transcribeBtn.disabled = !!transcriptionKey;
  }

  function startTranscription() {
    if (!currentSession || !selectedModelId || transcriptionKey) return;
    const audioFilename = currentSession.nativeAudioFile;
    if (!audioFilename) { showToast('\u6CA1\u6709\u5F55\u97F3\u6587\u4EF6'); return; }

    const key          = 'tr_' + Date.now();
    const lang         = dom.whisperLangSelect.value;
    const diarize      = dom.diarizeToggle?.checked || false;
    const durationSecs = Math.floor((currentSession.duration || 0) / 1000);
    const estimatedMs  = getTranscriptEstimatedMs(selectedModelId, durationSecs, diarize);

    transcriptionKey = key;
    dom.transcribeBtn.disabled = true;
    dom.transcribeBtn.textContent = '\u8F6C\u5F55\u4E2D...';
    dom.cancelTranscribeBtn.style.display = '';
    dom.transcriptionProgressWrap.classList.remove('hidden');
    dom.transcriptionResult.classList.add('hidden');
    dom.shareMergedBtn.classList.add('hidden');
    mergedDocFilename = null;

    startProgressBar(estimatedMs, diarize);

    // New 6-param version: audioFilename, modelId, language, resultKey, durationSecs, diarize
    NativeBridge.startTranscription(audioFilename, selectedModelId, lang, key, durationSecs, diarize);

    transcriptionPoll = setInterval(pollTranscription, 3000);
  }

  function pollTranscription() {
    if (!transcriptionKey || !isNative) return;
    const result = NativeBridge.checkTranscriptionResult(transcriptionKey);
    if (!result) return;

    clearInterval(transcriptionPoll); transcriptionPoll = null;
    stopProgressBar(true);
    NativeBridge.clearTranscriptionResult(transcriptionKey);
    transcriptionKey = null;

    dom.cancelTranscribeBtn.style.display = 'none';
    dom.transcribeBtn.disabled = false;
    dom.transcribeBtn.textContent = '\u91CD\u65B0\u8F6C\u5F55';

    if (result.startsWith('error:')) {
      dom.transcriptionStatus.textContent = '\u8F6C\u5F55\u5931\u8D25: ' + result.slice(7);
      showToast('\u8F6C\u5F55\u5931\u8D25');
    } else {
      dom.transcriptionStatus.textContent = '\u8F6C\u5F55\u5B8C\u6210 \u2713';
      dom.transcriptText.textContent = result;
      dom.transcriptionResult.classList.remove('hidden');
      if (currentSession) {
        currentSession.transcription = result;
        saveSessions();
        if (dom.transcribeEntryBtn) dom.transcribeEntryBtn.textContent = '\u91CD\u65B0\u8F6C\u5F55';
      }
    }
  }

  function cancelTranscription() {
    if (!transcriptionKey) return;
    clearInterval(transcriptionPoll); transcriptionPoll = null;
    NativeBridge.stopTranscription();
    NativeBridge.clearTranscriptionResult(transcriptionKey);
    transcriptionKey = null;
    stopProgressBar(false);
    dom.cancelTranscribeBtn.style.display = 'none';
    dom.transcribeBtn.disabled = !selectedModelId;
    dom.transcribeBtn.textContent = '\u5F00\u59CB\u8F6C\u5F55';
    dom.transcriptionStatus.textContent = '\u8F6C\u5F55\u5DF2\u53D6\u6D88';
  }

  function getTranscriptEstimatedMs(modelId, durationSecs, diarize) {
    // Conservative: mobile is much slower than desktop benchmarks
    const factor = modelId === 'large-v3-turbo' ? 3 : modelId === 'small' ? 5 : 10;
    const base = Math.max(15, Math.ceil(durationSecs / factor));
    return (diarize ? base * 2.5 : base) * 1000;
  }

  function startProgressBar(estimatedMs, diarize) {
    clearInterval(progressTimer);
    progressStartMs     = Date.now();
    progressEstimatedMs = estimatedMs;
    if (dom.transcriptionProgressBar) dom.transcriptionProgressBar.style.width = '0%';
    progressTimer = setInterval(() => {
      const elapsed  = Date.now() - progressStartMs;
      const elapsedS = Math.floor(elapsed / 1000);
      // Asymptotic progress: never stalls at a fixed cap.
      // Before estimate: linear to 80%. After estimate: slow log curve toward 99%.
      let pct;
      if (elapsed <= progressEstimatedMs) {
        pct = (elapsed / progressEstimatedMs) * 80;
      } else {
        // Overtime: 80 + 19 * (1 - 1/(1 + overtimeRatio))  → approaches 99 but never reaches it
        const overtimeRatio = (elapsed - progressEstimatedMs) / progressEstimatedMs;
        pct = 80 + 19 * (1 - 1 / (1 + overtimeRatio * 1.5));
      }
      if (dom.transcriptionProgressBar) dom.transcriptionProgressBar.style.width = pct.toFixed(1) + '%';
      if (dom.transcriptionStatus) {
        const phase = diarize && elapsed < progressEstimatedMs / 3 ? '分析说话人中...' : '转录中...';
        if (elapsed <= progressEstimatedMs) {
          const remainS = Math.max(0, Math.ceil((progressEstimatedMs - elapsed) / 1000));
          dom.transcriptionStatus.textContent = `${phase} 已用时 ${elapsedS}秒，约还需 ${remainS}秒`;
        } else {
          dom.transcriptionStatus.textContent = `${phase} 已用时 ${elapsedS}秒，比预计时间长，仍在处理中...`;
        }
      }
    }, 1000);
  }

  function stopProgressBar(success) {
    clearInterval(progressTimer); progressTimer = null;
    if (dom.transcriptionProgressBar) dom.transcriptionProgressBar.style.width = success ? '100%' : '0%';
  }

  // == Notes Fusion ==

  function generateMergedDoc(session, transcriptText) {
    const title    = session.title || '\u672A\u547D\u540D\u4F1A\u8BAE';
    const date     = new Date(session.startTime).toLocaleString('zh-CN');
    const duration = formatTime(session.duration);

    let doc = `# ${title} \u2014 \u878D\u5408\u7EAA\u8981\n\n`;
    doc += `**\u65E5\u671F**: ${date}  |  **\u65F6\u957F**: ${duration}  |  **\u7B14\u8BB0**: ${session.notes.length}\u6761\n\n---\n\n`;

    // Section 1: Transcript
    doc += `## \u8F6C\u5F55\u6587\u5B57\u7A3F\n\n`;
    doc += (transcriptText || '\uFF08\u65E0\u8F6C\u5F55\u6587\u5B57\u7A3F\uFF09') + '\n\n---\n\n';

    // Section 2: Hand-written notes
    doc += `## \u624B\u52A8\u7B14\u8BB0\n\n`;
    if (session.notes.length) {
      doc += `| \u65F6\u95F4 | \u5185\u5BB9 |\n|------|------|\n`;
      session.notes.forEach(n => {
        doc += `| ${formatTimestamp(n.timestamp)} | ${n.text} |\n`;
      });
    } else {
      doc += '\uFF08\u65E0\u624B\u52A8\u7B14\u8BB0\uFF09\n';
    }
    doc += '\n---\n\n';

    // Section 3: Timeline (interleaved)
    doc += `## \u65F6\u95F4\u8F74\u878D\u5408\n\n`;
    doc += `> \u2605 = \u624B\u52A8\u7B14\u8BB0\n\n`;

    const segRegex = /^\[([^\]]+?)\s+(\d+:\d{2})-\d+:\d{2}\]\s+(.+)$/;
    const items    = [];

    if (transcriptText) {
      transcriptText.split('\n').forEach(line => {
        const m = line.trim().match(segRegex);
        if (m) {
          const [, speaker, startStr, text] = m;
          const secs = parseTimestampStr(startStr);
          items.push({ time: secs, type: 'seg', speaker, text });
        } else if (line.trim() && !transcriptText.includes('[\u8BF4\u8BDD\u4EBA')) {
          items.push({ time: 0, type: 'seg', speaker: null, text: line.trim() });
        }
      });
    }

    session.notes.forEach(n => {
      items.push({ time: n.timestamp / 1000, type: 'note', text: n.text });
    });

    items.sort((a, b) => a.time - b.time);
    items.forEach(item => {
      const t = formatTimestampSecs(item.time);
      if (item.type === 'note') {
        doc += `**[${t}] \u2605 ${item.text}**\n`;
      } else {
        const sp = item.speaker ? `[${item.speaker}] ` : '';
        doc += `[${t}] ${sp}${item.text}\n`;
      }
    });

    doc += `\n---\n*\u7531 QuickNote \u751F\u6210 \u00B7 ${new Date().toLocaleDateString('zh-CN')}*\n`;
    return doc;
  }

  function parseTimestampStr(str) {
    const parts = str.split(':');
    return parseInt(parts[0]) * 60 + parseInt(parts[1]);
  }

  function mergeNotes() {
    if (!currentSession || !isNative) return;
    const transcript = currentSession.transcription || '';
    const docContent = generateMergedDoc(currentSession, transcript);
    const filename   = sanitizeFilename(currentSession.title) + '_merged.md';

    NativeBridge.saveText(filename, docContent);
    mergedDocFilename = filename;
    dom.shareMergedBtn.classList.remove('hidden');
    showToast('\u878D\u5408\u6587\u6863\u5DF2\u751F\u6210');
  }

  function shareMergedDoc() {
    if (!mergedDocFilename || !isNative) return;
    NativeBridge.shareTextFile(mergedDocFilename);
  }

  // --- Download helper ---
  async function downloadBlob(blob, filename) {
    if (isNative) {
      const b64 = await blobToBase64(blob);
      NativeBridge.saveFile(filename, b64);
      return;
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function autoResize() {
    const el = dom.noteInput;
    el.style.height = '44px'; // reset to single line
    el.style.height = Math.min(el.scrollHeight, 160) + 'px';
  }

  let wakeLock = null;
  async function requestWakeLock() { try { if ('wakeLock' in navigator) wakeLock = await navigator.wakeLock.request('screen'); } catch {} }
  function releaseWakeLock() { if (wakeLock) { wakeLock.release(); wakeLock = null; } }

  // --- Events ---
  function init() {
    loadSessions();
    renderSessionList(dom.sessionList, false, 5);

    // Start recording
    dom.startBtn.addEventListener('click', startRecording);
    dom.meetingTitle.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); startRecording(); } });

    // View all button -> history
    dom.viewAllBtn?.addEventListener('click', () => {
      renderSessionList(dom.historyList, true);
      navigateTo('history-screen');
    });

    // Note input — send detection via TEXT CONTENT (not keydown)
    // This works with ANY keyboard including BT keyboards that don't report modifier keys
    let lastNewlineTime = 0;
    dom.noteInput.addEventListener('input', () => {
      const val = dom.noteInput.value;
      // Detect double-Enter: two newlines at end within 500ms
      if (val.endsWith('\n')) {
        const now = Date.now();
        if (now - lastNewlineTime < 250) {
          // Double-Enter detected — strip trailing newlines and send
          dom.noteInput.value = val.replace(/\n+$/, '');
          if (dom.noteInput.value.trim()) {
            addNote(dom.noteInput.value);
          }
          lastNewlineTime = 0;
          autoResize();
          return;
        }
        lastNewlineTime = now;
      } else {
        lastNewlineTime = 0;
      }
      autoResize();
      dom.sendBtn.disabled = !val.trim();
    });
    // Shift/Ctrl+Enter via keyup — fires AFTER IME processes the key,
    // so modifier state is more likely correct on BT keyboards
    dom.noteInput.addEventListener('keyup', e => {
      const isEnter = e.key === 'Enter' || e.keyCode === 13;
      if (isEnter && (e.shiftKey || e.ctrlKey || e.metaKey)) {
        // Undo the newline that was already inserted by default keydown
        const val = dom.noteInput.value;
        dom.noteInput.value = val.replace(/\n+$/, '');
        if (dom.noteInput.value.trim()) addNote(dom.noteInput.value);
      }
    });
    // Also try keydown (works on desktop/USB keyboards)
    document.addEventListener('keydown', e => {
      if (document.activeElement !== dom.noteInput) return;
      const isEnter = e.key === 'Enter' || e.keyCode === 13;
      if (isEnter && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        addNote(dom.noteInput.value);
      }
    });
    dom.sendBtn.addEventListener('click', () => addNote(dom.noteInput.value));

    // Format toolbar toggle
    const fmtToggle = $('#fmt-toggle-btn');
    const fmtBar = $('#format-toolbar');
    fmtToggle?.addEventListener('click', () => {
      fmtBar?.classList.toggle('hidden');
      fmtToggle.classList.toggle('active', !fmtBar?.classList.contains('hidden'));
    });

    // Format toolbar actions
    document.querySelector('.format-toolbar')?.addEventListener('click', e => {
      const btn = e.target.closest('[data-fmt]');
      if (!btn) return;
      const ta = dom.noteInput;
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const sel = ta.value.substring(start, end);
      let insert = '';
      switch (btn.dataset.fmt) {
        case 'bullet':   insert = '- '; break;
        case 'bold':     insert = sel ? `**${sel}**` : '**'; break;
        case 'heading':  insert = '# '; break;
        case 'action':   insert = '[] '; break;
        case 'highlight':insert = sel ? `==${sel}==` : '=='; break;
      }
      if (sel && (btn.dataset.fmt === 'bold' || btn.dataset.fmt === 'highlight')) {
        ta.value = ta.value.substring(0, start) + insert + ta.value.substring(end);
        ta.selectionStart = ta.selectionEnd = start + insert.length;
      } else {
        ta.value = ta.value.substring(0, start) + insert + ta.value.substring(end);
        ta.selectionStart = ta.selectionEnd = start + insert.length;
      }
      ta.focus();
      dom.sendBtn.disabled = !ta.value.trim();
      autoResize();
    });

    // Camera / Gallery buttons
    const cameraInput = $('#camera-input');
    const galleryInput = $('#gallery-input');
    $('#camera-btn')?.addEventListener('click', () => {
      if (!currentSession) return;
      if (isNative) {
        const cb = 'qnCamera' + Date.now();
        window[cb] = function(result) {
          delete window[cb];
          if (result && result.filename) {
            const note = {
              timestamp: recordingStartTime ? Date.now() - recordingStartTime : (currentSession ? currentSession.duration || 0 : 0),
              text: `![${result.filename}]`,
              type: 'image',
              imageFile: result.filename,
              imageDataUrl: result.dataUrl,
              createdAt: Date.now(),
            };
            currentSession.notes.push(note);
            saveSessions();
            saveActiveSession(); // real-time save
            dom.emptyHint.classList.add('hidden');
            renderNoteEntry(note);
            dom.notesEntries.scrollTop = dom.notesEntries.scrollHeight;
          }
        };
        NativeBridge.capturePhoto(cb);
      } else {
        cameraInput?.click();
      }
    });
    $('#gallery-btn')?.addEventListener('click', () => { if (currentSession) galleryInput?.click(); });
    cameraInput?.addEventListener('change', e => { if (e.target.files[0]) { addImageNote(e.target.files[0]); e.target.value = ''; } });
    galleryInput?.addEventListener('change', e => { if (e.target.files[0]) { addImageNote(e.target.files[0]); e.target.value = ''; } });

    // Stop recording — always confirm to prevent accidental tap
    dom.stopBtn.addEventListener('click', () => {
      if (!confirm('确定结束记录？')) return;
      stopRecording();
    });

    // Review actions
    dom.exportBtn.addEventListener('click', exportSession);
    dom.shareBtn.addEventListener('click', shareSession);

    dom.newBtn.addEventListener('click', () => {
      currentSession = null;
      dom.meetingTitle.value = '';
      screenHistory.length = 0;
      screenHistory.push('start-screen');
      showScreen('start-screen', true);
      renderSessionList(dom.sessionList, false, 5);
    });

    // Menu
    dom.menuBtn.addEventListener('click', e => { e.stopPropagation(); dom.menuDropdown.classList.toggle('hidden'); });
    document.addEventListener('click', () => dom.menuDropdown.classList.add('hidden'));

    dom.menuHistory.addEventListener('click', () => { renderSessionList(dom.historyList, true); navigateTo('history-screen'); });
    dom.menuExports.addEventListener('click', () => { renderExportsList(); navigateTo('exports-screen'); });
    dom.menuSettings?.addEventListener('click', openSettingsScreen);

    // Back buttons
    dom.backBtn.addEventListener('click', () => {
      screenHistory.length = 0;
      screenHistory.push('start-screen');
      showScreen('start-screen', true);
      renderSessionList(dom.sessionList, false, 5);
    });
    dom.backFromExports.addEventListener('click', () => {
      screenHistory.length = 0;
      screenHistory.push('start-screen');
      showScreen('start-screen', true);
    });
    dom.backFromSettings?.addEventListener('click', () => {
      screenHistory.length = 0;
      screenHistory.push('start-screen');
      showScreen('start-screen', true);
    });

    // Settings change listeners
    dom.settingAudioQuality?.addEventListener('change', onSettingChange);
    dom.settingAutoStop?.addEventListener('change', onSettingChange);
    dom.settingExportFormat?.addEventListener('change', onSettingChange);
    $('#setting-stealth-mode')?.addEventListener('change', onSettingChange);

    // == Settings model list: event delegation -- registered ONCE here ==
    // Handles ALL download/delete for both Whisper and Diarization models
    dom.settingsModelList?.addEventListener('click', e => {
      const btn = e.target.closest('[data-action]');
      if (!btn || btn.disabled) return;
      const action = btn.dataset.action;
      const mid    = btn.dataset.mid || '';

      if (action === 'download-whisper') {
        btn.disabled = true;
        btn.textContent = '\u4E0B\u8F7D\u4E2D...';
        const modelKey = mid;
        const progressEl = document.getElementById('dl-progress-' + modelKey);

        // Initialize download tracking
        downloadProgress[modelKey] = {
          active: true, percent: 0, bytesDownloaded: 0, bytesTotal: 0,
          speed: '', speedBps: 0, startTime: Date.now(), lastBytes: 0, lastTime: Date.now(),
          currentFile: 1, totalFiles: 3, fileBytesDownloaded: 0, fileBytesTotal: 0,
        };
        if (progressEl) progressEl.innerHTML = buildModelProgressHtml(downloadProgress[modelKey]);

        const cb = 'qnDl' + Date.now();
        window[cb] = function(data) {
          const dlState = downloadProgress[modelKey];
          if (data.type === 'progress') {
            // File-level: which file is being downloaded (1/3, 2/3, 3/3)
            if (dlState) {
              dlState.currentFile = data.file || 1;
              dlState.totalFiles = data.total || 1;
              dlState.currentFileName = data.name || '';
              // Reset byte tracking for each new file
              dlState.fileBytesDownloaded = 0;
              dlState.fileBytesTotal = 0;
            }
          } else if (data.type === 'bytes') {
            // Byte-level: real-time download progress
            if (dlState) {
              dlState.fileBytesDownloaded = data.downloaded || 0;
              dlState.fileBytesTotal = data.total > 0 ? data.total : 0;
              // Overall progress: (completedFiles + currentFileProgress) / totalFiles
              const filePct = dlState.fileBytesTotal > 0 ? dlState.fileBytesDownloaded / dlState.fileBytesTotal : 0;
              const overallPct = ((dlState.currentFile - 1 + filePct) / dlState.totalFiles) * 100;
              dlState.percent = Math.min(99, overallPct);
              dlState.bytesDownloaded = dlState.fileBytesDownloaded;
              dlState.bytesTotal = dlState.fileBytesTotal;
              // Speed calculation (smoothed, update every 0.5s)
              const now = Date.now();
              const elapsed = (now - dlState.lastTime) / 1000;
              if (elapsed >= 0.5) {
                const speedBps = (dlState.fileBytesDownloaded - dlState.lastBytes) / elapsed;
                dlState.speedBps = speedBps > 0 ? speedBps : (dlState.speedBps || 0);
                dlState.speed = dlState.speedBps > 0 ? formatFileSize(dlState.speedBps) + '/s' : '';
                dlState.lastBytes = dlState.fileBytesDownloaded;
                dlState.lastTime = now;
              }
              updateModelDownloadProgress(modelKey, data);
            }
          } else if (data.type === 'done') {
            delete window[cb];
            delete downloadProgress[modelKey];
            if (data.result === 'ok') {
              showToast('模型下载完成');
              renderSettingsModels();
            } else {
              if (progressEl) progressEl.innerHTML = `<span style="color:var(--danger)">下载失败: ${data.result}</span>`;
              btn.disabled = false;
              btn.textContent = '重试下载';
            }
          }
        };
        NativeBridge.downloadWhisperModel(mid, cb);

      } else if (action === 'delete-whisper') {
        if (!confirm('\u786E\u5B9A\u5220\u9664\u6B64\u6A21\u578B\uFF1F\u9700\u8981\u65F6\u53EF\u91CD\u65B0\u4E0B\u8F7D\u3002')) return;
        NativeBridge.deleteWhisperModel(mid);
        showToast('\u6A21\u578B\u5DF2\u5220\u9664');
        renderSettingsModels();

      } else if (action === 'download-diarize') {
        btn.disabled = true;
        btn.textContent = '\u4E0B\u8F7D\u4E2D...';
        const modelKey = 'diarization';
        const progressEl = document.getElementById('dl-progress-diarization');

        downloadProgress[modelKey] = {
          active: true, percent: 0, bytesDownloaded: 0, bytesTotal: 0,
          speed: '', speedBps: 0, startTime: Date.now(), lastBytes: 0, lastTime: Date.now(),
          currentFile: 1, totalFiles: 2, fileBytesDownloaded: 0, fileBytesTotal: 0,
        };
        if (progressEl) progressEl.innerHTML = buildModelProgressHtml(downloadProgress[modelKey]);

        const cb = 'qnDl' + Date.now();
        window[cb] = function(data) {
          const dlState = downloadProgress[modelKey];
          if (data.type === 'progress') {
            if (dlState) {
              dlState.currentFile = data.file || 1;
              dlState.totalFiles = data.total || 1;
              dlState.fileBytesDownloaded = 0;
              dlState.fileBytesTotal = 0;
            }
          } else if (data.type === 'bytes') {
            if (dlState) {
              dlState.fileBytesDownloaded = data.downloaded || 0;
              dlState.fileBytesTotal = data.total > 0 ? data.total : 0;
              const filePct = dlState.fileBytesTotal > 0 ? dlState.fileBytesDownloaded / dlState.fileBytesTotal : 0;
              const overallPct = ((dlState.currentFile - 1 + filePct) / dlState.totalFiles) * 100;
              dlState.percent = Math.min(99, overallPct);
              dlState.bytesDownloaded = dlState.fileBytesDownloaded;
              dlState.bytesTotal = dlState.fileBytesTotal;
              const now = Date.now();
              const elapsed = (now - dlState.lastTime) / 1000;
              if (elapsed >= 0.5) {
                const speedBps = (dlState.fileBytesDownloaded - dlState.lastBytes) / elapsed;
                dlState.speedBps = speedBps > 0 ? speedBps : (dlState.speedBps || 0);
                dlState.speed = dlState.speedBps > 0 ? formatFileSize(dlState.speedBps) + '/s' : '';
                dlState.lastBytes = dlState.fileBytesDownloaded;
                dlState.lastTime = now;
              }
              updateModelDownloadProgress(modelKey, data);
            }
          } else if (data.type === 'done') {
            delete window[cb];
            delete downloadProgress[modelKey];
            if (data.result === 'ok') {
              showToast('说话人识别模型下载完成');
              renderSettingsModels();
            } else {
              if (progressEl) progressEl.innerHTML = `<span style="color:var(--danger)">下载失败: ${data.result}</span>`;
              btn.disabled = false;
              btn.textContent = '重试下载';
            }
          }
        };
        NativeBridge.downloadDiarizationModel(cb);

      } else if (action === 'delete-diarize') {
        if (!confirm('\u786E\u5B9A\u5220\u9664\u8BF4\u8BDD\u4EBA\u8BC6\u522B\u6A21\u578B\uFF1F\u9700\u8981\u65F6\u53EF\u91CD\u65B0\u4E0B\u8F7D\u3002')) return;
        NativeBridge.deleteDiarizationModel();
        showToast('\u6A21\u578B\u5DF2\u5220\u9664');
        renderSettingsModels();
      }
    });

    // == Transcription screen ==
    if (isNative) {
      dom.transcribeEntryBtn?.addEventListener('click', openTranscriptionScreen);
      dom.backFromTranscription?.addEventListener('click', () => {
        if (transcriptionKey) showToast('\u8F6C\u5F55\u5728\u540E\u53F0\u7EE7\u7EED\u8FD0\u884C\uFF0C\u5B8C\u6210\u540E\u8FD4\u56DE\u67E5\u770B\u7ED3\u679C');
        navigateBack('review-screen');
      });
      dom.transcribeBtn?.addEventListener('click', startTranscription);
      dom.cancelTranscribeBtn?.addEventListener('click', cancelTranscription);
      dom.transcriptionModelSelect?.addEventListener('change', () => {
        selectedModelId = dom.transcriptionModelSelect.value;
        dom.transcribeBtn.disabled = !!transcriptionKey;
      });
      dom.copyTranscriptBtn?.addEventListener('click', () => {
        const text = dom.transcriptText?.textContent;
        if (!text) return;
        if (navigator.clipboard) {
          navigator.clipboard.writeText(text).then(() => showToast('\u5DF2\u590D\u5236'));
        }
      });
      dom.mergeNotesBtn?.addEventListener('click', mergeNotes);
      dom.shareMergedBtn?.addEventListener('click', shareMergedDoc);
      dom.goToSettingsBtn?.addEventListener('click', openSettingsScreen);

      // Resume polling if app was backgrounded during transcription
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && transcriptionKey && !transcriptionPoll) {
          transcriptionPoll = setInterval(pollTranscription, 3000);
        }
      });
    }

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && mediaRecorder?.state === 'recording') requestWakeLock();
      // Emergency save when app goes to background
      if (document.visibilityState === 'hidden' && currentSession && recordingStartTime) {
        saveActiveSession();
      }
    });

    // Emergency save on page unload (app killed, WebView destroyed)
    window.addEventListener('pagehide', () => {
      if (currentSession && recordingStartTime) saveActiveSession();
    });
    window.addEventListener('beforeunload', () => {
      if (currentSession && recordingStartTime) saveActiveSession();
    });

    // Backup button — save with timestamp, show path
    $('#backup-btn')?.addEventListener('click', () => {
      const data = {
        sessions: JSON.parse(localStorage.getItem('quicknote_sessions') || '[]'),
        settings: JSON.parse(localStorage.getItem('quicknote_settings') || '{}'),
        version: '3.5',
        exportedAt: new Date().toISOString(),
        sessionCount: sessions.length,
      };
      const json = JSON.stringify(data, null, 2);
      if (isNative) {
        const path = NativeBridge.saveBackup(json);
        if (path) {
          showToast('备份完成: ' + path, 4000);
        } else {
          showToast('备份失败', 2000);
        }
      } else {
        const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
        const blob = new Blob([json], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `quicknote_backup_${ts}.json`;
        a.click();
        showToast('备份已下载');
      }
    });

    // Restore button — list available backups and let user choose
    $('#restore-btn')?.addEventListener('click', () => {
      if (!isNative) { showToast('仅支持安卓原生'); return; }
      let backups;
      try {
        backups = JSON.parse(NativeBridge.listBackups());
      } catch(e) { backups = []; }
      if (!backups.length) {
        showToast('未找到任何备份文件\n路径: Documents/QuickNote/backups/', 3000);
        return;
      }
      // Build selection dialog
      let msg = '选择要恢复的备份:\n\n';
      backups.forEach((b, i) => {
        const date = new Date(b.modified).toLocaleString('zh-CN');
        const sizeKB = Math.round(b.size / 1024);
        msg += `[${i + 1}] ${date} (${sizeKB}KB)\n`;
      });
      msg += '\n输入编号 (1-' + backups.length + '):';
      const choice = prompt(msg);
      if (!choice) return;
      const idx = parseInt(choice) - 1;
      if (idx < 0 || idx >= backups.length) { showToast('无效选择'); return; }
      const selected = backups[idx];
      try {
        const json = NativeBridge.loadBackup(selected.path);
        if (!json) { showToast('读取备份失败'); return; }
        const data = JSON.parse(json);
        if (!data.sessions) throw new Error('invalid');
        const date = new Date(selected.modified).toLocaleString('zh-CN');
        if (!confirm(`恢复备份:\n${date}\n${data.sessions.length} 条记录\n\n将覆盖当前数据，确定？`)) return;
        localStorage.setItem('quicknote_sessions', JSON.stringify(data.sessions));
        if (data.settings) localStorage.setItem('quicknote_settings', JSON.stringify(data.settings));
        loadSessions();
        renderSessionList(dom.sessionList, false);
        applySettingsToUI();
        showToast(`已恢复 ${data.sessions.length} 条记录`, 3000);
      } catch(e) { showToast('备份文件格式错误: ' + e.message); }
    });

    // Debug info button
    $('#debug-info-btn')?.addEventListener('click', () => {
      let info = '浏览器模式';
      if (isNative) {
        try { info = NativeBridge.getDebugInfo(); } catch(e) { info = 'error: ' + e; }
      }
      const el = $('#debug-info-display');
      if (el) el.textContent = typeof info === 'string' && info.startsWith('{') ? JSON.stringify(JSON.parse(info), null, 1) : info;
      showToast(info);
    });

    // Load initial settings
    applySettingsToUI();

    // Android hardware back button handler
    window.__qnHandleBack = function() {
      if (currentScreenId === 'start-screen') {
        return 'exit'; // at home screen, let Android handle (minimize)
      }
      if (recordingStartTime && currentScreenId === 'notes-screen') {
        // Recording in progress — don't navigate away, just ignore back
        return;
      }
      navigateBack('start-screen');
    };

    // Haptic feedback on button taps
    document.addEventListener('click', e => {
      if (e.target.closest('button, .session-card, .export-card, .settings-item')) {
        if (navigator.vibrate) navigator.vibrate(5);
      }
    });

    // Material ripple effect on interactive elements
    function addRipple(event) {
      const el = event.currentTarget;
      const ripple = document.createElement('span');
      const rect = el.getBoundingClientRect();
      const size = Math.max(rect.width, rect.height);
      const x = (event.touches ? event.touches[0].clientX : event.clientX) - rect.left - size / 2;
      const y = (event.touches ? event.touches[0].clientY : event.clientY) - rect.top - size / 2;
      ripple.className = 'ripple';
      ripple.style.width = ripple.style.height = size + 'px';
      ripple.style.left = x + 'px';
      ripple.style.top = y + 'px';
      el.appendChild(ripple);
      ripple.addEventListener('animationend', () => ripple.remove());
    }
    document.querySelectorAll('.ripple-target').forEach(el => {
      el.addEventListener('touchstart', addRipple, { passive: true });
    });

    // Check for interrupted session recovery
    checkForRecovery();
  }

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').then(reg => {
      setTimeout(() => reg.unregister(), 3000);
    }).catch(() => {});
  }

  init();
})();
