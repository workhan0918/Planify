# Planify

바이브 코딩 중간고사 프로젝트: 강좌 개설 → 참여코드 수강 등록 → 과제 제출 → 채점·피드백 → 통계 확인을 제공하는 웹 애플리케이션입니다.

## 기술 스택

- Java 17 이상 / Gradle Wrapper 8.14.3
- Spring Boot 3.5.16 / Spring Data JPA / Spring Security / Bean Validation
- Thymeleaf / SB Admin 2 4.1.4 (Bootstrap 4) / Chart.js 4.4.8
- PostgreSQL 16 / Docker Compose
- JUnit 5 / MockMvc / H2 (자동 테스트 전용)

현재 개발 PC의 Java 21로도 빌드할 수 있으며, Java 17을 대상으로 컴파일합니다.

## 빠른 실행 (Windows PowerShell)

### 현재 PC의 구성

현재 실행 중인 `postgres-boot` 컨테이너(포트 5432)에 Planify 전용 사용자 `planify`와 데이터베이스 `planify`를 생성했습니다. 기존 `bootex` 데이터베이스는 유지됩니다. DB 비밀번호와 최초 강사 비밀번호는 환경변수로 제공하며 저장소에 포함하지 않습니다.

이 PC에서는 Docker Desktop에서 `postgres-boot` 컨테이너를 실행한 후 아래 명령으로 서버만 시작하세요. 동일한 5432 포트를 사용하는 별도 Compose 컨테이너를 동시에 시작할 필요가 없습니다.

```powershell
cd D:\YNC\3-2\java_project\Planify
$env:DB_PASSWORD = Read-Host '현재 Planify DB 비밀번호'
$env:INSTRUCTOR_PASSWORD = Read-Host '초기 강사 비밀번호'
java -jar .\build\libs\Planify-1.0.0.jar
```

### 새 환경에서 실행

Java 17 이상과 Docker Desktop을 설치하고 Docker Desktop을 실행한 후 아래 명령을 사용하세요.

```powershell
cd D:\YNC\3-2\java_project\Planify
$env:DB_PASSWORD = Read-Host '새 DB 비밀번호'
$env:INSTRUCTOR_PASSWORD = Read-Host '초기 강사 비밀번호'
docker compose up -d
.\gradlew.bat bootRun
```

브라우저에서 <http://localhost:8080>에 접속합니다. 최초 실행 시 Gradle과 라이브러리가 다운로드됩니다. SB Admin 2·jQuery·Bootstrap·Chart.js는 CDN에서 불러오므로 화면 스타일과 차트에는 인터넷 연결이 필요합니다.

Linux/macOS에서는 `chmod +x gradlew` 후 `./gradlew bootRun`을 사용합니다.

### 강사 초기 계정

| 항목 | 개발용 기본값 |
| --- | --- |
| 이메일 | `instructor@planify.local` |
| 비밀번호 | `INSTRUCTOR_PASSWORD` 환경변수로 지정 |
| 이름 | 담당 강사 |

강사 계정은 서버가 처음 실행될 때 생성합니다. 공개 회원가입은 항상 학생 역할로만 생성됩니다. 이미 존재하는 강사 계정의 비밀번호는 재시작 시 덮어쓰지 않습니다.

초기 생성 전 환경변수를 설정하면 강사 계정과 DB 연결 정보를 변경할 수 있습니다.

```powershell
$env:INSTRUCTOR_EMAIL = 'teacher@school.ac.kr'
$env:INSTRUCTOR_PASSWORD = '원하는-초기-비밀번호'
$env:INSTRUCTOR_NAME = '담당 강사'
$env:DB_PASSWORD = '원하는-DB-비밀번호'
docker compose up -d
.\gradlew.bat bootRun
```

`DB_PASSWORD`와 `INSTRUCTOR_PASSWORD`는 필수 환경변수입니다. Compose의 DB 비밀번호는 DB 볼륨 최초 생성 시 적용됩니다. 기존 볼륨의 비밀번호를 변경할 때는 DB에서도 비밀번호를 변경해야 합니다. 실제 서비스에서는 별도의 비밀번호와 HTTPS를 사용하세요.

### 기존 PostgreSQL 사용

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/planify'
$env:DB_USERNAME = 'planify'
$env:DB_PASSWORD = '설정한-비밀번호'
$env:INSTRUCTOR_PASSWORD = Read-Host '초기 강사 비밀번호'
.\gradlew.bat bootRun
```

`planify` 데이터베이스와 접근 가능한 사용자 계정을 미리 생성하세요. 개발 편의를 위해 `ddl-auto: update`로 테이블을 생성·갱신합니다.

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/planify` | DB 연결 |
| `DB_USERNAME` | `planify` | DB 사용자 |
| `DB_PASSWORD` | 없음 (필수) | DB 비밀번호 |
| `INSTRUCTOR_EMAIL` | `instructor@planify.local` | 최초 강사 계정 |
| `INSTRUCTOR_PASSWORD` | 없음 (필수) | 최초 강사 비밀번호 |
| `INSTRUCTOR_NAME` | `담당 강사` | 강사 이름 |
| `UPLOAD_DIR` | `./uploads` | 파일 저장 위치 |
| `PORT` | `8080` | 서버 포트 |

