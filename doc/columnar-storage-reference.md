# Apache Pinot Columnar Storage Reference

> **Tags:** `#columnar-storage` `#forward-index` `#dictionary-encoding` `#chunk-compression` `#segment` `#pinot` `#off-heap` `#mmap` `#inverted-index` `#V3-format`

---

## 1. 개념 요약

Pinot는 행(Row) 단위로 입력된 데이터를 **컬럼별로 분리**하여 세그먼트에 저장한다.
쿼리 시 필요한 컬럼만 읽으므로, 수십억 행에서도 불필요한 I/O 없이 빠른 집계가 가능하다.

```
입력 (행 단위):
  Row0: {city="Seoul", amount=1500, ts=1700000000}
  Row1: {city="Busan", amount=3200, ts=1700000001}

저장 (컬럼 단위):
  city.fwd:    ["Seoul", "Busan", ...]     ← 컬럼별 별도 파일
  amount.fwd:  [1500, 3200, ...]
  ts.fwd:      [1700000000, 1700000001, ...]

SELECT SUM(amount) → amount.fwd만 읽음 (city, ts 안 읽음)
```

---

## 2. 세그먼트 생성 흐름

소스: `SegmentColumnarIndexCreator.java`, `ColumnIndexCreators.java`

```
SegmentWriter.init(TableConfig, Schema)
  └→ SegmentColumnarIndexCreator 생성
      └→ 컬럼마다 ColumnIndexCreators 생성
          ├→ SegmentDictionaryCreator   (딕셔너리 인코딩 시)
          ├→ ForwardIndexCreator        (컬럼 값 저장)
          ├→ InvertedIndexCreator       (선택)
          └→ NullValueVectorCreator     (선택)

SegmentWriter.collect(GenericRow) × N번
  └→ indexRow(GenericRow)
      └→ 각 컬럼별로:
          ① 값 추출
          ② 딕셔너리에 등록 → dictId 획득
          ③ ForwardIndexCreator에 dictId 기록
          ④ 기타 인덱스에 기록

SegmentWriter.flush()
  └→ seal()
      ├→ 각 컬럼의 .dict, .fwd, .inv 파일 flush
      └→ metadata.properties에 컬럼 통계 기록
```

---

## 3. 딕셔너리 인코딩

소스: `SegmentDictionaryCreator.java`

### 3.1 원리

```
원본:     ["Seoul", "Seoul", "Busan", "Seoul", "Busan"]
              ↓
딕셔너리:  {0: "Busan", 1: "Seoul"}   → .dict 파일
인코딩값:  [1, 1, 0, 1, 0]            → .fwd 파일 (INT 배열)
```

문자열을 정수 ID로 치환하여 **공간 절약 + 비교 연산 가속**.

### 3.2 딕셔너리 생성 자료구조

소스: `SegmentDictionaryCreator.java:65` 부근

| 데이터 타입 | HashMap 구현 | 키 크기 |
|------------|-------------|--------|
| INT | `Int2IntOpenHashMap` | 4B |
| LONG | `Long2IntOpenHashMap` | 8B |
| FLOAT | `Float2IntOpenHashMap` | 4B |
| DOUBLE | `Double2IntOpenHashMap` | 8B |
| STRING, BYTES, BIG_DECIMAL | `Object2IntOpenHashMap` | 가변 |

### 3.3 딕셔너리 파일 포맷 (.dict)

```
고정 길이 (INT, LONG, FLOAT, DOUBLE):
  ┌──────────────────────────────────┐
  │ value[0]  value[1]  value[2] ... │  정렬된 순서로 저장
  └──────────────────────────────────┘
  크기 = cardinality × sizeof(type)

가변 길이 (STRING, BYTES):
  ┌─────────────────────────────────────────┐
  │ VarLengthValueWriter 포맷               │
  │ [offset0][offset1]...[data0][data1]...  │
  └─────────────────────────────────────────┘
```

### 3.4 딕셔너리 읽기 클래스 계층

소스: `BaseImmutableDictionary.java`

