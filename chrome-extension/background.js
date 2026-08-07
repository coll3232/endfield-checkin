// 명일방주 엔드필드 백그라운드 출석체크 Service Worker (Manifest V3)

const ALARM_NAME = 'ENDFIELD_DAILY_CHECKIN_ALARM';
const SKPORT_BASE_URL = 'https://zonai.skport.com';
const ATTENDANCE_URL = `${SKPORT_BASE_URL}/web/v1/game/endfield/attendance`;
const BINDING_URL = `${SKPORT_BASE_URL}/web/v1/game/endfield/binding`;

// 확장프로그램 설치/업데이트 시 초기화
chrome.runtime.onInstalled.addListener(() => {
  console.log('[Endfield Auto Check-in] Service Worker 설치 완료');
  setupAlarm();
  // 설치 직후 5초 뒤 최초 1회 체크 시도
  setTimeout(() => {
    checkAndPerformCheckIn(false);
  }, 5000);
});

// 크롬 브라우저 시작 시 실행
chrome.runtime.onStartup.addListener(async () => {
  console.log('[Endfield Auto Check-in] 크롬 브라우저가 실행되었습니다.');
  setupAlarm();
  
  // 저장된 딜레이 설정 읽기 (기본값 10초)
  const settings = await chrome.storage.local.get({ startupDelaySec: 10, autoCheckInEnabled: true });
  if (settings.autoCheckInEnabled) {
    const delayMs = (settings.startupDelaySec || 10) * 1000;
    console.log(`[Endfield Auto Check-in] ${settings.startupDelaySec}초 후 출석체크를 진행합니다.`);
    setTimeout(() => {
      checkAndPerformCheckIn(false);
    }, delayMs);
  }
});

// 주기적 알람 수신 (매 6시간 마다 확인)
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === ALARM_NAME) {
    console.log('[Endfield Auto Check-in] 정기 알람 발생 -> 출석체크 확인');
    checkAndPerformCheckIn(false);
  }
});

// 알람 등록 함수
function setupAlarm() {
  chrome.alarms.get(ALARM_NAME, (alarm) => {
    if (!alarm) {
      // 360분(6시간) 주기
      chrome.alarms.create(ALARM_NAME, { periodInMinutes: 360 });
    }
  });
}

// 오늘 이미 출석했는지 검사 후 출석 수행
async function checkAndPerformCheckIn(isManualTrigger = false) {
  const todayStr = getTodayString();
  const data = await chrome.storage.local.get(['lastCheckInDate', 'lastCheckInStatus', 'notifyEnabled']);
  const notify = data.notifyEnabled !== false; // 기본값 true

  if (!isManualTrigger && data.lastCheckInDate === todayStr && data.lastCheckInStatus === 'SUCCESS') {
    console.log(`[Endfield Auto Check-in] 오늘(${todayStr})은 이미 출석체크가 완료되었습니다.`);
    return { success: true, message: '오늘 출석이 이미 완료되었습니다.', date: todayStr };
  }

  return await executeCheckIn(todayStr, isManualTrigger, notify);
}

