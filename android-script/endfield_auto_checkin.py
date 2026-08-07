#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
명일방주: 엔드필드 (SKPORT) 안드로이드 Termux / Tasker 자동 출석 스크립트
"""

import json
import os
import sys
import requests
from datetime import datetime

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'config.json')

def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
            return json.load(f)
    return {"cred": "", "last_check_date": ""}

def save_config(config):
    with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
        json.dump(config, f, ensure_ascii=False, indent=2)

def perform_checkin(cred_token):
    url = "https://zonai.skport.com/web/v1/game/endfield/attendance"
    headers = {
        "Accept": "application/json, text/plain, */*",
        "Content-Type": "application/json",
        "cred": cred_token,
        "platform": "3",
        "v": "1.0.0"
    }

    try:
        response = requests.post(url, headers=headers, json={}, timeout=10)
        res_json = response.json()
        print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] API Response: {res_json}")
        
        code = res_json.get("code")
        msg = res_json.get("message", "")
        
        if code == 0 or "already" in msg:
            return True, "출석체크가 성공적으로 완료되었습니다."
        else:
            return False, f"출석 실패 (코드: {code}, 메시지: {msg})"
    except Exception as e:
        return False, f"네트워크 오류: {str(e)}"

def main():
    config = load_config()
    cred = config.get("cred", "").strip()

    if not cred:
        if len(sys.argv) > 1:
            cred = sys.argv[1].strip()
            config["cred"] = cred
            save_config(config)
            print("새로운 cred 토큰이 저장되었습니다.")
        else:
            print("오류: cred 토큰이 설정되지 않았습니다.")
            print("사용법: python endfield_auto_checkin.py [YOUR_CRED_TOKEN]")
            sys.exit(1)

    today_str = datetime.now().strftime("%Y-%m-%d")
    if config.get("last_check_date") == today_str:
        print(f"오늘({today_str})은 이미 출석이 완료되었습니다.")
        sys.exit(0)

    success, message = perform_checkin(cred)
    print(message)

    if success:
        config["last_check_date"] = today_str
        save_config(config)

if __name__ == '__main__':
    main()
