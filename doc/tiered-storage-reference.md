# Apache Pinot Tiered Storage Reference

> **Tags:** `#tiered-storage` `#segment-migration` `#time-based-tier` `#cold-storage` `#S3` `#GCS` `#HDFS` `#instance-partitions` `#segment-selector` `#pinot`

---

## 1. 개념 요약

Tiered Storage는 **세그먼트의 나이에 따라 자동으로 다른 스토리지 계층으로 이동**하는 메커니즘이다.
최신 데이터는 고성능 SSD 서버에, 오래된 데이터는 저비용 스토리지(S3, GCS 등)에 저장한다.

```
Hot Tier (0~7일):    고성능 SSD 서버, 빠른 쿼리
Warm Tier (7~30일):  일반 디스크 서버
Cold Tier (30일+):   S3/GCS, 비용 절감

세그먼트 생성 → 7일 경과 → Warm으로 이동 → 30일 경과 → Cold로 이동
```

---

## 2. 기술 원리

### 2.1 HSM (Hierarchical Storage Management)

Tiered Storage는 1970년대부터 메인프레임에서 사용된 **HSM(계층적 스토리지 관리)** 개념의 현대적 구현이다. 데이터의 접근 빈도(hotness)에 따라 비용-성능이 다른 스토리지 계층에 자동 배치한다.

```
스토리지 계층 피라미드:

  ┌─────────┐  ← L1 Cache (1ns, $/GB 최고)
  │  CPU    │
  ├─────────┤  ← DRAM (10ns)
  │  메모리  │
  ├──────────┤  ← NVMe SSD (100μs)     ← Hot Tier
  │  SSD    │
  ├──────────┤  ← HDD (10ms)           ← Warm Tier
  │  HDD    │
  ├───────────┤  ← S3/GCS (100ms~수초)  ← Cold Tier
  │ 오브젝트  │
  └───────────┘

핵심 관찰:
  데이터 접근은 시간적 지역성(temporal locality)을 따름
  → 최근 데이터일수록 접근 빈도가 높음
  → 오래된 데이터는 드물게 접근됨

  비용 최적화:
    Hot(SSD): $0.10/GB/월, 100μs 응답
    Cold(S3): $0.023/GB/월, 수초 응답
    → 오래된 데이터를 Cold로 이동 시 77% 비용 절감
```

### 2.2 시간 기반 세그먼트 선택의 원리

TimeBasedTierSegmentSelector는 세그먼트의 `endTime`(마지막 데이터 시각)을 기준으로 "나이"를 계산한다. `startTime`이 아닌 `endTime`을 사용하는 이유:

```
왜 endTime인가:
  세그먼트 시간 범위 = [startTime, endTime]

  endTime 기준:
    세그먼트의 모든 데이터가 segmentAge보다 오래되었음을 보장
    → Cold로 이동해도 최근 쿼리에 영향 없음

  startTime 기준 (사용하지 않음):
    세그먼트에 아직 최근 데이터가 포함될 수 있음
    → 너무 일찍 Cold로 이동하여 최근 쿼리 성능 저하

  예: segmentAge=7d, 세그먼트 범위=[10일 전, 3일 전]
    endTime 기준: now - 3d = 3d < 7d → 이동 안 함 (올바른 판단)
    startTime 기준: now - 10d = 10d > 7d → 이동함 (3일 전 데이터도 Cold로!)
```

### 2.3 티어 우선순위 정렬의 원리

여러 티어가 한 세그먼트에 매칭될 수 있으므로, 평가 순서가 중요하다. Pinot는 **"가장 제한적인 티어 먼저"** 규칙을 적용한다.

```
평가 순서: Fixed > Time(나이 큰 순)

왜 이 순서인가:
  Fixed 셀렉터: 관리자가 명시적으로 지정 → 최우선 (의도적 배치)
  Time 셀렉터: 나이 큰 순 (30d > 7d > 1d)
    → 30d 조건을 만족하면 7d도 자동으로 만족
    → 더 제한적인(오래된) 조건부터 평가해야 정확한 배치

  예: 15일 된 세그먼트
    [30d Cold, 7d Warm, 1d Hot] 순으로 평가
    30d: 15d < 30d → 매칭 안 됨
    7d:  15d > 7d  → 매칭! → Warm에 배치 (정확)

  만약 역순(1d → 7d → 30d)으로 평가하면:
    1d: 15d > 1d → 매칭! → Hot에 배치 (오류 - Warm이어야 함)
```

### 2.4 .tier 트래킹 파일의 필요성

서버가 재시작되면 인메모리 상태가 사라진다. `.tier` 파일은 각 세그먼트의 현재 물리적 위치를 디스크에 기록하여, 재시작 후에도 세그먼트를 즉시 찾을 수 있게 한다.

