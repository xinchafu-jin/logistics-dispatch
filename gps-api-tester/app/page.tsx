'use client';

import {
  FormEvent,
  KeyboardEvent,
  PointerEvent as ReactPointerEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

type Source = '預設位置' | '真實 GPS' | '搖桿模擬' | '手動輸入';
type Position = { lat: number; lng: number; accuracy: number | null; source: Source; capturedAt: Date };
type Log = {
  id: number; time: Date; method: string; path: string; status: number | null;
  ok: boolean; duration: number; request?: unknown; response?: unknown; error?: string;
};
type Driver = {
  accessToken: string; tokenType: string; expiresAt: string; role: string;
  userId: number; account: string; name: string;
};
type Attendance = { status?: string; gpsAllowed?: boolean; clockInAt?: string; clockOutAt?: string };

const DEFAULT_API = 'http://localhost:8080';
const DEFAULT_POSITION: Position = {
  lat: 25.047817, lng: 121.517053, accuracy: null,
  source: '預設位置', capturedAt: new Date(0),
};
const STEPS = [1, 5, 10, 25, 50];
const time = (value: Date) => new Intl.DateTimeFormat('zh-TW', {
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
}).format(value);
const coordinate = (value: number) => Number.isFinite(value) ? value.toFixed(6) : '—';

function geoMessage(error: GeolocationPositionError) {
  if (error.code === error.PERMISSION_DENIED) return '定位權限被拒絕，請在瀏覽器網址列允許位置權限。';
  if (error.code === error.POSITION_UNAVAILABLE) return '目前無法取得定位，請確認裝置 GPS 已開啟。';
  if (error.code === error.TIMEOUT) return '取得定位逾時，請移到收訊較好的位置後重試。';
  return error.message || '無法取得目前位置。';
}

function messageOf(value: unknown) {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object' && 'message' in value) {
    return String((value as { message: unknown }).message);
  }
  return null;
}

