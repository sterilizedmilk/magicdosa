# magicdosa — 코드 작업 안내

MTG(모던, 고정 덱 1~4개, 사이드보드 포함 Bo3) 강화학습 AI. Forge에서 학습하고, 공통 프로토콜을 쓰는
엔진(XMage, Arena 자문 모드)이면 어디서든 둔다. 목표는 사람 흉내가 아니라 사람보다 강한 플레이.

## 기준 문서

- 설계 문서 (모든 결정의 출처): https://claude.ai/artifact/E92JE6JZxX2YA8PbQCm8gS
- 선행 사례 조사: https://claude.ai/artifact/FLpGpVZDdwTzFSPTbQStNQ
- 작업 대화 구성: https://claude.ai/artifact/WqTXJCtAiFtsRdovg6PN6T

### 설계 문서: 작업 종류별로 읽을 탭 (2026-11-10, 탭 8개)

필요한 탭만 읽는다. 전체 문서를 통째로 읽지 않는다. 탭 링크는 설계 문서 주소 뒤에 `#<탭 ID>`를 붙인다.

| 탭 (ID) | 내용 | 언제 읽나 |
| --- | --- | --- |
| 개요 (`90cca2a6-073c`) | 목표, 전제, 로드맵, 단계 완료 판정 | 작업 시작 전 항상 |
| 구조·프로토콜 (`271e0e1e-94d9`) | 모듈 구성(adapter/forge, runner/forge), 프로토콜, 결정론 수준, 어댑터 설계 | 모듈·proto·어댑터·재현 작업 |
| 결정 기록 (`17a062f4-2e17`) | 프로토콜 세부 결정 Q1~Q20, 확정된 설계 사항 | 결정 유형·메시지·키를 다룰 때 |
| 그림자·탐색 (`cf0ebe1c-d703`) | 그림자 게임 추적·검증·복구·지문, 탐색 분기, 성능 방침 | 그림자 게임, 탐색, 성능 |
| 관측·행동 (`0a65d0a1-a203`) | 확정 정보 계산, 관측·행동 스키마, 결정 주체 원칙 | 인코더, 관측, 후보, 결정 주체 |
| 학습·평가 (`78483d9c-9a2f`) | belief, 멀리건, Bo3·사이드보딩, 학습·보상, 평가·폴백 검증 | 학습, 평가, belief, 게이트 판정 |
| 미결·리스크 (`7c5fd0bb-1bd6`) | 아직 안 정한 것 | 여기 있는 항목은 임의로 정하지 말고 질문한다 |
| 기록 보관 (`abb124c3-f95a`) | 과거 측정·조사 결과 | 참고용 |

모듈은 adapter/forge(변환 라이브러리)와 runner/forge(실행기)로 나뉘었다(2026-11-10). 설계 문서의 "forge-adapter"는
둘을 함께 가리킨다. 이름이 어긋나면 코드 기준으로 해석한다.

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
| `adapter/forge/` | Forge ↔ 프로토콜 변환 라이브러리(엔티티 ID, 능력 키, 관측, 사건, 후보, 결정 계층 `AgentPlayerController`, `GameCreation`). agent-core와 runner/forge가 쓴다. agent-core에 의존하지 않는다 |
| `runner/forge/` | 학습·게이트용 실제 Forge 게임 실행(매치, gRPC 클라이언트, 게임 로그, 후보·복사 점검, 하네스) (`adapter/`·`runner/`에는 pom 없음) |
| `python/magicdosa` | 학습, 평가, ONNX 내보내기 (`python/`을 별도 프로젝트로 연다) |
| `forge/` | Forge git 서브모듈 (고정 커밋) |
| `decks/` | 테스트 덱 |

Java 패키지: `magicdosa.protocol`, `magicdosa.agent`, `magicdosa.adapter.forge`, `magicdosa.runner.forge`.
groupId `magicdosa`, Java 17, Maven.

## 명령 (Windows PowerShell, 저장소 루트 기준)