## 기능과 사용 순서

### 공통

- 학생 회원가입: 학번·이름·이메일·비밀번호, 학번 및 이메일 중복 방지
- 이메일 로그인 / 로그아웃, BCrypt 비밀번호 저장
- 강사·학생 사이드바 분리 및 서버 접근 권한 검사
- 학번: 영문·숫자·하이픈 최대 30자, 비밀번호: 8~60자 (BCrypt 기준 UTF-8 최대 72바이트)

### 강사

1. **강좌 관리**에서 강좌명·소개를 입력하여 강좌 개설
2. 자동 발급된 10자리 참여코드를 학생에게 안내
3. 강좌 상세에서 등록 학생의 학번·이름·이메일 확인
4. **과제 등록**에서 내용·제출 기간·배점·선택 첨부파일 설정
5. 과제 상세에서 제출/미제출 학생 확인 및 제출 파일 다운로드
6. 0~배점 범위의 점수와 최대 5,000자 피드백 저장
7. **통계 대시보드**에서 강좌별 제출률·평균·미제출 건수와 차트 확인

과제 수정·삭제를 지원합니다. 이미 채점된 점수보다 배점을 낮출 수 없습니다. 과제 삭제는 확인창 후 해당 과제의 제출물·채점 결과·파일을 함께 삭제합니다. 채점 결과와 피드백은 수정할 수 있습니다.

### 학생

1. 학생 회원가입 및 로그인
2. **수강 등록**에서 강사의 참여코드 입력 (대소문자 구분 없음)
3. **내 과제 목록**에서 등록 강좌의 과제와 예정·진행중·마감 배지 확인
4. 과제 상세에서 안내 및 첨부파일 열람, 파일 1개 제출
5. 기간 내 채점 전에는 재제출 가능 (기존 파일 교체)
6. 채점 완료 후 점수·피드백 확인
7. **내 통계**에서 제출률·백분율 평균·24시간 내 마감 임박 과제 확인

## 확정된 업무 규칙

- 기간은 **과제별**로 설정합니다. 강좌에는 기간·배점을 설정하지 않습니다.
- 새 과제의 기본 기간은 **한국 시간 당일 00:00~23:59**, 배점은 **0점**입니다. 강사가 직접 변경합니다.
- 모든 날짜 비교와 표시 기준은 `Asia/Seoul`입니다. 종료 시각 자체까지 제출 가능하며 그 이후에는 차단합니다. 예: 종료를 23:59로 설정하면 23:59:00 이후에는 제출할 수 없습니다.
- 시작일시는 종료일시보다 빨라야 하며, 배점은 0~100,000 정수입니다.
- **학생 제출 파일 1개, 최대 20MiB(20×1024×1024바이트)**. 과제 첨부파일도 같은 크기 제한입니다.
- 파일명이 교수 자료나 다른 제출물과 같아도 업로드할 수 있습니다. 0바이트 파일은 제출할 수 없으며 내용을 저장한 후 제출해야 합니다. 화면과 서버에서 빈 파일을 구분해 안내합니다.
- 기간 밖 제출은 서버에서 거부합니다. 저장 도중 마감되면 새 파일을 정리하고 기존 제출을 유지합니다.
- **기간 내 + 채점 전**에만 재제출할 수 있습니다. **0점으로 채점된 경우도 채점 완료**이므로 재제출할 수 없습니다.
- 업로드 파일은 서버 디스크에 임의 UUID 이름으로 저장하며 원래 파일 이름은 다운로드 시 제공합니다. DB 반영 성공 후 교체된 파일을 정리합니다.
- 제출·채점·수정·삭제 시 과제 행의 DB 잠금으로 동시 변경 충돌을 방지합니다.
- 등록 학생만 과제를 열람합니다. 학생은 자신의 제출 파일만, 강사는 본인 강좌의 제출물만 다운로드합니다.
- 변경 요청은 POST 및 CSRF 검증을 사용합니다. 파일은 공개 정적 경로로 노출하지 않습니다.

## 통계 계산

| 지표 | 기준 |
| --- | --- |
| 과제 제출률 | 제출 학생 수 ÷ 등록 학생 수 × 100 |
| 강좌 제출률 | 제출 건수 ÷ (등록 학생 수 × 과제 수) × 100 |
| 학생 제출률 | 본인의 제출 과제 수 ÷ 등록 강좌 전체 과제 수 × 100 |
| 강사 평균 점수 | 채점 완료된 제출물의 원점수 평균 (0점 채점 포함) |
| 학생 평균 점수 | 채점 완료되고 배점이 0보다 큰 과제들의 `(점수 ÷ 배점) × 100` 산술평균 |
| 마감 임박 | 진행중이고 미제출이며 종료까지 24시간 이내인 과제 |
| 미제출 | 등록 학생 중 해당 과제의 제출물이 없는 학생 |