```
BaseImmutableDictionary
  ├── IntDictionary            (4B 고정)
  ├── LongDictionary           (8B 고정)
  ├── FloatDictionary          (4B 고정)
  ├── DoubleDictionary         (8B 고정)
  ├── StringDictionary         (가변)
  ├── OnHeapStringDictionary   (JVM 힙에 로드)
  ├── BytesDictionary          (가변)
  ├── BigDecimalDictionary     (가변)
  └── ConstantValue*Dictionary (단일 값 최적화)
```

딕셔너리 검색: `indexOf(value)` → dictId (정렬 저장이므로 이진 탐색)

---

## 4. 포워드 인덱스 (Forward Index)

### 4.1 유형

| 파일 확장자 | 유형 | 설명 |
|------------|------|------|
| `.sv.sorted.fwd` | Sorted SV | 정렬된 컬럼. (minDocId, maxDocId) 쌍 저장 |
| `.sv.unsorted.fwd` | Unsorted SV | 딕셔너리 인코딩된 단일값 |
| `.sv.raw.fwd` | Raw SV | 딕셔너리 미사용, 원본 값 그대로 |
| `.mv.fwd` | Unsorted MV | 딕셔너리 인코딩된 다중값 |
| `.mv.raw.fwd` | Raw MV | 딕셔너리 미사용 다중값 |

### 4.2 청크 기반 저장 구조 (Raw Forward Index)

소스: `BaseChunkForwardIndexWriter.java:39-59`

```
┌───────────────────────────────────────────────────┐
│ HEADER                                             │
│  version            (4B)                           │
│  numChunks          (4B)                           │
│  numDocsPerChunk    (4B)  기본 1000                 │
│  sizeOfEntry        (4B)  INT=4, LONG=8 등         │
│  totalDocs          (4B)                           │
│  compressionType    (4B)  SNAPPY=1, ZSTD=2 등      │
│  dataHeaderStart    (4B)                           │
│                                                    │
│  ── 청크 오프셋 테이블 ──                           │
│  chunk0 offset      (v2: 4B, v3+: 8B)             │
│  chunk1 offset                                     │
│  chunk2 offset                                     │
│  ...                                               │
├───────────────────────────────────────────────────┤
│ DATA                                               │
│  [Chunk 0] 압축된 바이트 (doc 0 ~ 999)              │
│  [Chunk 1] 압축된 바이트 (doc 1000 ~ 1999)          │
│  [Chunk 2] ...                                     │
└───────────────────────────────────────────────────┘
```

### 4.3 청크 내부 데이터 (INT 컬럼, numDocsPerChunk=4 예시)

```
원본: docId=0 → 1500, docId=1 → 3200, docId=2 → 800, docId=3 → 4100

청크 버퍼 (16 bytes, Big-Endian):
  offset 0x00: 00 00 05 DC  → 1500
  offset 0x04: 00 00 0C 80  → 3200
  offset 0x08: 00 00 03 20  → 800
  offset 0x0C: 00 00 10 04  → 4100
         ↓
  SNAPPY 압축 → 디스크 기록
  오프셋 테이블에 이 청크의 시작 위치 기록
```

### 4.4 쓰기 흐름

소스: `FixedByteChunkForwardIndexWriter.java:56-91`

```java
// 값을 청크 버퍼에 추가
public void putInt(int value) {
    _chunkBuffer.putInt(value);
    _chunkDataOffset += Integer.BYTES;
    flushChunkIfNeeded();  // 꽉 차면 압축 + 디스크 기록
}

private void flushChunkIfNeeded() {
    if (_chunkDataOffset == _chunkSize) {
        writeChunk();  // → compress → write to file → 오프셋 기록
    }
}
```

소스: `BaseChunkForwardIndexWriter.java:175-198`

```java
protected void writeChunk() {
    _chunkBuffer.flip();
    sizeToWrite = _chunkCompressor.compress(_chunkBuffer, _compressedBuffer);
    _dataFile.write(_compressedBuffer, _dataOffset);
    _header.putLong(_dataOffset);     // 오프셋 테이블에 위치 기록
    _dataOffset += sizeToWrite;
    _chunkBuffer.clear();             // 버퍼 재사용
}
```

