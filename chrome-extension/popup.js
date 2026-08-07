document.addEventListener('DOMContentLoaded', () => {
  const statusChip = document.getElementById('statusChip');
  const statusText = document.getElementById('statusText');
  const lastTimeDisplay = document.getElementById('lastTimeDisplay');
  const messageDisplay = document.getElementById('messageDisplay');
  const cardIcon = document.getElementById('cardIcon');

  const btnCheckIn = document.getElementById('btnCheckIn');
  const toggleAutoCheckIn = document.getElementById('toggleAutoCheckIn');
  const inputDelaySec = document.getElementById('inputDelaySec');
  const toggleNotify = document.getElementById('toggleNotify');

  // 1. 현재 상태 및 설정 로드
  loadCurrentStatus();

  // 2. 수동 출석 버튼 클릭
  btnCheckIn.addEventListener('click', () => {
    btnCheckIn.disabled = true;
    btnCheckIn.querySelector('span').textContent = '출석체크 진행 중...';
    
    chrome.runtime.sendMessage({ action: 'MANUAL_CHECKIN' }, (response) => {
      btnCheckIn.disabled = false;
      btnCheckIn.querySelector('span').textContent = '지금 즉시 출석체크';
      loadCurrentStatus();
    });
  });

  // 3. 설정 변경 이벤트
  toggleAutoCheckIn.addEventListener('change', saveSettings);
  inputDelaySec.addEventListener('change', saveSettings);
  toggleNotify.addEventListener('change', saveSettings);

  function loadCurrentStatus() {
    chrome.runtime.sendMessage({ action: 'GET_STATUS' }, (data) => {
      if (!data) return;

      // 설정값 동기화
      toggleAutoCheckIn.checked = data.autoCheckInEnabled !== false;
      inputDelaySec.value = data.startupDelaySec || 10;
      toggleNotify.checked = data.notifyEnabled !== false;

      // 출석 상태 동기화
      const status = data.lastCheckInStatus || 'PENDING';
      const time = data.lastCheckInTime || '이력 없음';
      const msg = data.lastCheckInMessage || '크롬 실행 시 설정된 시간 후 자동으로 출석합니다.';

      lastTimeDisplay.textContent = time;
      messageDisplay.textContent = msg;

      updateStatusUI(status);
    });
  }

  function updateStatusUI(status) {
    statusChip.className = 'status-chip';
    cardIcon.className = 'card-icon';

    if (status === 'SUCCESS') {
      statusChip.classList.add('success');
      statusText.textContent = '출석 완료';
      cardIcon.classList.add('success');
    } else if (status === 'NEED_LOGIN') {
      statusChip.classList.add('need-login');
      statusText.textContent = '로그인 필요';
    } else if (status === 'FAILED' || status === 'ERROR') {
      statusChip.classList.add('failed');
      statusText.textContent = '출석 실패';
      cardIcon.classList.add('failed');
    } else {
      statusText.textContent = '대기 중';
    }
  }

  function saveSettings() {
    const settings = {
      autoCheckInEnabled: toggleAutoCheckIn.checked,
      startupDelaySec: parseInt(inputDelaySec.value, 10) || 10,
      notifyEnabled: toggleNotify.checked
    };

    chrome.runtime.sendMessage({ action: 'UPDATE_SETTINGS', settings: settings });
  }
});
