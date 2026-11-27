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

퍼레가 말을 알아듣고 맞는 도구를 고르는지 보려면(`app/src/main/assets/buddy/scenarios.json`의 문장을 앱과 같은 요청으로 NVIDIA NIM에 보내요. 키나 인터넷이 없으면 말 단계만 봐요):

```bash
python3 tools/buddy_eval.py            # 음성 대화
python3 tools/buddy_eval.py --path all # 퍼레와 대화(글)까지
```

## 구성

- `app/` 앱 소스
- `tools/` 빌드와 배포 확인 도구
- `.github/workflows/` APK 빌드와 풀 리퀘스트 확인

쓰인 외부 라이브러리는 `THIRD_PARTY_NOTICES.md`에 있어요.