// 실제 SKPORT API 호출 및 출석 처리
async function executeCheckIn(todayStr, isManualTrigger, notify) {
  try {
    // 1. 쿠키 및 토큰 조회
    const cookies = await chrome.cookies.getAll({ domain: 'skport.com' });
    let credToken = '';
    let accountToken = '';

    for (const cookie of cookies) {
      if (cookie.name === 'cred') credToken = cookie.value;
      if (cookie.name === 'ACCOUNT_TOKEN') accountToken = cookie.value;
    }

    const tokenToUse = credToken || accountToken;

    if (!tokenToUse) {
      const msg = 'SKPORT 로그인 정보(쿠키)를 찾을 수 없습니다. 브라우저에서 SKPORT(game.skport.com)에 로그인해 주세요.';
      console.warn(`[Endfield Auto Check-in] ${msg}`);
      await updateCheckInStatus(todayStr, 'NEED_LOGIN', msg);
      if (isManualTrigger || notify) {
        showNotification('로그인 필요', msg);
      }
      return { success: false, status: 'NEED_LOGIN', message: msg };
    }

    // 2. 헤더 구성
    const headers = {
      'Accept': 'application/json, text/plain, */*',
      'Content-Type': 'application/json',
      'cred': tokenToUse,
      'platform': '3',
      'v': '1.0.0'
    };

    // 3. 캐릭터 바인딩 정보 조회 (필요한 경우 sk-game-role 취득)
    let gameRoleHeader = '';
    try {
      const bindingRes = await fetch(BINDING_URL, {
        method: 'GET',
        headers: headers,
        credentials: 'include'
      });
      if (bindingRes.ok) {
        const bindingJson = await bindingRes.json();
        if (bindingJson.code === 0 && bindingJson.data && bindingJson.data.list && bindingJson.data.list.length > 0) {
          const role = bindingJson.data.list[0];
          gameRoleHeader = `3_${role.roleId}_${role.serverId}`;
          headers['sk-game-role'] = gameRoleHeader;
        }
      }
    } catch (e) {
      console.warn('[Endfield Auto Check-in] 바인딩 정보 조회 중 예외 발생 (기본 헤더로 진행):', e);
    }

    // 4. 출석체크 API POST 호출
    const response = await fetch(ATTENDANCE_URL, {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({}),
      credentials: 'include'
    });

    const resJson = await response.json();
    console.log('[Endfield Auto Check-in] API 응답:', resJson);

    // SKPORT 응답 코드: 0(성공) 또는 이미 출석(10001 / 특정 코드)
    if (resJson.code === 0 || (resJson.message && resJson.message.includes('already'))) {
      const successMsg = '명일방주: 엔드필드 일일 출석체크가 완료되었습니다!';
      await updateCheckInStatus(todayStr, 'SUCCESS', successMsg);
      
      if (isManualTrigger || notify) {
        showNotification('출석체크 성공 🎯', successMsg);
      }
      return { success: true, status: 'SUCCESS', message: successMsg, date: todayStr };
    } else {
      const errorMsg = resJson.message || `출석체크 실패 (코드: ${resJson.code})`;
      await updateCheckInStatus(todayStr, 'FAILED', errorMsg);

      if (isManualTrigger || notify) {
        showNotification('출석체크 실패 ⚠️', errorMsg);
      }
      return { success: false, status: 'FAILED', message: errorMsg };
    }

  } catch (err) {
    const errMsg = `네트워크 오류: ${err.message}`;
    console.error('[Endfield Auto Check-in] Exception:', err);
    await updateCheckInStatus(todayStr, 'ERROR', errMsg);
    
    if (isManualTrigger || notify) {
      showNotification('출석체크 오류 ❌', errMsg);
    }
    return { success: false, status: 'ERROR', message: errMsg };
  }
}

// 팝업 요청 메시지 처리 리스너
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.action === 'MANUAL_CHECKIN') {
    checkAndPerformCheckIn(true).then((res) => sendResponse(res));
    return true; // 비동기 응답 처리
  } else if (request.action === 'GET_STATUS') {
    chrome.storage.local.get(['lastCheckInDate', 'lastCheckInStatus', 'lastCheckInMessage', 'lastCheckInTime', 'startupDelaySec', 'autoCheckInEnabled', 'notifyEnabled'], (items) => {
      sendResponse(items);
    });
    return true;
  } else if (request.action === 'UPDATE_SETTINGS') {
    chrome.storage.local.set(request.settings, () => {
      sendResponse({ success: true });
    });
    return true;
  }
});

// 상태 업데이트 헬퍼
async function updateCheckInStatus(dateStr, status, message) {
  const timeStr = new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  await chrome.storage.local.set({
    lastCheckInDate: dateStr,
    lastCheckInStatus: status,
    lastCheckInMessage: message,
    lastCheckInTime: `${dateStr} ${timeStr}`
  });
}

// 오늘 날짜 YYYY-MM-DD
function getTodayString() {
  const d = new Date();
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

// 알림 표시
function showNotification(title, message) {
  chrome.notifications.create({
    type: 'basic',
    iconUrl: 'icons/icon128.png',
    title: `[엔드필드] ${title}`,
    message: message,
    priority: 1
  });
}