### 4.5 읽기 흐름

소스: `FixedByteChunkSVForwardIndexReader.java:53-61`

```java
public int getInt(int docId, ChunkReaderContext context) {
    if (_isCompressed) {
        int chunkRowId = docId % _numDocsPerChunk;     // 청크 내 위치
        ByteBuffer chunkBuffer = getChunkBuffer(docId, context); // 해압축
        return chunkBuffer.getInt(chunkRowId * Integer.BYTES);
    } else {
        return _rawData.getInt(docId * Integer.BYTES);  // 직접 접근
    }
}
```

소스: `BaseChunkForwardIndexReader.java:123-134`

```java
// 청크 로딩 (캐시 확인 → 해압축)
protected ByteBuffer getChunkBuffer(int docId, ChunkReaderContext context) {
    int chunkId = docId / _numDocsPerChunk;
    if (context.getChunkId() == chunkId) {
        return context.getChunkBuffer();     // 캐시 히트
    }
    return decompressChunk(chunkId, context); // 캐시 미스 → 해압축
}
```

### 4.6 docId → 값 조회 계산 과정

```
docId=2500, numDocsPerChunk=1000, INT 컬럼

① chunkId     = 2500 / 1000 = 2         → Chunk 2
② chunkRowId  = 2500 % 1000 = 500       → 청크 내 500번째
③ 오프셋 테이블에서 chunk2 위치 조회       → fileOffset
④ chunk3 offset - chunk2 offset          → 압축된 크기
⑤ 해압축 → 4000 bytes (1000 × 4B)
⑥ position 500 × 4 = 2000 에서 4B 읽기   → 결과값
```

### 4.7 V4 최적화: 2의 거듭제곱 청크 크기

소스: `FixedByteChunkForwardIndexWriter.java:93-99`

```java
// numDocsPerChunk을 2의 거듭제곱으로 올림
// docId / 1024 → docId >> 10  (비트시프트)
// docId % 1024 → docId & 0x3FF (비트마스크)
private static int normalizeDocsPerChunk(int version, int numDocsPerChunk) {
    if (version >= 4 && (numDocsPerChunk & (numDocsPerChunk - 1)) != 0) {
        return 1 << (32 - Integer.numberOfLeadingZeros(numDocsPerChunk - 1));
    }
    return numDocsPerChunk;
}
```

### 4.8 배치 읽기 최적화

소스: `BaseChunkForwardIndexReader.java:286-325`

```java
// 연속 docId + 비압축 + 고정폭 → NIO 벌크 복사
if (isFixedWidth && !_isCompressed && isContiguousRange(docIds, length)) {
    int minOffset = docIds[0] * Integer.BYTES;
    IntBuffer buffer = _rawData.toDirectByteBuffer(minOffset, length * Integer.BYTES)
                               .asIntBuffer();
    buffer.get(values, 0, length);  // 한 번의 메모리 복사
}
```

---

## 5. 압축 코덱

소스: `ChunkCompressorFactory`, `ForwardIndexConfig`

| 코덱 | 특성 | 적합 케이스 |
|------|------|-----------|
| `PASS_THROUGH` | 압축 없음, O(1) 직접 접근 | 빈번한 랜덤 읽기 |
| `SNAPPY` | 빠른 압축/해제 | 범용 기본값 |
| `LZ4` | SNAPPY와 유사, 약간 높은 압축률 | 범용 |
| `ZSTANDARD` | 높은 압축률, 느린 속도 | 저장 공간 최적화 |
| `GZIP` | 최고 압축률, 가장 느림 | 아카이브 |
| `DELTA` | 연속 수치의 차이값 저장 | 단조 증가 숫자 |
| `DELTADELTA` | 차이의 차이 저장 | 타임스탬프 |
| `CLP` / `CLPV2` | 문자열 패턴 학습 압축 | 로그 메시지 |

ForwardIndex 기본 설정:
- writerVersion: 4
- targetDocsPerChunk: 1000
- targetMaxChunkSize: 1MB