```powershell
.\scripts\install-forge.ps1          # Forge 서브모듈을 로컬 Maven에 설치 (서브모듈 커밋이 바뀔 때마다)
mvn install                          # 전체 빌드 (첫 빌드에서 의존성 조회 실패가 캐시됐으면 -U)
mvn -pl agent-core exec:exec         # 재현 하네스 (기본: decks/ IzzetProwess vs EsperBlink, 40매치 순차 2회 + 8스레드 처리량)
mvn -pl agent-core exec:exec "-Dexec.mainClass=magicdosa.agent.bench.ShadowBench" "-Dexec.args=<절대경로 decks> 200"
mvn -pl agent-core exec:exec "-Dexec.mainClass=magicdosa.agent.AgentCore" "-Dexec.args=--port 50051"   # agent-core gRPC 서버 (1단계: 랜덤 정책)
mvn -pl runner/forge exec:exec   # 1단계 게이트: 랜덤 정책 vs Forge AI (기본 20매치, agent-core를 별도 JVM으로 띄움, 비의도 폴백 0이면 PASS)
mvn -pl runner/forge exec:exec "-Dexec.mainClass=magicdosa.runner.forge.harness.AdapterDeterminism" "-Dexec.args=<절대경로 decks> IzzetProwess.dck EsperBlink.dck 20"
mvn -pl runner/forge exec:exec "-Dexec.mainClass=magicdosa.runner.forge.harness.LogReplay" "-Dexec.args=<절대경로 decks> IzzetProwess.dck EsperBlink.dck 10 <절대경로 빈 로그 폴더>"
mvn -pl runner/forge exec:exec "-Dexec.mainClass=magicdosa.runner.forge.harness.AllPairings" "-Dexec.args=<절대경로 decks> 60 <절대경로 빈 로그 폴더> --threads 8"   # 16조합 게이트. --threads는 스레드 병렬이라 판정에 쓰지 않음(프로세스 병렬 실행기로 교체 예정)
cd python; pip install -e ".[dev]"
```

forge-adapter 구현 메모(구성, 대응표 생성, 아직 근사인 부분)는 `docs/forge-adapter.md`. 어댑터 하네스의 마지막 인자로
agent-core 연결을 고른다: `spawn`(기본, 별도 JVM + gRPC), `host:port`, `local`(같은 JVM, gRPC 없음).

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
- Forge 문제는 우리 코드에서 우회하거나 업스트림 PR 후보로 사용자에게 알린다. 후보는 `docs/upstream-candidates.md`에 모은다.
- 서브모듈 커밋을 올리면 `agent-core`의 `GameCopier`에 업스트림 변경을 반영하고 순차 재현 하네스를 돌려
  `different=0`을 확인한다.
- 성능 문제는 포크에서 따로 고치지 않는다. 측정 자료를 남기고 사용자에게 알린다(업스트림 논의 대상).

## 지켜야 할 설계 결정 (자세한 내용은 설계 문서의 해당 탭)

- **두 JVM:** runner/forge와 agent-core는 별도 JVM이고 gRPC로 연결한다(Forge 경로는 proto3 바이너리,
  외부 엔진은 JSON). 신경망 추론은 agent-core가 ONNX Runtime으로 한다.
- **인코더는 agent-core 한 곳에만.** Python은 인코딩을 다시 구현하지 않고 agent-core가 쓴 텐서만 읽는다.
- **합법 행동은 엔진이 준다.** AI가 후보를 만들지 않는다.
- **모든 결정은 agent-core가 내린다.** 정책 → 그림자 게임 탐색 → 그림자 게임의 Forge AI(의도된 위임) 순.
  기록은 정책 / 탐색 / 의도된 위임 / 비의도 폴백으로 나누고, 탐색 생략(망 단독)은 따로 표시한다.
  비의도 폴백이 하나라도 있으면 그 평가는 무효다. 조용히 Forge AI로 넘어가는 코드를 만들지 않는다.
- **그림자 게임 (2026-11-10 결정, 그림자·탐색 탭):** 그림자 게임을 이어서 추적하고(그림자 전용 `Game-shadow-*`
  스레드에서 Forge 루프 `PhaseHandler.mainLoopStep()`을 직접 돌린다. 양쪽 좌석은 사건 기록에서 답하는 스크립트
  컨트롤러이고, 답이 아직 없으면 멈춰 기다린다. 결정 요청이 동기화 지점), 매 턴 시작에 `GameMemento`를 저장한다
  (직전 턴 정리 단계 끝의 루프 경계). 복구는 멈춘 컨트롤러가 예외로 스레드를 풀고 Memento 복원 후 루프를 다시 돈다.
  공개 선택은 모두 `ResolutionChoice`로 사건 스트림에 낸다(프로토콜 9, 숨은 선택은 privileged에만).
  결정마다(턴 시작 포함) 공개 정보 지문(L1 기본, L2 객체, L3 합법 행동)을 해시 하나로 비교하고, 다를 때만
  필드 단위 diff로 원인을 기록한다. 불일치 복구: ① 턴 시작 Memento로 되돌려 그 턴 사건 재적용 ② 턴 시작
  스냅샷으로 재생성(숨은 카드는 belief 샘플) ③ 그래도 다르면 그 턴의 남은 결정은 망 단독, '탐색 생략'으로
  원인과 함께 기록. 매 턴 강제 재생성은 2단계 검증의 측정 모드로만 쓴다.