예정 과제도 제출률의 분모와 미제출 목록에 포함합니다. 등록 학생 또는 과제가 없으면 제출률은 0%입니다. 평균 계산 대상이 없으면 `채점 없음` 또는 `—`를 표시합니다. 강사 평균은 원점수, 학생 평균은 백분율이므로 두 수치는 다를 수 있습니다.

## 빌드 및 테스트

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
java -jar .\build\libs\Planify-1.0.0.jar
```

자동 테스트는 H2의 PostgreSQL 호환 모드를 사용하여 실제 서비스·JPA·Spring Security·Thymeleaf 흐름을 검사합니다. 운영 연결은 PostgreSQL입니다.

검증 범위: 기간 경계, 예정/마감 제출 차단, 파일 크기/빈 파일, 재제출 교체, 채점 후 재제출 차단, 배점 범위, 0점 과제 평균, 등록 여부/역할/파일 접근 권한, CSRF, 학생 역할 고정 회원가입, 로그인, 주요 화면 렌더링, 제출→채점→결과 조회 및 삭제 파일 정리.

### 이번 구현의 검증 결과

- 통합 테스트 **10개 성공**, 실패 0개. 같은 파일명 제출·재제출 및 0바이트 파일 안내 검증 포함
- Java 17 대상 컴파일 및 `bootJar` 생성 성공: `build/libs/Planify-1.0.0.jar`
- 검증 실행 환경: Java 21.0.11, H2 PostgreSQL 호환 모드
- PostgreSQL 16.14 실제 연결 성공: `postgres-boot` 컨테이너 내 Planify 전용 DB 사용
- PostgreSQL에 테이블 5개 및 초기 강사 계정 생성 확인
- 실제 HTTP 요청으로 로그인, 강사 대시보드, 강좌 관리 화면 정상 응답(200) 확인

## 프로젝트 구조

```text
Planify/
├── build.gradle, settings.gradle, gradlew, gradlew.bat
├── compose.yaml
├── src/main/java/com/planify/
│   ├── PlanifyApplication.java       # 시작 및 한국 시간 Clock
│   ├── Account, Course, Enrollment   # 계정·강좌·수강 등록
│   ├── Assignment, Submission        # 과제·제출·채점
│   ├── Repositories.java             # JPA 저장소
│   ├── PlanifyService.java           # 업무 규칙과 통계
│   ├── FileStorage.java              # 파일 저장·교체·정리
│   ├── SecurityConfig, InitialData   # 인증·인가·초기 강사
│   └── Forms, WebController, WebErrors
├── src/main/resources/
│   ├── application.yml
│   ├── templates/                    # 공통·강사·학생 화면
│   └── static/                       # 화면 스타일·차트
└── src/test/                         # 통합 검증
```

관계: 강사 1:N 강좌, 학생 N:M 강좌(Enrollment), 강좌 1:N 과제, 과제 1:N 제출물. 학생당 과제 제출물은 1개이며 재제출 시 갱신합니다.

## 시연 체크리스트

1. 강사 로그인 → 강좌 개설 → 참여코드 확인
2. 별도 브라우저/시크릿 창에서 학생 가입 → 로그인 → 참여코드 수강 등록
3. 강사가 당일 기간과 배점 100의 과제 등록
4. 학생 제출 → 채점 전 재제출 → 강사가 최신 파일 확인
5. 강사 80점 및 피드백 입력 → 학생 결과와 평균 80% 확인
6. 학생 재제출 버튼이 사라지고 직접 요청도 거부되는지 확인
7. 미제출 학생을 추가하여 제출률과 미제출 목록 확인
8. 종료일시를 과거로 변경한 미채점 과제에서 제출 불가 확인

## 운영 참고 및 문제 해결

- DB 연결 오류: Docker Desktop 실행 여부, `docker compose ps`, 포트 5432 및 환경변수를 확인하세요.
- 이미 5432 포트가 사용 중이면 Compose 포트를 `5433:5432`로 바꾸고 DB_URL도 5433으로 설정하세요.
- 파일 업로드 오류: 20MB 제한 및 `UPLOAD_DIR` 쓰기 권한을 확인하세요.
- 파일은 DB에 포함되지 않으므로 **PostgreSQL 데이터와 uploads 폴더를 함께 백업**하세요.
- `docker compose down`은 컨테이너를 중지하고 DB 볼륨은 보존합니다.
- 본 과제에는 비밀번호 찾기·메일 발송·회원 탈퇴·수강 취소·제출 이력 보존 기능은 포함하지 않습니다.

## 사용한 공개 UI 라이브러리

- [SB Admin 2 / MIT](https://github.com/StartBootstrap/startbootstrap-sb-admin-2)
- [Bootstrap / MIT](https://github.com/twbs/bootstrap)
- [jQuery / MIT](https://github.com/jquery/jquery)
- [Chart.js / MIT](https://github.com/chartjs/Chart.js)
- [Spring Boot 3.5 실행 환경 문서](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