```
.tier 파일이 없다면:
  서버 재시작 시 모든 세그먼트의 위치를 ZooKeeper에 질의해야 함
  → ZooKeeper 부하 증가
  → 세그먼트 수가 많으면 복구 시간 수 분

.tier 파일이 있으면:
  로컬 파일 읽기 → 즉시 세그먼트 위치 확인
  → 복구 시간 수 초

바이너리 포맷을 사용하는 이유:
  JSON/텍스트 대비 파싱 오버헤드 최소화
  고정 구조 → 읽기 시 메모리 할당 최소화
  세그먼트 수가 수천~수만 개일 때 차이가 누적됨
```

### 2.5 Upsert 테이블에서 티어 이동이 불가능한 이유

Upsert의 `validDocIds` 비트맵은 해당 서버 인스턴스에서만 유효한 **인스턴스 종속적(instance-local)** 상태이다.

```
일반 테이블:
  세그먼트 A를 Server-1 → Server-2로 이동
  → 세그먼트 데이터는 자체 완결적 → 어느 서버에서든 동일하게 동작

Upsert 테이블:
  Server-1의 메모리에 validDocIds = {0, 1, 3, 5} (HashMap에서 관리)
  세그먼트 A를 Server-2로 이동하면:
  → Server-2에는 validDocIds 정보가 없음
  → 무효화된 문서(docId=2, 4)도 쿼리에 노출됨 → 중복/오래된 데이터 반환

  해결 불가능한 이유:
    validDocIds는 다른 세그먼트의 레코드와 연동되어 있음
    세그먼트 A의 docId=2가 무효인 이유 = 세그먼트 B의 docId=5가 더 최신
    → 한 세그먼트만 이동하면 이 관계가 깨짐
```

Dedup 테이블은 메타데이터가 ZooKeeper에 저장되어 인스턴스 독립적이므로, MultiTier 이동이 가능하다.

---

## 3. 핵심 구조

### 2.1 Tier 클래스

소스: `Tier.java`

```java
public class Tier {
    String _name;                    // "TIER_HOT", "TIER_COLD" 등
    TierSegmentSelector _selector;   // 어떤 세그먼트를 이 티어로?
    TierStorage _storage;            // 어디에 저장?
}
```

### 2.2 세 가지 구성 요소

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│ TierConfig       │───→│ TierSegmentSelector│    │ TierStorage     │
│ (JSON 설정)      │    │ (선택 기준)        │    │ (저장 위치)      │
│                  │    │                    │    │                  │
│ name             │    │ - TimeBased        │    │ - PinotServer    │
│ segmentSelector  │    │   (나이 기반)       │    │   (서버 태그,    │
│ storageType      │    │ - Fixed            │    │    백엔드 타입,  │
│ serverTag        │    │   (목록 지정)       │    │    데이터 경로)  │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

---

## 4. Segment Selector

### 3.1 TimeBasedTierSegmentSelector

소스: `TimeBasedTierSegmentSelector.java`

```java
// 세그먼트 나이가 임계값을 넘으면 선택
boolean selectSegment(String table, SegmentZKMetadata metadata) {
    if (metadata.getStatus() != COMPLETED) return false;  // 소비 중인 세그먼트 제외
    long endTimeMs = metadata.getEndTimeMs();
    return (System.currentTimeMillis() - endTimeMs) > _segmentAgeMillis;
}
```

```
예시: segmentAge = "7d"

세그먼트 A (endTime: 3일 전)  → (now - 3d) > 7d = FALSE → 이동 안 함
세그먼트 B (endTime: 10일 전) → (now - 10d) > 7d = TRUE  → 이 티어로 이동
```

지원 시간 단위: `d`(일), `h`(시간), `m`(분)

### 3.2 FixedTierSegmentSelector

소스: `FixedTierSegmentSelector.java`

```java
// 명시적으로 지정된 세그먼트 목록만 선택
boolean selectSegment(String table, SegmentZKMetadata metadata) {
    if (metadata.getStatus() != COMPLETED) return false;
    return _segmentsToSelect.contains(metadata.getSegmentName());
}
```

---

## 5. 티어 정렬 우선순위

소스: `TierConfigUtils.java:192-210`

여러 티어가 정의되었을 때 세그먼트를 어느 티어에 배치할지 결정하는 순서:

```
1순위: Fixed 셀렉터 (항상 최우선)
2순위: Time 셀렉터 - 나이 큰 순 (오래된 티어 먼저)

예: [Fixed, 30d Time, 7d Time, 1d Time]
    → Fixed > 30d > 7d > 1d 순으로 평가
    → 첫 번째로 매칭되는 티어에 배치
```

---

## 6. 설정 예시

