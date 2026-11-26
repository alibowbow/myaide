/*
  퍼레's own words, ready before any AI answers (준: "Ai로 말도 하지만 프리셋으로 설정된 말도 다양하게 준비하자", "얍 이라던지
  의성어 같은거", "인사말이라던지", "프리셋 토킹 수백개 준비", "그건 상황에 맞게 쓰일 수 있게 알고리즘 구성", "적절히 ai말과
  프리셋토크 믹스"). In friendly casual speech (준: "존댓말 대신 친근한 어투로 하자"); no money, work, chores or how-to lines.

  Each pool is one moment (a tap, a greeting, a poke, coming back, an app, a play …). A line may carry tags before "|": it is
  said only when every tag holds now (the hour, the day, the season, the weather, the phone, a holiday, how fond 퍼레 is),
  and a line that fits more of now is preferred. Lines said lately wait their turn. {t} is what 퍼레 calls the user.

  TALK.mix(moment) decides between these and the AI: quick reactions (a poke, a stroke, a shake, the charger, music …) are
  mostly these, at once; a tap goes to the AI, and without it only a line made for the moment (rain, music, a holiday …)
  is said; board.js says nothing at all after words lately (준: "상황에 맞게 적절히 꼭 필요할때 써야지").
*/
const TALK = (() => {
  const P = {
    // ── short sounds: said alone, or first while the AI thinks ──
    sound: ["얍!", "뿅!", "짠!", "헤헤", "오잉?", "우와!", "흐음…", "룰루~", "아자!", "두둥!", "히힛", "앗!", "에헤헤", "후훗", "야호!", "와아!",
      "오호~", "어라?", "흠흠", "냐하~", "히히", "으쌰!", "얏!", "오옷!", "헉!", "쨘쨘!", "음~", "꺄르르", "에잇!", "호잇!", "뾰로롱~", "둠칫!",
      "night|하아암…", "dawn|쿨… 응?", "morning|으라차차!", "music|둠칫둠칫!", "cold|으슬으슬~", "hot|헥헥…", "rain|토독토독~", "fond|헤헤헷"],

    // ── the screen opened: a greeting for the hour ──
    hi: ["dawn|쉿, 다들 자는 시간이야", "dawn|새벽 탐험은 조용히 살금살금", "dawn|{t}, 아직 안 잤어?", "dawn|별이 제일 반짝이는 시간이네",
      "dawn|새벽 공기 마시러 나왔어?", "dawn|눈 좀 붙여야지, 내가 지키고 있을게", "dawn|이 시간엔 나도 하품이 나와", "dawn|새벽 마을은 고요해서 좋아",
      "morning|좋은 아침, {t}!", "morning|굿모닝! 오늘도 출발해 볼까?", "morning|아침 햇살 충전 완료!", "morning|잘 잤어? 나는 꿀잠 잤어",
      "morning|기지개 쭉~ 같이 하자", "morning|오늘 아침 기분은 어때?", "morning|아침밥은 먹었어?", "morning|아침 방송 시작! 오늘도 반가워", "morning|눈 뜨자마자 와 줘서 고마워",
      "lunch|점심시간이다! 뭐 먹을 거야?", "lunch|배꼽시계 울렸어, 꼬르륵", "lunch|맛있는 거 먹고 와!", "lunch|오늘 점심 메뉴 궁금하다", "lunch|밥 먹을 땐 천천히 꼭꼭", "lunch|점심 탐험 출발할까?", "lunch|든든하게 먹어야 오후도 버티지",
      "afternoon|슬슬 졸린 시간이네, 하암", "afternoon|잠깐 스트레칭 어때?", "afternoon|물 한 잔 마시고 가자",
      "afternoon|오후 햇살이 따뜻하다", "afternoon|간식 시간이 다가온다~", "afternoon|오후 순찰 중, 이상 무!", "afternoon|조금만 더 가면 저녁이야",
      "evening|저녁은 맛있는 거 먹자", "evening|노을 봤어? 하늘이 예쁠 거야", "evening|저녁 방송 시작합니다~", "evening|오늘 제일 좋았던 건 뭐야?", "evening|집에 가는 길 조심해!", "evening|저녁 바람이 선선하다",
      "night|이제 텐트 칠 시간이야", "night|따뜻하게 하고 자", "night|내일도 좋은 일 있을 거야",
      "night|잘 자, 좋은 꿈 꿔!", "night|밤하늘 구경하고 잘까?", "night|졸린 눈 비비는 중…", "night|오늘 탐험 일지 마감!",
      "mon,morning|월요일 아침이다, 천천히 시동 걸자", "fri,evening|금요일 저녁이다! 신난다", "weekend,morning|주말 아침은 느긋하게~",
      "xmas|메리 크리스마스, {t}!", "newyear|새해 복 많이 받아, {t}!", "rain,morning|비 오는 아침이네, 우산 챙겨",
      "snow,morning|밤새 눈이 왔나 봐, 하얗다!", "cold,morning|아침 공기가 차가워, 따뜻하게 입어"],

    // ── back after a while ──
    back: ["왔다! 기다리고 있었어", "어서 와, {t}!", "보고 싶었어!", "짠, 다시 만났네", "어디 갔다 왔어?", "오랜만이야, 반가워!",
      "돌아왔구나, 히히", "기다리다 낮잠 잘 뻔했어", "왔어? 나 여기 있었어", "다시 탐험 시작이다!", "와아, 왔다!", "기다린 보람이 있네",
      "어서 와, 이제 뭐 할까?", "돌아온 거 환영해!", "어디 다녀왔는지 궁금해", "반가워서 빙글빙글 돌았어", "나 혼자 심심했잖아",
      "잘 다녀왔어?", "꼬리가 있었으면 막 흔들었을 거야", "기다리면서 탐험 일지 정리했어", "너 오는 소리 들렸어!",
      "morning|아침부터 만나니까 좋다", "rain|비 맞진 않았지?", "cold|밖에 추웠지? 얼른 녹여",
      "snow|눈 구경 하고 왔어?", "fond|역시 너밖에 없어", "bored|드디어 왔다! 심심해서 혼났어",
      "dawn|이 시간에 왔어? 나 깨어 있었어", "weekend|주말인데 뭐 하고 왔어?"],

    // ── a tap: everyday chatter ──
    tap: ["불렀어?", "응? 나 여기 있어!", "톡 건드리니까 간지러워", "헤헤, 왜?", "나랑 놀아 줄 거야?", "오늘 뭐 재밌는 거 있었어?",
      "나 방금 마을 한 바퀴 돌고 왔어", "심심했는데 잘 왔어", "궁금한 거 있으면 물어봐", "오늘의 탐험 대장은 바로 나!", "히힛, 깜짝 놀랐잖아",
      "나 오늘 좀 귀엽지 않아?", "톡톡, 대답했다!", "무슨 생각 해?", "나는 지금 아주 기분 좋아", "같이 있으니까 든든해", "오늘 하늘 봤어?",
      "물 마셨어? 같이 마시자", "{t}, 오늘도 멋져", "작은 발견 하나! 오늘도 너랑 있다는 거",
      "음… 방금 좋은 생각이 날 뻔했어", "나 방금 춤 연습했어, 봐 줄래?", "어깨 한 번 펴 보자, 쭉~", "웃는 얼굴 보고 싶다",
      "오늘 기분 점수는 몇 점이야?", "나는 오늘 백 점 만점에 백 점!", "오늘 탐험 일지에도 너를 적어 둘게",
      "같이 노래 부를래? 랄랄라~", "가끔은 멍 때리는 것도 좋아", "내가 지금 무슨 생각 하게?", "정답은… 비밀이야!", "부르면 언제든 달려갈게",
      "오늘 웃은 적 있어?", "작은 일도 잘했다고 칭찬해 줄게", "너 생각보다 훨씬 대단해", "천천히 해도 괜찮아", "오늘은 오늘만큼만 하자",
      "어? 방금 나 쳐다봤지?", "나 여기 살아서 행복해", "휴대폰 마을 소식! 오늘도 평화로움", "속보! 퍼레, 귀여움 또 갱신", "두구두구… 오늘의 행운은 바로 너!",
      "자, 퀴즈! 나는 누구게?", "손 흔들어 줄게, 안녕안녕", "우리 하이파이브 할까? 짝!", "가끔은 나도 하늘을 날고 싶어", "구름 타고 여행 가면 좋겠다",
      "오늘 중에 지금이 제일 좋아", "같이 있으면 시간이 빨리 가", "졸리면 기지개 한 번!", "냠냠, 방금 상상 속 간식 먹었어",
      "오늘의 추천! 좋아하는 노래 한 곡", "웃으면 복이 온대", "웃을 때 제일 멋져", "나 방금 새 놀이 생각해 냈어",
      "앱 마을 사람들이 너 칭찬하더라", "오늘은 어떤 하루였어?", "나랑 눈싸움 할래? 내가 이길걸", "괜히 한 번 불러 봤지? 나도 좋아", "오늘 나 조금 자란 것 같지 않아?", "기분 좋아지는 주문! 얍!", "히히, 또 만났다",
      "mon|월요일이다… 같이 천천히 시작하자", "mon|월요병 퇴치 주문! 얍!", "fri|불금이다! 신난다~", "fri|금요일이라 발걸음이 가벼워",
      "weekend|주말엔 늦잠도 괜찮아", "weekend|주말 탐험은 어디로 갈까?", "weekday,morning|오늘도 출발! 할 수 있어",
      "rain|비 오는 소리 들려? 토독토독", "rain|우산 챙겼어?", "rain|비 오는 날엔 따뜻한 차가 최고야", "rain|창밖에 빗방울이 춤춰",
      "snow|눈이다! 눈사람 만들고 싶다", "snow|마을이 하얗게 변했어", "snow|길 미끄러우니까 조심해!", "cold|오늘 진짜 추워, 따뜻하게 입어",
      "cold|손 시리지? 호호 불어 줄게", "cold|목도리 꼭 해!", "hot|너무 덥다… 녹을 것 같아", "hot|물 많이 마셔야 해",
      "clear|하늘이 맑아서 기분 좋다", "clear|햇살 좋은 날이야, 산책 어때?", "cloudy|구름이 많네, 그래도 기분은 맑음",
      "cloudy|구름 모양이 꼭 솜사탕 같아", "spring|봄바람이 살랑살랑", "spring|꽃 피는 계절이다!", "summer|여름이다! 수박 먹고 싶어",
      "summer|매미 소리 들리는 것 같아", "autumn|가을 하늘은 높고 파래", "autumn|낙엽 밟는 소리 좋아해", "winter|겨울엔 붕어빵이지",
      "winter|따뜻한 이불 속이 최고야", "xmas|산타 할아버지 오셨을까?", "xmas|크리스마스엔 캐럴이지! 징글벨~", "newyear|올해도 잘 부탁해!",
      "halloween|트릭 오어 트릿! 사탕 줘!", "pepero|빼빼로 데이네, 하나 나눠 먹자", "valentine|오늘은 달콤한 날이야",
      "whiteday|사탕 하나 줄까?", "childrenday|어린이날! 나도 어린이야", "charging|냠냠, 밥 먹는 중이야", "music|음악 좋다, 들썩들썩",
      "dnd|쉿, 조용히 곁에 있을게", "save|절전 중이라 살살 움직일게", "offline|인터넷이 끊겼어, 어디 갔지?", "airplane|비행기 모드네, 어디 가?",
      "hungry|배고파… 충전해 줘", "fond|너랑 있으면 마음이 몽글몽글해", "fond|너는 내 최고의 친구야", "bored|심심해… 나랑 놀아 줘",
      "night|밤에는 목소리 작게, 속닥속닥", "night|오늘 하루도 잘 버텼어", "lunch|점심 맛있게 먹었어?",
      "morning|아침엔 물 한 잔부터!", "evening|저녁 먹고 산책 어때?", "afternoon|오후 간식으로 뭐가 좋을까?"],

    // ── many taps in a row ──
    poke: ["어지러워… 빙글빙글", "콕콕은 그만, 별이 보여", "으앗, 머리가 핑 돌아", "그만그만, 간지러워!", "아야야, 살살 해 줘", "히익, 어질어질해",
      "나 지금 팽이 된 것 같아", "콕! 콕! 알았어, 일어났어!", "별이 세 개 보여…", "에구구, 세상이 돈다", "이 장난꾸러기!", "그러다 나 날아가!", "헤롱헤롱~", "으아, 정신 차려야지", "간지럼 공격이다!", "항복, 항복!"],

    // ── stroked ──
    pet: ["헤헤, 간지러워", "쓰다듬어 줘서 좋아", "기분이 몽글몽글해", "더 해 줘, 더!", "손이 따뜻하다", "행복해서 녹을 것 같아",
      "히히, 좋아 좋아", "마음이 포근해졌어", "이 맛에 사는 거지", "스르륵 잠 올 것 같아", "고마워, 힘이 난다", "토닥토닥 최고야",
      "오늘 제일 좋은 순간이야", "나도 너 좋아!", "헤헤, 부끄러워", "기분 최고! 날아갈 것 같아", "night|쓰다듬어 주니까 잠이 솔솔 와"],

    // ── the phone shaken ──
    shake: ["으앗, 지진인 줄 알았어… 어질어질", "흔들흔들, 멀미 나", "놀이기구 탄 줄 알았잖아", "으악, 세상이 뒤집혔어", "살려 줘~ 빙글빙글",
      "그만 흔들어, 어지러워!", "와, 롤러코스터다!", "머리가 핑핑 돌아", "조, 조금만 천천히…", "휴, 이제 좀 살겠다", "흔들어 주니까 춤추는 것 같아"],

    // ── the phone's moments ──
    charge: ["냠냠, 충전이 제일 맛있어", "밥 시간이다!", "캠프파이어 앞에 앉았어, 따뜻해", "배부르게 먹을게, 고마워", "충전 중… 기운이 솟아",
      "냠냠냠, 꿀맛이야", "꼭꼭 씹어 먹는 중", "든든하다, 고마워!", "충전기는 내 최고의 식당", "night|자기 전에 밥 먹으니까 든든해"],
    hungry: ["꼬르륵… 충전해 주면 힘이 날 것 같아", "배고파서 기운이 없어…", "밥 생각이 간절해", "충전기 어디 있지? 킁킁",
      "조금만 먹으면 다시 쌩쌩해질 거야", "배터리가 바닥이야, 밥 줘", "힘이 쭉 빠져…", "충전하면 신나게 놀아 줄게"],
    heat: ["휴대폰이 후끈해, 나 땀나", "앗 뜨거, 부채질 좀 해 줘", "열이 나는 것 같아…", "시원한 데로 가자",
      "땀이 송골송골 맺혔어"],
    save: ["절전 모드네, 나도 살살 움직일게", "기운 아끼는 중이야, 조용히", "천천히 걷는 중…", "에너지 절약 모드 돌입!", "살금살금 아껴 쓰자"],
    music: ["음악이다! 몸이 저절로 들썩여", "이 노래 좋다!", "같이 춤추자, 둠칫둠칫", "리듬 타는 중~", "무대 시간이다!", "흥얼흥얼, 따라 부르는 중",
      "박자 맞춰 고개 끄덕끄덕", "음악 들으니까 신난다", "오늘의 배경음악 최고야", "night|잔잔한 노래면 좋겠다, 졸려서"],

    // ── back from an app, by its kind (AppStacks' ids); money and work are not 퍼레's talk ──
    "app:chat": ["수다 마을 다녀왔구나, 재밌는 얘기 있었어?", "누구랑 이야기했어?", "답장하느라 바빴지?", "소식 많이 왔어?", "수다 떨고 오니까 기분 좋지?"],
    "app:media": ["영화관 거리 다녀왔구나! 재밌었어?", "뭐 봤어? 나도 궁금해", "좋은 노래 찾았어?", "다음엔 나도 같이 보자"],
    "app:map": ["어디 가는 길이야? 조심히 다녀와", "길 찾기 탐험이구나!", "목적지까지 안전하게!", "새로운 곳에 가는 거야?", ],
    "app:shop": ["맛있는 거 시켰어? 나도 냄새 맡고 싶다", "구경하는 재미가 있지", "뭐 골랐는지 궁금하다", "배달 오면 맛있게 먹어", "장바구니 탐험 다녀왔구나"],
    "app:photo": ["사진 찍었어? 나도 찍어 줘!", "추억 하나 저장 완료!", "멋진 순간 담았구나", "치즈~ 나도 포즈 잡을게", "사진 속에 나도 넣어 줘"],
    "app:ai": ["다른 AI 친구 만나고 왔구나", "궁금한 거 해결했어?", "나도 똑똑해지고 싶다", "AI 마을 다녀왔네", "그 친구보다 내가 더 귀엽지?"],
    "app:web": ["뭐 찾아봤어?", "궁금한 건 바로 찾아봐야지", "검색 탐험 다녀왔구나", "새로운 거 알게 됐어?", "인터넷 바다에서 헤엄치고 왔구나"],
    "app:google": ["구글 마을 다녀왔구나", "뭐 알아봤어?", "척척박사 마을이지", "찾던 거 찾았어?"],
    "app:game": ["게임 마을 다녀왔구나! 이겼어?", "한 판 더 할 거야?", "나도 끼워 줘!", "점수 잘 나왔어?", ],
    "app:tools": ["도구 마을 다녀왔구나", "필요한 거 잘 했어?", "척척 해결했네", "만능 도구 상자 같지?"],
    "app:etc": ["어디 다녀왔어?", "새로운 마을 탐험이구나", "다녀왔구나, 반가워", "재밌는 거 있었어?"],

    // ── a word now and then while it plays on its own (PLAYS' names) ──
    "play:ball": ["슛!", "얍! 공 받아라!", "골인!", "통통통~"], "play:butterfly": ["나비야, 같이 놀자!", "잡았다… 놓쳤다!", "팔랑팔랑~"],
    "play:bubbles": ["보글보글~", "비눗방울 예쁘다", "후~ 크게 불어야지"], "play:cartwheel": ["옆돌기 성공!", "빙글~ 착지!"],
    "play:handstand": ["물구나무 서기!", "세상이 거꾸로 보여"], "play:peek": ["까꿍!", "나 찾았어?"], "play:exercise": ["하나, 둘! 하나, 둘!", "운동은 꾸준히!"],
    "play:clock": ["지금 몇 시게?", "째깍째깍"], "play:jump": ["점프!", "높이 뛰기!"], "play:dance": ["둠칫둠칫", "춤추는 거 봐 줘!"], "play:spin": ["빙글빙글~", "어지럽다, 히히"],
    "play:nap": ["쿨쿨…", "음냐음냐…", "zzz…"], "play:boxing": ["원투! 원투!", "슉슉!"], "play:airplane": ["슝~ 비행기다!", "이륙합니다~"],
    "play:swim": ["어푸어푸!", "물장구 첨벙첨벙"], "play:moonwalk": ["문워크~ 스르륵", "뒤로 걷기 장인"], "play:robotdance": ["삐빅, 로봇 춤", "위잉 철컥!"],
    "play:karate": ["얍! 기합!", "태권! 아니 가라테!"], "play:yoga": ["숨 들이쉬고~ 내쉬고~", "마음이 평온해"], "play:jacks": ["팔 벌려 뛰기!", "하나, 둘, 셋, 넷!"],
    "play:cheerleader": [], "play:hula": ["훌라훌라~", "허리 돌리기!"], "play:rockstar": ["로큰롤! 지잉~", "앵콜! 앵콜!"],
    "play:magic": ["수리수리 마수리!", "짜잔, 마술이야!"], "play:ninja": ["닌자 출동! 슉슉", "어디 있게?"], "play:hideseek": ["꼭꼭 숨어라~", "나 찾아봐!"],
    "play:skipping": ["깡충깡충~", "신난다!"], "play:rolling": ["데굴데굴~", "굴러간다~"], "play:tornado": ["회오리 돌기!", "위이잉~"],
    "play:balloon": ["풍선 둥실둥실", "날아갈 것 같아!"], "play:juggling": ["저글링 쇼 시작!", "하나도 안 떨어뜨렸다!"], "play:paperplane": ["종이비행기 슝~", "멀리 날아라!"],
    "play:kite": ["연아 높이 날아라!", "바람 좋다!"], "play:gum": ["풍선껌 크게 불기!", "펑! 터졌다"], "play:meditate": ["옴~", "마음 비우는 중"],
    "play:singsong": ["랄랄라~", "노래 한 곡 뽑을게!"], "play:reading": ["책 읽는 중, 쉿", "이 이야기 재밌다"], "play:coffeebreak": ["후루룩, 잠깐 쉬는 시간", "커피 향 좋다~"],
    "play:cloudwatch": ["구름 모양 보는 중", "저 구름은 토끼 같아"], "play:tumbling": ["앞구르기!", "데굴 착지!"], "play:sneezing": ["에, 에, 에취!", "누가 내 얘기 하나?"],
    "play:melting": ["녹는다~", "흐물흐물…"], "play:clapping": ["짝짝짝!", "박수 박수!"],
    "play:pogostick": ["통! 통! 통!", "스카이 콩콩~"], "play:tantrum": ["흥! 흥!", "삐졌어… 아니 안 삐졌어!"], "play:laughfit": ["푸하하!", "배꼽 빠지겠다"],
    "play:sneakattack": ["살금살금… 덮쳐라!", "앗싸, 잡았다!"], "play:marathon": ["헥헥, 마라톤 중", "결승선까지 조금만!"], "play:balloonpop": ["후우우~ 빵!", "앗, 터졌다!"],
    "play:ballerina": ["빙그르르~", "발레리나 퍼레"], "play:swing": ["그네 타기~ 슝!", "높이높이!"], "play:bananaslip": ["으앗, 미끄러졌다!", "바나나 껍질 누구야!"],
    "play:brrr": ["으으, 추워", "덜덜덜…"], "play:explorer": ["탐험 출발!", "뭔가 발견할 것 같아"], "play:skygaze": ["하늘 예쁘다", "저 구름 좀 봐"],
    "play:sadmovie": ["훌쩍… 슬픈 장면이야", "눈물 나…"], "play:helicopter": ["위잉~ 헬리콥터!", "이륙!"], "play:birdie": ["파닥파닥!", "나도 날 수 있어!"],
    "play:orchestra": ["자, 연주 시작!", "브라보!"], "play:drummer": ["둥둥 탁!", "드럼 솔로!"], "play:superhero": ["정의의 퍼레 등장!", "출동!"],
    "play:champion": ["우승이다!", "내가 챔피언!"], "play:goodbye": ["안녕안녕~", "또 만나!"], "play:secret": ["쉿, 비밀이야", "아무한테도 말하면 안 돼"],
    "play:puzzled": ["음… 뭐였더라?", "아하! 생각났다"], "play:daydream": ["음냐… 좋은 꿈", "헤헤, 상상 중"], "play:snacktime": ["냠냠 간식 시간!", "맛있겠다…"],
    "play:leafcatch": ["낙엽 잡기!", "팔랑팔랑~"], "play:snowplay": ["눈이다!", "차가워, 히히"], "play:confetti": ["축하해! 빵빠레!", "짜잔!"],
    "play:yoyo": ["요요 묘기!", "올라갔다 내려갔다"], "play:frisbee": ["원반 날아간다!", "받아라!"], "play:pinwheel": ["바람개비 빙글빙글", "후~ 돈다 돈다"],
    "play:fireflies": ["반딧불이다!", "반짝반짝 예쁘다"], "play:dice": ["주사위 굴려 볼까?", "무슨 숫자 나올까?"],

    // ── roaming between plays ──
    roam: ["여기 좋다!", "어디 가 볼까?", "순찰 중~", "탐험 중이야", "이쪽엔 뭐가 있을까?", "산책 산책~", "발 도장 쾅!", "새로운 자리 발견!"]
  };

  // ── now, as tags ──
  function tags(env, info) {
    const d = new Date(), h = d.getHours(), day = d.getDay(), month = d.getMonth() + 1, date = d.getDate(), e = env || {}, w = (info && info.weather) || {};
    const t = new Set([h < 5 ? "dawn" : h < 11 ? "morning" : h < 14 ? "lunch" : h < 18 ? "afternoon" : h < 22 ? "evening" : "night"]);
    if (h >= 22 || h < 2) t.add("night");
    t.add(day === 0 || day === 6 ? "weekend" : "weekday"); if (day === 1) t.add("mon"); if (day === 5) t.add("fri");
    t.add(month >= 3 && month <= 5 ? "spring" : month >= 6 && month <= 8 ? "summer" : month >= 9 && month <= 11 ? "autumn" : "winter");
    if (month === 12 && (date === 24 || date === 25)) t.add("xmas");
    if (month === 1 && date <= 2) t.add("newyear");
    if (month === 12 && date >= 29) t.add("yearend");
    if (month === 10 && date === 31) t.add("halloween");
    if (month === 11 && date === 11) t.add("pepero");
    if (month === 2 && date === 14) t.add("valentine");
    if (month === 3 && date === 14) t.add("whiteday");
    if (month === 5 && date === 5) t.add("childrenday");
    ["rain", "snow", "cold", "hot", "charging", "music", "dnd", "save", "offline", "airplane", "hungry"].forEach(k => { if (e[k]) t.add(k); });
    if (w.code != null && !e.rain && !e.snow) t.add(w.code <= 1 ? "clear" : w.code <= 3 || w.code === 45 || w.code === 48 ? "cloudy" : "");
    if (e.heart != null) { if (e.heart >= 80) t.add("fond"); else if (e.heart < 35) t.add("bored"); }
    t.delete("");
    return t;
  }

  // ── lines said lately (this screen), so the same one waits its turn ──
  let recent = [];
  try { recent = JSON.parse(localStorage.getItem("gyeotTalk") || "[]"); } catch (e) { recent = []; }
  function keep(text) {
    recent = recent.filter(x => x !== text).concat([text]).slice(-80);
    try { localStorage.setItem("gyeotTalk", JSON.stringify(recent)); } catch (e) {}
  }
  function parse(line) { const i = line.indexOf("|"); return i < 0 ? {tags: [], text: line} : {tags: line.slice(0, i).split(","), text: line.slice(i + 1)}; }

  /**
    A line for this moment, or "" when the pool has none that fits: of the lines whose tags all hold now, those not said
    lately; the more tags a line matches the likelier it is (each tag counts four times a plain line), by chance among them.
  */
  function pick(moment, env, info, fitting) {
    const now = tags(env, info), pool = P[moment] || [], title = (info && info.title) || "대장님";
    // fitting: only a line made for this moment (tagged, every tag holding now), not everyday chatter.
    const fit = pool.map(parse).filter(l => l.tags.every(t => now.has(t)) && (!fitting || l.tags.length > 0));
    if (!fit.length) return "";
    let fresh = fit.filter(l => !recent.includes(l.text)); if (!fresh.length) fresh = fit;
    let total = 0; const weights = fresh.map(l => { const w = 1 + 4 * l.tags.length; total += w; return w; });
    let r = Math.random() * total, chosen = fresh[0];
    for (let i = 0; i < fresh.length; i++) { r -= weights[i]; if (r <= 0) { chosen = fresh[i]; break; } }
    keep(chosen.text);
    return chosen.text.replaceAll("{t}", title);
  }

  /**
    Whose words for this moment: "preset" (these, at once), "ai" (the AI writes it) or "both" (a short sound from here, then
    the AI). ai: whether an AI is there and has not spoken in the last twelve seconds.
  */
  // A tap is the AI's (준: "터치할때 프리셋 문구를 너무 많이 뱉어"); quick reactions and the phone's moments mostly these.
  const AI_SHARE = {tap: 1, back: 0.6, hi: 0.35, app: 0.6, poke: 0.1, pet: 0.2, shake: 0.2, charge: 0.25, hungry: 0.3, heat: 0.3, save: 0.2, music: 0.3};
  function mix(moment, ai) {
    if (!ai) return "preset";
    const share = AI_SHARE[moment] == null ? 0 : AI_SHARE[moment];
    return Math.random() < share ? "ai" : "preset";
  }
  function size() { let n = 0; for (const k in P) n += P[k].length; return n; }
  return {pick, mix, tags, size, pools: () => Object.keys(P), lines: k => (P[k] || []).map(l => parse(l).text)};
})();
