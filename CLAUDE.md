# magicdosa — 코드 작업 안내

MTG(모던, 고정 덱 1~4개, 사이드보드 포함 Bo3) 강화학습 AI. Forge에서 학습하고, 공통 프로토콜을 쓰는
엔진(XMage, Arena 자문 모드)이면 어디서든 둔다. 목표는 사람 흉내가 아니라 사람보다 강한 플레이.

## 기준 문서

- 설계 문서 (모든 결정의 출처): https://claude.ai/artifact/E92JE6JZxX2YA8PbQCm8gS
- 선행 사례 조사: https://claude.ai/artifact/FLpGpVZDdwTzFSPTbQStNQ
- 작업 대화 구성: https://claude.ai/artifact/WqTXJCtAiFtsRdovg6PN6T

설계를 바꿔야 할 것 같으면 코드에서 임의로 바꾸지 말고 사용자에게 알린다. 설계 결정은 기획 대화에서
정하고 설계 문서에 반영한다. 설계를 바꾸지 않는 구현 메모는 `docs/`에 남겨도 된다.

## 구조

| 경로 | 내용 |
| --- | --- |
| `pom.xml` | Maven 부모 (`magicdosa-parent`). IntelliJ는 저장소 루트를 연다 |
| `proto/magicdosa/v1/` | 프로토콜 (proto3, 외부 엔진은 JSON 매핑) |
| `protocol/` | proto에서 생성한 Java + `Versions.PROTOCOL` |
| `agent-core/` | 탐색, 추론(ONNX), 그림자 게임(내장 Forge), 인코더, 확정 정보, belief |
| `agent-core/src/test/java/magicdosa/agent/{determinism,bench}` | 재현 하네스·벤치 (main 프로그램, `mvn install` 때 실행 안 됨) |
| `adapter/forge-adapter/` | 학습용 실제 Forge 게임 → 프로토콜, agent-core에 결정 요청 (`adapter/`에는 pom 없음) |
| `python/magicdosa` | 학습, 평가, ONNX 내보내기 (`python/`을 별도 프로젝트로 연다) |
| `forge/` | Forge git 서브모듈 (고정 커밋) |
| `decks/` | 테스트 덱 |

Java 패키지: `magicdosa.protocol`, `magicdosa.agent`, `magicdosa.adapter.forge`.
groupId `magicdosa`, Java 17, Maven.

## 명령 (Windows PowerShell, 저장소 루트 기준)

```powershell
.\scripts\install-forge.ps1          # Forge 서브모듈을 로컬 Maven에 설치 (서브모듈 커밋이 바뀔 때마다)
mvn install                          # 전체 빌드 (첫 빌드에서 의존성 조회 실패가 캐시됐으면 -U)
mvn -pl agent-core exec:exec         # 재현 하네스 (기본: decks/ IzzetProwess vs EsperBlink, 40매치 순차 2회 + 8스레드 처리량)
mvn -pl agent-core exec:exec "-Dexec.mainClass=magicdosa.agent.bench.ShadowBench" "-Dexec.args=<절대경로 decks> 200"
mvn -pl adapter/forge-adapter exec:exec   # 1단계 게이트: 랜덤 정책 vs Forge AI (기본 20매치, 비의도 폴백 0이면 PASS)
mvn -pl adapter/forge-adapter exec:exec "-Dexec.mainClass=magicdosa.adapter.forge.harness.AdapterDeterminism" "-Dexec.args=<절대경로 decks> IzzetProwess.dck EsperBlink.dck 20"
mvn -pl adapter/forge-adapter exec:exec "-Dexec.mainClass=magicdosa.adapter.forge.harness.LogReplay" "-Dexec.args=<절대경로 decks> IzzetProwess.dck EsperBlink.dck 10 <절대경로 빈 로그 폴더>"
cd python; pip install -e ".[dev]"
```

forge-adapter 구현 메모(구성, 대응표 생성, 아직 근사인 부분)는 `docs/forge-adapter.md`.

- 이 PC에는 PATH에 `mvn`·`java`가 없다. IntelliJ 내장 Maven(`E:\IntelliJ\IntelliJ IDEA 2026.2.3\plugins\maven-plugin\lib\maven3\bin`)과
  `JAVA_HOME=E:\jdk\jdk17`을 셸에서 잡아 쓴다.
- Forge를 띄우는 프로그램은 작업 디렉터리가 `forge/forge-gui`여야 한다(`res/`). agent-core의 exec 설정이 그렇게
  되어 있으므로 `exec.args`의 경로는 절대 경로로 준다.
- 헤드리스 실행 준비: `java.awt.headless=true`, `java.util.Arrays.useLegacyMergeSort=true`,
  `GuiBase.setInterface(new GuiDesktop())`, `FModel.initialize(null, null)`.
- `GameState.applyToGame`은 이름이 `Game`으로 시작하는 스레드에서 불러야 즉시 실행된다.
- git: `forge/` 작업 트리가 커서 `git status`가 느리다. `git status --ignore-submodules=all`을 쓴다.

## Forge 규칙

- `forge/` 안은 수정하지 않고, Forge 클래스를 바꿔 끼우는 패치도 만들지 않는다.
- Forge 문제는 우리 코드에서 우회하거나 업스트림 PR 후보로 사용자에게 알린다.
- 서브모듈 커밋을 올리면 `agent-core`의 `GameCopier`에 업스트림 변경을 반영하고 순차 재현 하네스를 돌려
  `different=0`을 확인한다.