```json
{
  "tableName": "events_OFFLINE",
  "tierConfigs": [
    {
      "name": "hotTier",
      "segmentSelectorType": "time",
      "segmentAge": "0d",
      "storageType": "pinot_server",
      "serverTag": "hot_OFFLINE"
    },
    {
      "name": "warmTier",
      "segmentSelectorType": "time",
      "segmentAge": "7d",
      "storageType": "pinot_server",
      "serverTag": "warm_OFFLINE"
    },
    {
      "name": "coldTier",
      "segmentSelectorType": "time",
      "segmentAge": "30d",
      "storageType": "pinot_server",
      "serverTag": "cold_OFFLINE",
      "tierBackend": "s3",
      "tierBackendProperties": {
        "dataDir": "s3://my-bucket/pinot/cold-tier"
      }
    }
  ]
}
```

Fixed 셀렉터 예시:

```json
{
  "name": "specialTier",
  "segmentSelectorType": "fixed",
  "segmentList": ["segment_important_1", "segment_important_2"],
  "storageType": "pinot_server",
  "serverTag": "premium_OFFLINE"
}
```

---

## 7. 세그먼트 마이그레이션 흐름

소스: `TierBasedSegmentDirectoryLoader.java`

### 6.1 전체 흐름

```
① Controller: 리밸런스 트리거
   └→ 각 세그먼트에 대해 TierSegmentSelector 평가
   └→ 매칭 티어 결정
   └→ SegmentZKMetadata.tier = "coldTier" 설정
   └→ IdealState 업데이트 (cold 서버로 할당)

② Server: 할당 변경 감지
   └→ TierBasedSegmentDirectoryLoader.load()
       │
       ├── 현재 위치 확인 (.tier 트래킹 파일)
       ├── 대상 위치 계산 (tierBackendProperties.dataDir)
       │
       ├── 같은 위치? → 유지
       └── 다른 위치? → FileUtils.moveDirectory(src, dest)
           └→ .tier 파일에 새 위치 기록
```

### 6.2 .tier 트래킹 파일

소스: `TierBasedSegmentDirectoryLoader.java:154-165`

```
세그먼트별 로컬 트래킹 파일 (바이너리):

┌──────────────────────────────────────┐
│ Version       (4 bytes)              │
│ TierNameLen   (4 bytes)              │
│ TierName      (N bytes) "coldTier"   │
│ PathLen       (4 bytes)              │
│ Path          (M bytes) "/data/cold/segment_001" │
└──────────────────────────────────────┘

용도: 서버 재시작 시 세그먼트의 현재 티어/경로를 빠르게 복원
```

### 6.3 삭제

```java
// 티어 이동된 세그먼트 삭제 시
// .tier 파일에서 현재 경로 읽기 → 해당 경로의 데이터 삭제 → .tier 파일 삭제
```

---

## 8. Instance Partitions (티어별 서버 그룹)

소스: `InstancePartitions.java`

```
티어별로 별도의 InstancePartitions 생성:

events_hotTier:
  partition_0_replica_0: [server-hot-1, server-hot-2]
  partition_0_replica_1: [server-hot-3, server-hot-4]

events_coldTier:
  partition_0_replica_0: [server-cold-1]
  partition_0_replica_1: [server-cold-2]

네이밍: <rawTableName>_<tierName>
```

---

## 9. Controller 측 리밸런싱

소스: `BaseSegmentAssignment.java:94-134`

```java
Pair<List<tierAssignments>, nonTierAssignment> rebalanceTiers(
    currentAssignment, sortedTiers, tierInstancePartitionsMap, bootstrap) {

    // 1. TierSegmentAssignment 생성
    //    → 각 세그먼트를 TierSelector로 평가하여 티어별로 분류

    // 2. 티어별 리밸런싱
    for (Tier tier : sortedTiers) {
        instancePartitions = tierInstancePartitionsMap.get(tier.getName());
        strategy = SegmentAssignmentStrategyFactory.get(tier);
        newAssignment = strategy.reassignSegments(tierAssignment, instancePartitions);
    }

    // 3. 티어에 속하지 않는 세그먼트는 기본 할당 유지
    return (tierAssignments, nonTierAssignment);
}
```

---

## 10. Upsert 테이블과의 관계

소스: `SingleTierStrictRealtimeSegmentAssignment.java`, `MultiTierStrictRealtimeSegmentAssignment.java`

| 테이블 타입 | 티어 이동 | 이유 |
|------------|----------|------|
| **일반 테이블** | 가능 | 제약 없음 |
| **Dedup 테이블** | 가능 (MultiTier) | 메타데이터가 인스턴스 독립적 |
| **Upsert 테이블** | **불가** (SingleTier) | validDocIds 비트맵이 인스턴스 종속적 |

---

