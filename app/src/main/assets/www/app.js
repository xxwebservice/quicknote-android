// === QuickNote — Discreet Meeting Recorder + Notes ===
// Native Android version with NativeBridge support

(function() {
  'use strict';

  // --- Native detection ---
  const isNative = typeof NativeBridge !== 'undefined';

  // --- State ---
  let mediaRecorder = null;
  let audioChunks = [];
  let recordingStartTime = null;
  let timerInterval = null;
  let currentSession = null;
  let sessions = [];

  // --- DOM ---
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  const dom = {
    statusDot: $('#status-dot'),
    timer: $('#timer'),
    menuBtn: $('#menu-btn'),
    menuDropdown: $('#menu-dropdown'),
    menuExport: $('#menu-export'),
    menuHistory: $('#menu-history'),
    menuSettings: $('#menu-settings'),
    startScreen: $('#start-screen'),
    notesScreen: $('#notes-screen'),
    reviewScreen: $('#review-screen'),
    historyScreen: $('#history-screen'),
    meetingTitle: $('#meeting-title'),
    startBtn: $('#start-btn'),
    sessionList: $('#session-list'),
    currentTitle: $('#current-title'),
    notesEntries: $('#notes-entries'),
    noteInput: $('#note-input'),
    stopBtn: $('#stop-btn'),
    reviewTitle: $('#review-title'),
    reviewDuration: $('#review-duration'),
    reviewCount: $('#review-count'),
    reviewNotes: $('#review-notes'),
    exportBtn: $('#export-btn'),
    newBtn: $('#new-btn'),
    backBtn: $('#back-btn'),
    historyList: $('#history-list'),
  };

  // --- Utility ---
  function formatTime(ms) {
    const totalSec = Math.floor(ms / 1000);
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  function formatTimestamp(ms) {
    const totalSec = Math.floor(ms / 1000);
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
  }

  function formatDate(ts) {
    const d = new Date(ts);
    const month = d.getMonth() + 1;
    const day = d.getDate();
    const h = String(d.getHours()).padStart(2, '0');
    const min = String(d.getMinutes()).padStart(2, '0');
    return `${month}/${day} ${h}:${min}`;
  }

  function generateId() {
    return Date.now().toString(36) + Math.random().toString(36).substr(2, 5);
  }

  // --- Storage ---
  function loadSessions() {
    try {
      const data = localStorage.getItem('quicknote_sessions');
      sessions = data ? JSON.parse(data) : [];
    } catch {
      sessions = [];
    }
  }

  function saveSessions() {
    const meta = sessions.map(s => ({
      id: s.id,
      title: s.title,
      startTime: s.startTime,
      duration: s.duration,
      notes: s.notes,
      hasAudio: s.hasAudio || false,
    }));
    localStorage.setItem('quicknote_sessions', JSON.stringify(meta));
  }

  // Audio stored in IndexedDB
  function openAudioDB() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open('quicknote_audio', 1);
      req.onupgradeneeded = (e) => {
        e.target.result.createObjectStore('audio', { keyPath: 'id' });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function saveAudio(id, blob) {
    const db = await openAudioDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('audio', 'readwrite');
      tx.objectStore('audio').put({ id, blob, type: blob.type });
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async function getAudio(id) {
    const db = await openAudioDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('audio', 'readonly');
      const req = tx.objectStore('audio').get(id);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function deleteAudio(id) {
    const db = await openAudioDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('audio', 'readwrite');
      tx.objectStore('audio').delete(id);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  // --- Screens ---
  function showScreen(screenId) {
    $$('.screen').forEach(s => s.classList.remove('active'));
    $(`#${screenId}`).classList.add('active');
    dom.menuDropdown.classList.add('hidden');
  }

  // --- Session List ---
  function renderSessionList(container, showDelete) {
    container.innerHTML = '';
    const sorted = [...sessions].sort((a, b) => b.startTime - a.startTime);
    if (sorted.length === 0) {
      container.innerHTML = '<p style="color:var(--text-muted);font-size:14px;padding:16px 0;">暂无记录</p>';
      return;
    }
    sorted.forEach(s => {
      const card = document.createElement('div');
      card.className = 'session-card';
      card.innerHTML = `
        <div class="session-info">
          <span class="session-name">${s.title || '未命名会议'}</span>
          <span class="session-meta">${formatDate(s.startTime)} · ${formatTime(s.duration)} · ${s.notes.length}条笔记</span>
        </div>
        <div class="session-actions">
          ${showDelete ? '<button class="delete-btn" data-id="' + s.id + '">✕</button>' : ''}
          <span class="session-arrow">›</span>
        </div>
      `;
      card.addEventListener('click', (e) => {
        if (e.target.classList.contains('delete-btn')) {
          e.stopPropagation();
          deleteSession(s.id);
          return;
        }
        openReview(s);
      });
      container.appendChild(card);
    });
  }

  function deleteSession(id) {
    if (!confirm('确定删除此记录？')) return;
    sessions = sessions.filter(s => s.id !== id);
    saveSessions();
    deleteAudio(id).catch(() => {});
    renderSessionList(dom.sessionList, false);
    renderSessionList(dom.historyList, true);
  }

  // --- Recording ---
  async function startRecording() {
    const title = dom.meetingTitle.value.trim();
    const id = generateId();

    currentSession = {
      id,
      title: title || `会议 ${formatDate(Date.now())}`,
      startTime: Date.now(),
      duration: 0,
      notes: [],
      hasAudio: false,
    };

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          sampleRate: 44100,
        }
      });

      const mimeTypes = [
        'audio/webm;codecs=opus',
        'audio/webm',
        'audio/ogg;codecs=opus',
        'audio/mp4',
      ];
      let mimeType = '';
      for (const mt of mimeTypes) {
        if (MediaRecorder.isTypeSupported(mt)) {
          mimeType = mt;
          break;
        }
      }

      audioChunks = [];
      mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) audioChunks.push(e.data);
      };

      mediaRecorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        if (audioChunks.length > 0) {
          const blob = new Blob(audioChunks, { type: mediaRecorder.mimeType });
          await saveAudio(currentSession.id, blob);
          currentSession.hasAudio = true;
        }
        finishRecording();
      };

      mediaRecorder.start(1000);
      recordingStartTime = Date.now();
      timerInterval = setInterval(updateTimer, 1000);

      dom.statusDot.classList.remove('hidden');
      dom.timer.classList.remove('hidden');
      dom.currentTitle.textContent = currentSession.title;
      dom.notesEntries.innerHTML = '';
      dom.noteInput.value = '';

      showScreen('notes-screen');
      dom.noteInput.focus();

    } catch (err) {
      console.warn('Mic access denied, continuing without audio:', err);
      recordingStartTime = Date.now();
      timerInterval = setInterval(updateTimer, 1000);
      dom.statusDot.classList.remove('hidden');
      dom.timer.classList.remove('hidden');
      dom.currentTitle.textContent = currentSession.title;
      dom.notesEntries.innerHTML = '';
      dom.noteInput.value = '';
      showScreen('notes-screen');
      dom.noteInput.focus();
    }
  }

  function updateTimer() {
    if (!recordingStartTime) return;
    const elapsed = Date.now() - recordingStartTime;
    dom.timer.textContent = formatTime(elapsed);
  }

  function stopRecording() {
    if (timerInterval) {
      clearInterval(timerInterval);
      timerInterval = null;
    }

    if (currentSession) {
      currentSession.duration = Date.now() - recordingStartTime;
    }

    dom.statusDot.classList.add('hidden');
    dom.timer.classList.add('hidden');

    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      mediaRecorder.stop();
    } else {
      finishRecording();
    }
  }

  function finishRecording() {
    if (!currentSession) return;

    sessions.push(currentSession);
    saveSessions();

    openReview(currentSession);

    mediaRecorder = null;
    audioChunks = [];
    recordingStartTime = null;
    renderSessionList(dom.sessionList, false);
  }

  // --- Notes ---
  function addNote(text) {
    if (!text.trim() || !currentSession) return;
    const elapsed = Date.now() - recordingStartTime;
    const note = {
      timestamp: elapsed,
      text: text.trim(),
      createdAt: Date.now(),
    };
    currentSession.notes.push(note);
    renderNoteEntry(note);
    dom.noteInput.value = '';
    autoResizeInput();
    dom.notesEntries.scrollTop = dom.notesEntries.scrollHeight;
  }

  function renderNoteEntry(note) {
    const entry = document.createElement('div');
    entry.className = 'note-entry';
    entry.innerHTML = `
      <span class="note-timestamp">${formatTimestamp(note.timestamp)}</span>
      <span class="note-text">${escapeHtml(note.text)}</span>
    `;
    dom.notesEntries.appendChild(entry);
  }

  function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  // --- Review ---
  function openReview(session) {
    dom.reviewTitle.textContent = session.title || '未命名会议';
    dom.reviewDuration.textContent = `时长 ${formatTime(session.duration)}`;
    dom.reviewCount.textContent = `${session.notes.length} 条笔记`;

    dom.reviewNotes.innerHTML = '';
    session.notes.forEach(n => {
      const entry = document.createElement('div');
      entry.className = 'review-note-entry';
      entry.innerHTML = `
        <span class="review-timestamp">${formatTimestamp(n.timestamp)}</span>
        <span class="review-text">${escapeHtml(n.text)}</span>
      `;
      dom.reviewNotes.appendChild(entry);
    });

    currentSession = session;
    showScreen('review-screen');
  }

  // --- Export ---
  // Convert blob to base64
  function blobToBase64(blob) {
    return new Promise((resolve) => {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64 = reader.result.split(',')[1];
        resolve(base64);
      };
      reader.readAsDataURL(blob);
    });
  }

  async function exportSession() {
    if (!currentSession) return;

    const session = currentSession;
    const prefix = sanitizeFilename(session.title);

    // 1. Build notes markdown
    let md = `# ${session.title || '未命名会议'}\n\n`;
    md += `- 日期: ${new Date(session.startTime).toLocaleString('zh-CN')}\n`;
    md += `- 时长: ${formatTime(session.duration)}\n`;
    md += `- 笔记数: ${session.notes.length}\n\n`;
    md += `## 笔记\n\n`;
    session.notes.forEach(n => {
      md += `**[${formatTimestamp(n.timestamp)}]** ${n.text}\n\n`;
    });

    // 2. Build analysis JSON
    const combined = {
      session: {
        title: session.title,
        date: new Date(session.startTime).toISOString(),
        duration_ms: session.duration,
        duration_formatted: formatTime(session.duration),
      },
      notes: session.notes.map(n => ({
        timestamp_ms: n.timestamp,
        timestamp_formatted: formatTimestamp(n.timestamp),
        text: n.text,
      })),
      instructions: '请将录音文件转为transcript，与笔记按时间戳交叉对比。笔记代表记录者认为的重点，transcript提供完整上下文。输出: 1) 完整会议纪要 2) 重点标注(笔记提到的部分) 3) 笔记中未记录但重要的内容',
    };
    const jsonStr = JSON.stringify(combined, null, 2);

    if (isNative) {
      // --- Native export: save to app private storage ---
      let savedCount = 0;

      // Save notes
      if (NativeBridge.saveText(`${prefix}_notes.md`, md)) savedCount++;

      // Save JSON
      if (NativeBridge.saveText(`${prefix}_for_analysis.json`, jsonStr)) savedCount++;

      // Save audio
      try {
        const audioData = await getAudio(session.id);
        if (audioData && audioData.blob) {
          const ext = audioData.type.includes('webm') ? 'webm' :
                      audioData.type.includes('ogg') ? 'ogg' :
                      audioData.type.includes('mp4') ? 'm4a' : 'audio';
          const b64 = await blobToBase64(audioData.blob);
          if (NativeBridge.saveFile(`${prefix}_recording.${ext}`, b64)) savedCount++;
        }
      } catch (err) {
        console.warn('No audio to export:', err);
      }

      alert(`已导出 ${savedCount} 个文件到 QuickNote 文件夹\n\n路径: ${NativeBridge.getStoragePath()}`);

    } else {
      // --- Web export: browser downloads ---
      const notesBlob = new Blob([md], { type: 'text/markdown' });
      downloadBlob(notesBlob, `${prefix}_notes.md`);

      try {
        const audioData = await getAudio(session.id);
        if (audioData && audioData.blob) {
          const ext = audioData.type.includes('webm') ? 'webm' :
                      audioData.type.includes('ogg') ? 'ogg' :
                      audioData.type.includes('mp4') ? 'm4a' : 'audio';
          downloadBlob(audioData.blob, `${prefix}_recording.${ext}`);
        }
      } catch (err) {
        console.warn('No audio to export:', err);
      }

      const jsonBlob = new Blob([jsonStr], { type: 'application/json' });
      downloadBlob(jsonBlob, `${prefix}_for_analysis.json`);
    }
  }

  function sanitizeFilename(name) {
    return (name || 'meeting').replace(/[^\w\u4e00-\u9fff-]/g, '_').substring(0, 50);
  }

  function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  // --- Auto-resize textarea ---
  function autoResizeInput() {
    const el = dom.noteInput;
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
  }

  // --- Keep screen awake during recording ---
  let wakeLock = null;

  async function requestWakeLock() {
    try {
      if ('wakeLock' in navigator) {
        wakeLock = await navigator.wakeLock.request('screen');
      }
    } catch {
      // Wake Lock not supported or failed
    }
  }

  function releaseWakeLock() {
    if (wakeLock) {
      wakeLock.release();
      wakeLock = null;
    }
  }

  // --- Events ---
  function init() {
    loadSessions();
    renderSessionList(dom.sessionList, false);

    dom.startBtn.addEventListener('click', () => {
      requestWakeLock();
      startRecording();
    });

    dom.noteInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        addNote(dom.noteInput.value);
      }
    });

    dom.noteInput.addEventListener('input', autoResizeInput);

    dom.stopBtn.addEventListener('click', () => {
      releaseWakeLock();
      stopRecording();
    });

    dom.exportBtn.addEventListener('click', exportSession);

    dom.newBtn.addEventListener('click', () => {
      currentSession = null;
      dom.meetingTitle.value = '';
      showScreen('start-screen');
      renderSessionList(dom.sessionList, false);
    });

    dom.menuBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      dom.menuDropdown.classList.toggle('hidden');
    });

    document.addEventListener('click', () => {
      dom.menuDropdown.classList.add('hidden');
    });

    dom.menuExport.addEventListener('click', () => {
      if (currentSession) exportSession();
    });

    dom.menuHistory.addEventListener('click', () => {
      renderSessionList(dom.historyList, true);
      showScreen('history-screen');
    });

    dom.backBtn.addEventListener('click', () => {
      showScreen('start-screen');
      renderSessionList(dom.sessionList, false);
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && mediaRecorder && mediaRecorder.state === 'recording') {
        requestWakeLock();
      }
    });
  }

  // Service Worker only for PWA mode
  if (!isNative && 'serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }

  init();

})();