- 성능 문제는 포크에서 따로 고치지 않는다. 측정 자료를 남기고 사용자에게 알린다(업스트림 논의 대상).

## 지켜야 할 설계 결정 (자세한 내용은 설계 문서)

- **두 JVM:** forge-adapter와 agent-core는 별도 JVM이고 gRPC로 연결한다(Forge 경로는 proto3 바이너리,
  외부 엔진은 JSON). 신경망 추론은 agent-core가 ONNX Runtime으로 한다.
- **인코더는 agent-core 한 곳에만.** Python은 인코딩을 다시 구현하지 않고 agent-core가 쓴 텐서만 읽는다.
- **합법 행동은 엔진이 준다.** AI가 후보를 만들지 않는다.
- **모든 결정은 agent-core가 내린다.** 정책 → 그림자 게임 탐색 → 그림자 게임의 Forge AI(의도된 위임) 순.
  기록은 정책 / 탐색 / 의도된 위임 / 비의도 폴백으로 나누고, 탐색 생략(망 단독)은 따로 표시한다.
  비의도 폴백이 하나라도 있으면 그 평가는 무효다. 조용히 Forge AI로 넘어가는 코드를 만들지 않는다.
- **그림자 게임:** 매 턴 시작에 턴 시작 스냅샷으로 다시 만들고(숨은 카드는 belief 샘플대로), 그 턴의
  사건 스트림을 다시 적용한다. 결정마다 공개 정보 지문(L1 기본, L2 객체, L3 합법 행동)을 해시 하나로
  비교하고, 다를 때만 필드 단위 diff로 원인을 기록한다.
- **엔티티 ID:** 존에 들어갈 때마다 새 ID(규칙 400.7), 존 이동 사건에 `prev_id`. 스택 위 능력도 ID를
  받는다(유발 능력은 유발된 순간). **능력 키**는 엔진 중립 이름: `(oracle_id, 오라클 문구 순번)`,
  키워드는 이름+매개변수. 카드 식별은 Scryfall `oracle_id`.
- **탐색:** agent-core 자체 IS-MCTS. 후보는 합법 행동 전체, 예산은 시간이 아니라 노드 수, 결과는 후보별
  방문 비율(ExIt 정책 정답). Forge `FlatMonteCarloPicker`는 학습 상대·평가 기준선으로만 쓴다.
- **재현:** 학습은 병렬·비재현으로 돌리고 전체 로그를 남긴다. 디버깅은 로그 재생으로 한다. 회귀 테스트는
  단일 스레드 순차 + 시드로 한다(같은 JVM에서 같은 시드를 두 번 돌려 `different=0`).
  결정 경로에 시간 의존 로직(시간 예산, 벽시계)을 넣지 않는다. 탐색 예산은 노드 수다.
  스레드별 난수는 `InheritableThreadLocal`(Forge AI가 결정마다 스레드를 만든다).
  Forge 게임 생성은 `magicdosa.agent.shadow.GameCreation`을 거친다(Game ID 전역 카운터가 동기화되지 않음).
  학습 시드는 재사용하지 않고, 평가 시드 집합과 holdout은 따로 둔다.
- **학습 전용 정보:** 숨은 정보는 `privileged` 블록에만 담고 critic·belief 라벨에만 쓴다. 추론 모드에서
  이 블록이 들어오면 거부한다.
- **버전:** 프로토콜(`Versions.PROTOCOL`), 인코더(`AgentCore.ENCODER_VERSION`), 모델에 각각 버전을 두고
  구조를 바꿀 때마다 올린다. Python 쪽 `magicdosa/versions.py`도 같이 올린다. 게임 시작 시 하나라도
  다르면 게임을 시작하지 않는다.
- **학습 데이터:** agent-core가 게임 종료 시 Apache Arrow 샤드를 쓴다. 프로토콜 로그가 원본이며,
  로그에는 처음부터 결정 응답의 탐색 결과와 `privileged` 블록을 남긴다(방문 비율은 나중에 되살릴 수 없다).

## 지금 단계

0단계(기반 검증) 완료. **1단계: forge-adapter + agent-core 골격.**
- 프로토콜을 proto로 정의: 결정 요청·응답, 사건 스트림(행동·정보 공개·상태 변화, 순번), 턴 시작 스냅샷,
  엔티티 ID·능력 키, 버전 핸드셰이크(지금 `handshake.proto`만 있음)
- `RlPlayerController`(`PlayerControllerAi` 상속, 프로토콜에 정의된 결정만 오버라이드), 사건 스트림 송신
- agent-core: gRPC 서버, 관측 인코더, 폴백·위임 기록
- 로그로 한 판을 재연해 같은 결과가 나오는지 확인
- 게이트: 랜덤 정책으로 수천 판 완주, 비의도 폴백 0

다음은 2단계(그림자 추적, Forge 대 Forge 지문 검증)다.

## 작업 규칙

- 사용자와는 한국어. 코드, 주석, 커밋 메시지는 영어.
- 커밋·푸시는 사용자가 요청할 때만 한다.
- 새 결정 유형이나 메시지를 추가하면 proto와 버전을 함께 고친다.
- 변경 후에는 `mvn install`이 통과하는지 확인하고, Forge 동작에 닿는 변경이면 재현 하네스도 돌린다.