---

## 6. 세그먼트 파일 구조

소스: `V1Constants.java`, `SegmentLocalFSDirectory.java`

### 6.1 V1 포맷 (컬럼별 개별 파일)

```
segment_0001/
├── metadata.properties
├── creation.meta
├── user_id.dict
├── user_id.sv.unsorted.fwd
├── user_id.bitmap.inv
├── amount.sv.raw.fwd
├── amount.bitmap.range
├── city.dict
├── city.sv.sorted.fwd
├── city.bitmap.inv
├── city.bloom
└── ...
```

파일 수 = 컬럼 수 × 컬럼당 인덱스 수 (1~8개)

### 6.2 V3 포맷 (단일 파일 통합)

```
segment_0001/
├── metadata.properties
├── creation.meta
├── index_map               ← 각 인덱스의 offset/size
└── columns.psf             ← 모든 인덱스 바이너리 통합

index_map 내용:
  user_id.dict   → offset=0,      size=1024
  user_id.fwd    → offset=1024,   size=3976
  user_id.inv    → offset=5000,   size=2048
  amount.fwd     → offset=7048,   size=8192
  ...

columns.psf 내부:
  ┌──────────────────────────────────────┐
  │ [user_id.dict 바이너리]               │
  │ [user_id.fwd 바이너리]                │
  │ [user_id.inv 바이너리]                │
  │ [amount.fwd 바이너리]                 │
  │ ...                                   │
  └──────────────────────────────────────┘
```

| | V1 | V3 |
|--|----|----|
| 파일 수 | 컬럼 × 인덱스 종류 | 세그먼트당 3~4개 |
| file descriptor | 많음 | 적음 |
| 접근 방식 | OS 파일별 캐시 | mmap + offset |

### 6.3 파일 확장자 전체 목록

```
.dict                 딕셔너리
.sv.unsorted.fwd     단일값 포워드 (비정렬)
.sv.sorted.fwd       단일값 포워드 (정렬)
.sv.raw.fwd          단일값 Raw 포워드
.mv.fwd              다중값 포워드
.mv.raw.fwd          다중값 Raw 포워드
.bitmap.inv          역인덱스 (비트맵)
.bitmap.range        범위 인덱스
.bloom               블룸 필터
.bitmap.nullvalue    NULL 값 벡터
.lucene.index        Lucene 텍스트 인덱스
.json.idx            JSON 인덱스
.h3.idx              H3 지리공간 인덱스
.vector.index        벡터 인덱스
```

---

## 7. 컬럼 메타데이터

소스: `V1Constants.java:106-140`

세그먼트의 `metadata.properties`에 컬럼별로 기록되는 정보:

```
column.{name}.cardinality        유니크 값 수
column.{name}.totalDocs          총 문서 수
column.{name}.dataType           INT, LONG, FLOAT, DOUBLE, STRING, BYTES
column.{name}.bitsPerElement     압축 인덱스 비트 수
column.{name}.lengthOfEachEntry  딕셔너리 엔트리 크기
column.{name}.columnType         DIMENSION, METRIC, TIME
column.{name}.isSorted           정렬 여부
column.{name}.hasDictionary      딕셔너리 사용 여부
column.{name}.isSingleValues     단일값/다중값
column.{name}.maxNumberOfMultiValues  MV 최대 값 수
column.{name}.totalNumberOfEntries   총 엔트리 수
column.{name}.minValue           최솟값
column.{name}.maxValue           최댓값
```

---

## 8. 읽기 모드

소스: `SegmentLocalFSDirectory.java`

| 모드 | 방식 | 특성 |
|------|------|------|
| `MMAP` | Memory-mapped file | 기본값. OS 페이지 캐시 활용, off-heap |
| `HEAP` | JVM 힙 로드 | GC 영향 있음, 작은 세그먼트용 |

`PinotDataBuffer`가 두 모드를 추상화하여 동일한 `getInt(offset)` API 제공.

---

## 9. 주요 소스 파일 맵

