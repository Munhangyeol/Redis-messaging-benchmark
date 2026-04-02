# Git 자동화 (Auto Add → Commit → Push)

## 설명
Git 작업 흐름을 완전히 자동화하는 스킬입니다. 변경사항 확인, 스테이징, 커밋 메시지 생성, 커밋, 원격 저장소 푸시까지 한 번에 처리합니다.

## 사용법
```bash
/git-auto
```

커스텀 커밋 메시지 사용:
```bash
/git-auto "feat: 새로운 스킬 추가"
```

커밋만 하고 푸시 안함:
```bash
/git-auto --no-push
```

## 동작 과정

1. **변경사항 확인**
   - `git status`로 수정/추가/삭제된 파일 확인
   - `git diff`로 변경 내용 분석
   - 변경사항이 없으면 작업 종료

2. **커밋 메시지 자동 생성**
   - 변경된 파일과 내용을 분석하여 의미있는 메시지 생성
   - Conventional Commits 형식 사용:
     - `feat:` - 새로운 기능 (스킬, 몬스터, UI 등)
     - `fix:` - 버그 수정
     - `refactor:` - 코드 리팩토링
     - `docs:` - 문서 수정 (README, CLAUDE.md)
     - `style:` - 코드 포맷팅, CSS 변경
     - `test:` - 테스트 관련
     - `chore:` - 기타 작업

3. **자동 스테이징**
   - 모든 변경사항을 `git add .`로 스테이징
   - 민감한 파일(.env 등)은 자동으로 제외됨

4. **커밋 실행**
   - Co-Authored-By 태그와 함께 커밋
   - 커밋 메시지 형식:
     ```
     type: 간결한 설명

     Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
     ```

5. **자동 푸시**
   - 현재 브랜치를 원격 저장소에 자동으로 푸시
   - `--no-push` 옵션 사용 시 생략

## 옵션

- `--no-push` - 커밋만 하고 푸시하지 않음
- `-m "메시지"` - 커스텀 커밋 메시지 사용
- `--amend` - 이전 커밋에 변경사항 추가

## 예시

### 예시 1: 기본 사용 (스킬 추가 후)
```bash
/git-auto
```

**분석 결과:**
```
변경된 파일:
- game.html (새 스킬 perform함수 추가)
- CLAUDE.md (스킬 정보 업데이트)

자동 생성된 커밋 메시지:
feat: 지진 타격 스킬 추가

파일 스테이징 중...
✓ game.html staged
✓ CLAUDE.md staged

커밋 생성 중...
[main a1b2c3d] feat: 지진 타격 스킬 추가
 2 files changed, 78 insertions(+), 5 deletions(-)

원격 저장소에 푸시 중...
✓ Successfully pushed to origin/main
```

### 예시 2: 커스텀 메시지
```bash
/git-auto "refactor: 전사군 스킬 구조 개선"
```

### 예시 3: 커밋만 (푸시 안함)
```bash
/git-auto --no-push
```

**결과:**
```
[main def4567] fix: 몬스터 충돌 버그 수정
 1 file changed, 12 insertions(+), 8 deletions(-)

푸시가 생략되었습니다. (--no-push)
수동으로 푸시하려면: git push
```

## 프로젝트 특화 패턴

### 게임 기능별 커밋 패턴

**스킬 추가:**
```bash
/git-auto  # → "feat: [스킬명] 스킬 추가"
# 예: feat: 독 단검 스킬 추가
```

**몬스터 추가:**
```bash
/git-auto  # → "feat: [몬스터명] 몬스터 타입 추가"
# 예: feat: 불타는 버그 몬스터 타입 추가
```

**버그 수정:**
```bash
/git-auto  # → "fix: [문제] 버그 수정"
# 예: fix: 스킬 쿨다운 표시 오류 수정
```

**리팩토링:**
```bash
/git-auto  # → "refactor: [대상] 구조 개선"
# 예: refactor: Effect 클래스 draw 메서드 분리
```

**파일 분리 작업:**
```bash
/git-auto  # → "refactor: [모듈명] 모듈 분리"
# 예: refactor: combat.js 모듈 분리
```

**UI/스타일 개선:**
```bash
/git-auto  # → "style: [대상] 스타일 개선"
# 예: style: 스킬바 쿨다운 진행원 추가
```

**문서 업데이트:**
```bash
/git-auto  # → "docs: [파일명] 업데이트"
# 예: docs: plan.md Phase 1 체크리스트 업데이트
```

## 주의사항

1. **변경사항 확인**
   - 자동으로 모든 파일을 스테이징하므로 불필요한 파일 주의
   - 임시 파일이나 디버그 코드가 포함되지 않았는지 확인

2. **브랜치 확인**
   - main/master 브랜치에 직접 푸시하므로 주의
   - 협업 시 feature 브랜치 사용 권장

3. **원격 저장소 충돌**
   - 푸시 전 원격 저장소의 최신 변경사항 확인
   - 충돌 시 수동으로 pull 후 다시 시도

4. **민감한 파일**
   - .env, credentials.json 등은 자동으로 제외됨
   - .gitignore 설정 확인 필수

5. **의미있는 커밋**
   - 너무 많은 변경사항을 한 커밋에 포함하지 말 것
   - 논리적으로 관련된 변경사항만 함께 커밋

## 워크플로우 예시

### 전형적인 작업 흐름

1. **새 스킬 개발**
   ```bash
   # 1. game.html에서 스킬 구현
   # 2. 브라우저에서 테스트
   # 3. 완료 후 자동 커밋
   /git-auto
   ```

2. **버그 수정**
   ```bash
   # 1. 버그 재현 및 원인 파악
   # 2. 코드 수정
   # 3. 테스트로 수정 확인
   # 4. 커밋
   /git-auto "fix: 스킬 쿨다운 버그 수정"
   ```

3. **Phase 단위 작업**
   ```bash
   # Phase 1 작업 완료 시
   /git-auto "feat: Phase 1 스탯 시스템 구현 완료"

   # Phase 2 작업 완료 시
   /git-auto "feat: Phase 2 초변직 시스템 구현 완료"
   ```

## 관련 명령어

- `git status` - 변경사항 수동 확인
- `git log` - 커밋 히스토리 조회
- `git diff` - 변경 내용 상세 보기
- `git push` - 수동 푸시 (--no-push 사용 시)
- `git reset HEAD~1` - 마지막 커밋 취소

## 트러블슈팅

### 푸시 실패 시
```bash
# 원격 저장소의 최신 변경사항 가져오기
git pull --rebase

# 다시 푸시
git push
```

### 잘못된 커밋 메시지 수정
```bash
# 마지막 커밋 메시지 수정 (푸시 전)
git commit --amend -m "올바른 메시지"

# 푸시 전이라면 정상 푸시
git push
```

### 민감한 파일 실수로 커밋한 경우
```bash
# 파일 제거 (히스토리에서 완전 삭제는 별도 작업 필요)
git rm --cached 파일명
git commit -m "chore: 민감한 파일 제거"
```