- **엔티티 ID:** 존에 들어갈 때마다 새 ID(규칙 400.7), 존 이동 사건에 `prev_id`. 스택 위 능력도 ID를
  받는다(유발 능력은 유발된 순간). **능력 키**는 엔진 중립 이름: `(oracle_id, 오라클 문구 순번)`,
  키워드는 이름+매개변수. 카드 식별은 Scryfall `oracle_id`.
- **탐색:** agent-core 자체 IS-MCTS. 후보는 합법 행동 전체, 예산은 시간이 아니라 노드 수, 결과는 후보별
  방문 비율(ExIt 정책 정답). Forge `FlatMonteCarloPicker`는 학습 상대·평가 기준선으로만 쓴다.
- **재현:** 학습은 병렬·비재현으로 돌리고 전체 로그를 남긴다. 디버깅은 로그 재생으로 한다. 회귀 테스트는
  단일 스레드 순차 + 시드로 한다(같은 JVM에서 같은 시드를 두 번 돌려 `different=0`).
  결정 경로에 시간 의존 로직(시간 예산, 벽시계)을 넣지 않는다. 탐색 예산은 노드 수다.
  스레드별 난수는 `InheritableThreadLocal`(Forge AI가 결정마다 스레드를 만든다).
  Forge 게임 생성은 `magicdosa.adapter.forge.GameCreation`을 거친다(Game ID 전역 카운터가 동기화되지 않음).
  학습 시드는 재사용하지 않고, 평가 시드 집합과 holdout은 따로 둔다.
- **학습 전용 정보:** 숨은 정보는 `privileged` 블록에만 담고 critic·belief 라벨에만 쓴다. 추론 모드에서
  이 블록이 들어오면 거부한다.
- **버전:** 프로토콜(`Versions.PROTOCOL`), 인코더(`AgentCore.ENCODER_VERSION`), 모델에 각각 버전을 두고
  구조를 바꿀 때마다 올린다. Python 쪽 `magicdosa/versions.py`도 같이 올린다. 게임 시작 시 하나라도
  다르면 게임을 시작하지 않는다.
- **학습 데이터:** agent-core가 게임 종료 시 Apache Arrow 샤드를 쓴다. 프로토콜 로그가 원본이며,
  로그에는 처음부터 결정 응답의 탐색 결과와 `privileged` 블록을 남긴다(방문 비율은 나중에 되살릴 수 없다).

## 지금 단계

0단계(기반 검증) 완료. 1단계(forge-adapter + agent-core 골격) 완료(2026-11-10, protocol 8 / encoder 3 /
vocab 3). 최종 게이트 AllPairings 16×60(8스레드) PASS: 비의도 폴백·거부·미매핑·미분류 0, `cost:*` 위임 0,
게임 복사 실패 0, 순차 재현·로그 재연 통과. 구현 메모와 남은 의도된 위임(`orderCosts`, `chooseNewTargetsFor`)은
`docs/forge-adapter.md`.

**2단계: 그림자 추적, Forge 대 Forge 지문 검증.** 범위와 게이트는 설계 문서 개요 탭의 2단계 단락, 구조는
그림자·탐색 탭.

- 게이트(AllPairings)는 병렬로 돌린다. 재현 하네스와 로그 재연만 순차로 돌린다.
- **병렬은 프로세스 단위 (2026-11-11 결정):** 한 JVM에서 Forge 게임을 여러 스레드로 동시에 돌리면 게임끼리 간섭한다
  (Forge 능력 ID 전역 카운터가 스레드 안전하지 않음, `docs/upstream-candidates.md` 1번). 실행기·게이트·agent-core·
  ShadowCheck는 JVM 하나에 한 번에 게임 하나만 돌리고, 병렬은 프로세스를 여러 개 띄워서 한다. `--threads`로 낸 결과는
  게이트 판정에 쓰지 않는다. 업스트림 수정이 고정 커밋에 들어오면 스레드 병렬로 돌아온다.
- 엔진 거부는 0건이 목표다. 거부가 한 건이라도 나오면 어댑터 결함으로 본다(재요청은 안전망).

## 작업 규칙

- 사용자와는 한국어. 코드, 주석, 커밋 메시지는 영어.
- 커밋·푸시는 사용자가 요청할 때만 한다.
- 새 결정 유형이나 메시지를 추가하면 proto와 버전을 함께 고친다.
- 변경 후에는 `mvn install`이 통과하는지 확인하고, Forge 동작에 닿는 변경이면 재현 하네스도 돌린다.
