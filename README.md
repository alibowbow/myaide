# 들퍼레

Android용 음성 비서 앱이에요. 화면은 Java 뷰로 만들었어요.

## 빌드

JDK 17과 Python 3.12가 필요해요. Android SDK와 라이브러리는 빌드 도구가 내려받아요.

```bash
python3 tools/build.py
```

APK는 `build/` 폴더에 만들어져요.

앱 화면까지 컴파일만 확인하려면:

```bash
python3 tools/build.py --test-only --compile-app
```

## 구성

- `app/` 앱 소스
- `tools/` 빌드와 배포 확인 도구
- `.github/workflows/` APK 빌드와 풀 리퀘스트 확인

쓰인 외부 라이브러리는 `THIRD_PARTY_NOTICES.md`에 있어요.