```
pinot-spi/
  └── .../ingestion/segment/writer/
      └── SegmentWriter.java                  세그먼트 쓰기 진입점 인터페이스

pinot-segment-spi/
  └── .../segment/spi/
      ├── V1Constants.java                    파일 확장자, 메타데이터 키 상수
      ├── creator/
      │   └── SegmentCreator.java             세그먼트 생성 인터페이스
      ├── index/
      │   ├── ForwardIndexConfig.java         포워드 인덱스 설정 (압축, 버전)
      │   ├── creator/
      │   │   └── ForwardIndexCreator.java    포워드 인덱스 Writer 인터페이스
      │   └── reader/
      │       ├── ForwardIndexReader.java     포워드 인덱스 Reader 인터페이스
      │       └── Dictionary.java             딕셔너리 Reader 인터페이스
      └── memory/
          └── PinotDataBuffer.java            Off-heap 메모리 추상화

pinot-segment-local/
  └── .../segment/local/
      ├── segment/creator/impl/
      │   ├── SegmentColumnarIndexCreator.java  컬럼별 인덱스 생성 구현
      │   ├── ColumnIndexCreators.java          컬럼 인덱스 묶음 관리
      │   ├── SegmentDictionaryCreator.java     딕셔너리 빌드
      │   └── fwd/
      │       ├── SingleValueSortedForwardIndexCreator.java    정렬 SV
      │       ├── SingleValueFixedByteRawIndexCreator.java     고정폭 Raw SV
      │       └── ForwardIndexUtils.java                       청크 크기 계산
      ├── segment/index/
      │   ├── forward/
      │   │   └── ForwardIndexType.java         인덱스 타입 결정 로직
      │   └── readers/
      │       ├── BaseImmutableDictionary.java   딕셔너리 읽기 기반 클래스
      │       └── forward/
      │           ├── BaseChunkForwardIndexReader.java       청크 Reader 기반
      │           └── FixedByteChunkSVForwardIndexReader.java 고정폭 SV Reader
      ├── io/writer/impl/
      │   ├── BaseChunkForwardIndexWriter.java   청크 Writer 기반 (파일 레이아웃)
      │   └── FixedByteChunkForwardIndexWriter.java  고정폭 청크 Writer
      └── segment/store/
          └── SegmentLocalFSDirectory.java       세그먼트 디렉토리 관리
```

---

## 10. 참조 라이브러리 / 의존성

| 라이브러리 | 용도 | 사용 위치 |
|-----------|------|----------|
| `PinotDataBuffer` (pinot-segment-spi) | Off-heap mmap 메모리 매핑 | 모든 Reader/Writer |
| `ChunkCompressorFactory` (pinot-segment-local) | 압축/해압축 인스턴스 생성 | BaseChunkForwardIndex* |
| `fastutil` (`Int2IntOpenHashMap` 등) | 타입별 고성능 HashMap | SegmentDictionaryCreator |
| `Guava` (`Preconditions`) | 입력 유효성 검증 | BaseChunkForwardIndexWriter |
| `Java NIO` (`ByteBuffer`, `FileChannel`) | Direct 메모리 할당, 파일 I/O | 청크 Writer/Reader |
| `VarLengthValueWriter/Reader` (내부) | 가변 길이 딕셔너리 저장 | StringDictionary, BytesDictionary |

---

## 11. 핵심 상수 / 기본값

```java
// 포워드 인덱스
DEFAULT_WRITER_VERSION = 4
DEFAULT_TARGET_DOCS_PER_CHUNK = 1000
DEFAULT_TARGET_MAX_CHUNK_SIZE = 1MB (1_048_576)
MIN_CHUNK_SIZE = 4KB (4_096)

// 딕셔너리
NULL_VALUE_INDEX = -1          // Dictionary.indexOf() 미발견 시

// 청크 Writer 버전
V2: 청크 오프셋 int (4B), 최대 2GB
V3: 청크 오프셋 long (8B), 2GB 이상 지원
V4: V3 + 2의 거듭제곱 청크 크기 (고정폭 전용)
V5: V4 확장
```