export default function Home() {
  const [apiBase, setApiBase] = useState(DEFAULT_API);
  const [connection, setConnection] = useState<'idle' | 'checking' | 'online' | 'offline'>('idle');
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [token, setToken] = useState('');
  const [driver, setDriver] = useState<Driver | null>(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [attendance, setAttendance] = useState<Attendance | null>(null);
  const [attendanceBusy, setAttendanceBusy] = useState(false);
  const [position, setPosition] = useState<Position>(DEFAULT_POSITION);
  const [trail, setTrail] = useState<Position[]>([DEFAULT_POSITION]);
  const [stepMeters, setStepMeters] = useState(5);
  const [autoSend, setAutoSend] = useState(false);
  const [watching, setWatching] = useState(false);
  const [locating, setLocating] = useState(false);
  const [geoError, setGeoError] = useState('');
  const [sending, setSending] = useState(false);
  const [lastSent, setLastSent] = useState<Position | null>(null);
  const [logs, setLogs] = useState<Log[]>([]);
  const [selectedLogId, setSelectedLogId] = useState<number | null>(null);
  const [stick, setStick] = useState({ x: 0, y: 0 });

  const logId = useRef(0);
  const watchId = useRef<number | null>(null);
  const stickTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const stickDirection = useRef({ x: 0, y: 0 });
  const canAutoSend = useRef(false);
  const sendRef = useRef<() => Promise<void>>(async () => undefined);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setApiBase(localStorage.getItem('gps-tester-api-base') || DEFAULT_API);
      setToken(sessionStorage.getItem('gps-tester-token') || '');
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);
  useEffect(() => () => {
    if (watchId.current !== null) navigator.geolocation.clearWatch(watchId.current);
    if (stickTimer.current) clearInterval(stickTimer.current);
  }, []);

  const selectedLog = useMemo(
    () => logs.find((entry) => entry.id === selectedLogId) ?? logs[0] ?? null,
    [logs, selectedLogId],
  );

  async function request<T>(
    path: string,
    options: { method?: string; body?: unknown; authenticated?: boolean } = {},
  ): Promise<{ ok: boolean; status: number | null; data: T | unknown }> {
    const method = options.method ?? 'GET';
    const started = performance.now();
    try {
      const headers: Record<string, string> = {};
      if (options.body !== undefined) headers['Content-Type'] = 'application/json';
      if (options.authenticated !== false && token) headers.Authorization = `Bearer ${token}`;
      const response = await fetch(`${apiBase.trim().replace(/\/$/, '')}${path}`, {
        method, headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      });
      const raw = await response.text();
      let data: unknown = null;
      if (raw) {
        try { data = JSON.parse(raw); } catch { data = raw; }
      }
      const entry: Log = {
        id: ++logId.current, time: new Date(), method, path, status: response.status,
        ok: response.ok, duration: Math.round(performance.now() - started),
        request: options.body, response: data,
      };
      setLogs((items) => [entry, ...items].slice(0, 30));
      setSelectedLogId(entry.id);
      return { ok: response.ok, status: response.status, data };
    } catch (error) {
      const detail = error instanceof Error ? error.message : '未知連線錯誤';
      const entry: Log = {
        id: ++logId.current, time: new Date(), method, path, status: null, ok: false,
        duration: Math.round(performance.now() - started), request: options.body,
        error: `${detail}。請確認後端已啟動，並允許目前頁面的 CORS 來源。`,
      };
      setLogs((items) => [entry, ...items].slice(0, 30));
      setSelectedLogId(entry.id);
      return { ok: false, status: null, data: entry.error };
    }
  }

  async function checkBackend() {
    setConnection('checking');
    const result = await request<{ status?: string }>('/actuator/health', { authenticated: false });
    const data = result.data as { status?: string } | null;
    setConnection(result.ok && (!data?.status || data.status === 'UP') ? 'online' : 'offline');
  }

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!account.trim() || !password) return;
    setAuthBusy(true);
    const result = await request<Driver>('/api/auth/driver/login', {
      method: 'POST', body: { account: account.trim(), password }, authenticated: false,
    });
    setAuthBusy(false);
    if (result.ok && result.data && typeof result.data === 'object' && 'accessToken' in result.data) {
      const session = result.data as Driver;
      setToken(session.accessToken);
      setDriver(session);
      setPassword('');
      setConnection('online');
      sessionStorage.setItem('gps-tester-token', session.accessToken);
    }
  }

  function logout() {
    setToken(''); setDriver(null); setAttendance(null); setAutoSend(false);
    sessionStorage.removeItem('gps-tester-token');
  }

  async function checkAttendance() {
    if (!token) return;
    setAttendanceBusy(true);
    const result = await request<Attendance>('/api/driver/attendance/today');
    setAttendanceBusy(false);
    if (result.ok && result.data && typeof result.data === 'object') setAttendance(result.data as Attendance);
  }

  async function clockIn() {
    if (!token) return;
    setAttendanceBusy(true);
    const result = await request<Attendance>('/api/driver/attendance/clock-in', { method: 'POST' });
    setAttendanceBusy(false);
    if (result.ok && result.data && typeof result.data === 'object') setAttendance(result.data as Attendance);
  }

  function apply(next: Position, resetTrail = false) {
    setPosition(next);
    setTrail((items) => resetTrail ? [next] : [...items, next].slice(-18));
    canAutoSend.current = true;
  }

  function applyBrowserPosition(value: GeolocationPosition, resetTrail = true) {
    apply({
      lat: value.coords.latitude, lng: value.coords.longitude,
      accuracy: value.coords.accuracy, source: '真實 GPS', capturedAt: new Date(value.timestamp),
    }, resetTrail);
    setGeoError('');
  }

  function locateOnce() {
    if (!('geolocation' in navigator)) return setGeoError('這個瀏覽器不支援定位功能。');
    setLocating(true); setGeoError('');
    navigator.geolocation.getCurrentPosition(
      (value) => { applyBrowserPosition(value); setLocating(false); },
      (error) => { setGeoError(geoMessage(error)); setLocating(false); },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
    );
  }

  function toggleWatch() {
    if (watching) {
      if (watchId.current !== null) navigator.geolocation.clearWatch(watchId.current);
      watchId.current = null; setWatching(false); return;
    }
    if (!('geolocation' in navigator)) return setGeoError('這個瀏覽器不支援定位功能。');
    setGeoError('');
    watchId.current = navigator.geolocation.watchPosition(
      (value) => applyBrowserPosition(value, false),
      (error) => setGeoError(geoMessage(error)),
      { enableHighAccuracy: true, timeout: 20000, maximumAge: 1000 },
    );
    setWatching(true);
  }

  function moveBy(east: number, north: number) {
    setPosition((current) => {
      const radians = current.lat * Math.PI / 180;
      const next: Position = {
        lat: Math.max(-90, Math.min(90, current.lat + north / 111_320)),
        lng: Math.max(-180, Math.min(180, current.lng + east / (111_320 * Math.max(.01, Math.cos(radians))))),
        accuracy: null, source: '搖桿模擬', capturedAt: new Date(),
      };
      setTrail((items) => [...items, next].slice(-18));
      return next;
    });
    canAutoSend.current = true;
  }

  function nudge(x: number, y: number) {
    const magnitude = Math.min(1, Math.hypot(x, y));
    if (magnitude < .08) return;
    moveBy(x / magnitude * stepMeters * magnitude, -y / magnitude * stepMeters * magnitude);
  }

  function updateStick(event: ReactPointerEvent<HTMLDivElement>) {
    const rect = event.currentTarget.getBoundingClientRect();
    const radius = Math.max(1, rect.width / 2 - 32);
    let x = (event.clientX - rect.left - rect.width / 2) / radius;
    let y = (event.clientY - rect.top - rect.height / 2) / radius;
    const magnitude = Math.hypot(x, y);
    if (magnitude > 1) { x /= magnitude; y /= magnitude; }
    stickDirection.current = { x, y };
    setStick({ x, y });
  }

  function startStick(event: ReactPointerEvent<HTMLDivElement>) {
    event.currentTarget.setPointerCapture(event.pointerId);
    updateStick(event);
    nudge(stickDirection.current.x, stickDirection.current.y);
    if (stickTimer.current) clearInterval(stickTimer.current);
    stickTimer.current = setInterval(() => nudge(stickDirection.current.x, stickDirection.current.y), 180);
  }

  function stopStick() {
    if (stickTimer.current) clearInterval(stickTimer.current);
    stickTimer.current = null; stickDirection.current = { x: 0, y: 0 }; setStick({ x: 0, y: 0 });
  }

  function stickKey(event: KeyboardEvent<HTMLDivElement>) {
    const directions: Record<string, [number, number]> = {
      arrowup: [0, stepMeters], w: [0, stepMeters], arrowdown: [0, -stepMeters], s: [0, -stepMeters],
      arrowleft: [-stepMeters, 0], a: [-stepMeters, 0], arrowright: [stepMeters, 0], d: [stepMeters, 0],
    };
    const direction = directions[event.key.toLowerCase()];
    if (direction) { event.preventDefault(); moveBy(direction[0], direction[1]); }
  }

  function edit(field: 'lat' | 'lng', raw: string) {
    const value = Number(raw);
    if (!Number.isFinite(value)) return;
    const max = field === 'lat' ? 90 : 180;
    apply({ ...position, [field]: Math.max(-max, Math.min(max, value)), accuracy: null, source: '手動輸入', capturedAt: new Date() });
  }

  async function sendGps() {
    if (!token || sending) return;
    setSending(true);
    const snapshot = { ...position, capturedAt: new Date() };
    const result = await request('/api/driver/gps', {
      method: 'POST', body: { lat: snapshot.lat, lng: snapshot.lng },
    });
    setSending(false);
    if (result.ok) setLastSent(snapshot);
  }

  useEffect(() => { sendRef.current = sendGps; });
  useEffect(() => {
    if (!autoSend || !token || !canAutoSend.current) return;
    const timer = window.setTimeout(() => {
      canAutoSend.current = false;
      void sendRef.current();
    }, 550);
    return () => clearTimeout(timer);
  }, [autoSend, token, position.lat, position.lng]);

  const connectionLabel = { idle: '尚未檢查', checking: '檢查中', online: 'API 在線', offline: '無法連線' }[connection];
  const responseMessage = selectedLog ? selectedLog.error ?? messageOf(selectedLog.response) : null;

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand-block">
          <div className="brand-mark" aria-hidden="true"><span /></div>
          <div><p className="eyebrow">LOGISTICS · FIELD TOOL</p><h1>GPS Signal Lab</h1></div>
        </div>
        <div className={`connection-pill ${connection}`}>
          <span className="status-dot" aria-hidden="true" /><span>{connectionLabel}</span>
          <button type="button" onClick={checkBackend} disabled={connection === 'checking'}>{connection === 'checking' ? '檢查中…' : '測試連線'}</button>
        </div>
      </header>

      <section className="workspace">
        <div className="position-stage">
          <div className="stage-toolbar">
            <div><p className="section-kicker">CURRENT POSITION</p><h2>位置控制台</h2></div>
            <div className={`source-badge ${position.source === '真實 GPS' ? 'real' : ''}`}>
              <span aria-hidden="true">{position.source === '真實 GPS' ? '●' : '◆'}</span>{position.source}
            </div>
          </div>

          <div className="coordinate-grid" aria-label="目前經緯度">
            <label><span>LAT 緯度</span><input type="number" min="-90" max="90" step="0.000001" value={position.lat} onChange={(event) => edit('lat', event.target.value)} /></label>
            <span className="coordinate-divider" aria-hidden="true" />
            <label><span>LNG 經度</span><input type="number" min="-180" max="180" step="0.000001" value={position.lng} onChange={(event) => edit('lng', event.target.value)} /></label>
          </div>

          <div className="signal-map" aria-label="位置移動視覺區">
            <span className="map-label north">N</span><span className="map-label east">E</span>
            <span className="map-label south">S</span><span className="map-label west">W</span>
            <div className="map-ring ring-one" /><div className="map-ring ring-two" />
            <div className="map-axis horizontal" /><div className="map-axis vertical" />
            {trail.slice(-7, -1).map((point, index, points) => <span className="trail-dot" key={`${point.capturedAt.getTime()}-${index}`} style={{ left: `${42 + (index + 1) / Math.max(1, points.length) * 8}%`, top: `${55 - ((index % 3) - 1) * 4}%`, opacity: .18 + index * .1 }} />)}
            <div className="position-marker"><span className="marker-pulse" /><span className="marker-core" /></div>
            <div className="map-readout"><span>位置精度</span><strong>{position.accuracy ? `± ${Math.round(position.accuracy)} m` : '模擬座標'}</strong></div>
            <a className="map-link" href={`https://www.google.com/maps?q=${position.lat},${position.lng}`} target="_blank" rel="noreferrer">在地圖查看 ↗</a>
          </div>

          <div className="capture-bar">
            <div><span>最後更新</span><strong>{position.capturedAt.getTime() === 0 ? '等待更新' : time(position.capturedAt)}</strong></div>
            <div className="capture-actions">
              <button className="secondary-button" type="button" onClick={locateOnce} disabled={locating}><span aria-hidden="true">◎</span>{locating ? '正在定位…' : '取得我的真實位置'}</button>
              <button className={`watch-button ${watching ? 'active' : ''}`} type="button" onClick={toggleWatch}><span className="watch-dot" aria-hidden="true" />{watching ? '停止持續追蹤' : '持續追蹤'}</button>
            </div>
          </div>
          {geoError && <p className="inline-alert error" role="alert">{geoError}</p>}
        </div>

        <aside className="control-stack">
          <section className="panel auth-panel">
            <div className="panel-heading">
              <div><p className="section-kicker">BACKEND ACCESS</p><h2>後端連線</h2></div>
              {token && <span className="verified-badge">已登入</span>}
            </div>
            <label className="field full-field"><span>API Base URL</span><input value={apiBase} onChange={(event) => { const nextApiBase = event.target.value; setApiBase(nextApiBase); localStorage.setItem('gps-tester-api-base', nextApiBase); setConnection('idle'); }} placeholder={DEFAULT_API} inputMode="url" /></label>
            {!token ? (
              <form className="login-form" onSubmit={login}>
                <label className="field"><span>司機帳號</span><input value={account} onChange={(event) => setAccount(event.target.value)} autoComplete="username" placeholder="例如 D001" /></label>
                <label className="field"><span>密碼</span><input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" placeholder="輸入密碼" /></label>
                <button className="login-button" type="submit" disabled={authBusy || !account.trim() || !password}>{authBusy ? '登入中…' : '取得 Driver Token'}</button>
              </form>
            ) : (
              <div className="session-card">
                <div className="avatar" aria-hidden="true">{(driver?.name || driver?.account || 'D').slice(0, 1)}</div>
                <div><strong>{driver?.name || driver?.account || 'Driver Token 已載入'}</strong><span>{driver ? `${driver.account} · DRIVER #${driver.userId}` : '目前瀏覽器工作階段'}</span></div>
                <button type="button" onClick={logout}>登出</button>
              </div>
            )}
            <div className="attendance-row">
              <div><span>出勤狀態</span><strong className={attendance?.gpsAllowed ? 'ready-text' : ''}>{attendance?.status ?? '尚未檢查'}</strong></div>
              <div className="attendance-actions"><button type="button" onClick={checkAttendance} disabled={!token || attendanceBusy}>查詢</button><button type="button" onClick={clockIn} disabled={!token || attendanceBusy || attendance?.gpsAllowed}>上班打卡</button></div>
            </div>
            <p className="helper-text">GPS API 只接受已登入、且今日狀態為 WORKING 的司機。</p>
          </section>

          <section className="panel joystick-panel">
            <div className="panel-heading joystick-heading">
              <div><p className="section-kicker">SIMULATION</p><h2>位置搖桿</h2></div>
              <label className="step-picker"><span>每步</span><select value={stepMeters} onChange={(event) => setStepMeters(Number(event.target.value))}>{STEPS.map((value) => <option key={value} value={value}>{value} m</option>)}</select></label>
            </div>
            <div className="joystick-area">
              <div className="joystick-base" role="application" aria-label="位置模擬搖桿，可拖曳或使用方向鍵與 WASD" tabIndex={0}
                onPointerDown={startStick} onPointerMove={(event) => { if (event.currentTarget.hasPointerCapture(event.pointerId)) updateStick(event); }} onPointerUp={stopStick} onPointerCancel={stopStick} onKeyDown={stickKey}>
                <span className="joystick-n">N</span><span className="joystick-e">E</span><span className="joystick-s">S</span><span className="joystick-w">W</span>
                <div className="joystick-knob" style={{ transform: `translate(calc(-50% + ${stick.x * 54}px), calc(-50% + ${stick.y * 54}px))` }}><span /></div>
              </div>
              <div className="position-summary"><span>模擬後座標</span><strong>{coordinate(position.lat)}</strong><strong>{coordinate(position.lng)}</strong><small>拖曳搖桿，或使用方向鍵 / WASD</small></div>
            </div>
            <label className={`toggle-row ${!token ? 'disabled' : ''}`}>
              <span><strong>位置變更後自動送出</strong><small>停止移動 0.55 秒後呼叫 API</small></span>
              <input type="checkbox" checked={autoSend} onChange={(event) => setAutoSend(event.target.checked)} disabled={!token} /><span className="toggle-track" aria-hidden="true"><span /></span>
            </label>
            <button className="send-button" type="button" onClick={sendGps} disabled={!token || sending}>
              <span className="send-icon" aria-hidden="true">⌁</span><span><strong>{sending ? '傳送中…' : '送出目前 GPS'}</strong><small>POST /api/driver/gps</small></span><span aria-hidden="true">→</span>
            </button>
            {!token && <p className="inline-alert">請先登入司機帳號，再送出座標。</p>}
            {lastSent && <p className="last-sent">✓ {time(lastSent.capturedAt)} 已成功寫入 {coordinate(lastSent.lat)}, {coordinate(lastSent.lng)}</p>}
          </section>
        </aside>
      </section>

      <section className="request-console">
        <div className="console-heading"><div><p className="section-kicker">REQUEST INSPECTOR</p><h2>API 請求紀錄</h2></div><span>{logs.length} REQUESTS</span></div>
        {logs.length === 0 ? (
          <div className="empty-console"><span aria-hidden="true">⌁</span><p>尚無請求</p><small>測試連線、登入或送出 GPS 後，HTTP 狀態與回傳內容會顯示在這裡。</small></div>
        ) : (
          <div className="console-layout">
            <div className="request-list" role="list">
              {logs.map((entry) => <button type="button" role="listitem" key={entry.id} className={selectedLog?.id === entry.id ? 'selected' : ''} onClick={() => setSelectedLogId(entry.id)}>
                <span className={`http-status ${entry.ok ? 'success' : 'failure'}`}>{entry.status ?? 'ERR'}</span>
                <span className="request-path"><strong>{entry.method}</strong>{entry.path}</span><span className="request-meta">{entry.duration} ms · {time(entry.time)}</span>
              </button>)}
            </div>
            {selectedLog && <div className="response-view">
              <div className="response-bar"><span className={selectedLog.ok ? 'success-text' : 'error-text'}>{selectedLog.ok ? 'REQUEST SUCCESS' : 'REQUEST FAILED'}</span><span>{selectedLog.status ? `HTTP ${selectedLog.status}` : 'NETWORK ERROR'}</span></div>
              {responseMessage && <p className={`response-message ${selectedLog.ok ? '' : 'error'}`}>{responseMessage}</p>}
              {selectedLog.request !== undefined && <div className="code-block"><span>REQUEST BODY</span><pre>{JSON.stringify(selectedLog.request, null, 2)}</pre></div>}
              <div className="code-block"><span>RESPONSE</span><pre>{selectedLog.error ?? JSON.stringify(selectedLog.response, null, 2)}</pre></div>
            </div>}
          </div>
        )}
      </section>
    </main>
  );
}