## 11. 지원 백엔드

소스: `PinotServerTierStorage.java`

| 백엔드 | 설정값 | 설명 |
|--------|--------|------|
| `local` | `"tierBackend": "local"` | 로컬 디스크 (기본) |
| `s3` | `"tierBackend": "s3"` | Amazon S3 |
| `gcs` | `"tierBackend": "gcs"` | Google Cloud Storage |
| `hdfs` | `"tierBackend": "hdfs"` | Hadoop HDFS |

```json
"tierBackendProperties": {
  "dataDir": "s3://bucket/pinot/cold-tier"
}
```

인스턴스 레벨 오버라이드도 가능:

```
서버 설정에서 티어별 dataDir을 인스턴스 단위로 지정 가능
→ 같은 티어의 서버가 다른 경로를 사용할 수 있음
```

---

## 12. 쿼리 라우팅

```
SELECT * FROM events WHERE timestamp > '2024-01-01'

브로커:
  ① 각 세그먼트의 SegmentZKMetadata.tier 확인
  ② 세그먼트별로 해당 티어의 서버 인스턴스 선택
  ③ 각 서버로 쿼리 라우팅

  segment_001 (hotTier)  → server-hot-1
  segment_002 (warmTier) → server-warm-3
  segment_003 (coldTier) → server-cold-1

  ④ 모든 서버의 결과를 집계하여 반환
```

세그먼트가 어느 티어에 있든 쿼리는 동일하게 동작. 다만 Cold 티어의 서버는 성능이 낮을 수 있으므로 응답 시간이 길어질 수 있다.

---

## 13. 모니터링

소스: `TableTierReader.java`

```
API: GET /tables/{tableName}/tier

응답:
  segment_001:
    server-hot-1:  currentTier="hotTier"   targetTier="hotTier"   ✓ 일치
  segment_002:
    server-warm-3: currentTier="hotTier"   targetTier="warmTier"  ✗ 마이그레이션 대기중
  segment_003:
    server-cold-1: currentTier="coldTier"  targetTier="coldTier"  ✓ 일치

에러 상태:
  NO_RESPONSE_FROM_SERVER:  서버 응답 없음
  SEGMENT_MISSED_ON_SERVER: IdealState에 있으나 서버에 없음
  NOT_IMMUTABLE_SEGMENT:    소비 중 세그먼트 (티어 정보 없음)
```

---

## 14. 주요 소스 파일 맵

```
pinot-spi/.../config/table/
  └── TierConfig.java                       티어 설정 모델

pinot-common/.../tier/
  ├── Tier.java                             티어 추상화 (이름+셀렉터+스토리지)
  ├── TierSegmentSelector.java              셀렉터 인터페이스
  ├── TimeBasedTierSegmentSelector.java     시간 기반 셀렉터
  ├── FixedTierSegmentSelector.java         고정 목록 셀렉터
  ├── TierStorage.java                      스토리지 인터페이스
  ├── PinotServerTierStorage.java           Pinot 서버 스토리지 구현
  └── TierFactory.java                      팩토리 (설정 → Tier 객체)

pinot-common/.../utils/config/
  └── TierConfigUtils.java                  유틸리티 (정렬, 디렉토리 조회 등)

pinot-segment-local/.../loader/
  └── TierBasedSegmentDirectoryLoader.java  세그먼트 마이그레이션 (이동/삭제)

pinot-segment-spi/.../loader/
  ├── SegmentDirectoryLoader.java           로더 인터페이스
  └── SegmentDirectoryLoaderContext.java    로더 컨텍스트

pinot-segment-spi/.../store/
  └── SegmentDirectory.java                 getTier()/setTier()

pinot-common/.../metadata/segment/
  └── SegmentZKMetadata.java                ZK 메타데이터 (tier 필드)

pinot-common/.../assignment/
  └── InstancePartitions.java               티어별 서버 그룹

pinot-controller/.../assignment/segment/
  ├── BaseSegmentAssignment.java            리밸런싱 로직
  ├── MultiTierStrictRealtimeSegmentAssignment.java   Dedup용 (이동 가능)
  └── SingleTierStrictRealtimeSegmentAssignment.java  Upsert용 (이동 불가)

pinot-controller/.../util/
  └── TableTierReader.java                  모니터링 API

pinot-core/.../data/manager/
  └── BaseTableDataManager.java             세그먼트 로딩 시 티어 처리
```

---

## 15. 참조 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| Apache Helix | IdealState 관리, 세그먼트 할당 |
| Apache Commons IO (`FileUtils`) | 세그먼트 디렉토리 이동/삭제 |
| ZooKeeper | SegmentZKMetadata 저장 |
| `TimeUtils` (Pinot 내부) | "7d", "24h" 등 시간 문자열 파싱 |
