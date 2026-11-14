-- SYNC-1-M1 첫 배포 스키마. 컬럼은 docs/domain.md 2장을 따른다.
-- 시각 컬럼은 모두 timestamptz(시간대 포함)로 둔다. 앱은 주입한 Clock의 Instant로 기록한다.

-- 2.1 혜택 서비스
create table benefit (
    service_id           varchar(64)   primary key,
    name                 varchar(500)  not null,
    purpose_summary      text,
    purpose              text,
    target               text,
    selection_criteria   text,
    support_content      text,
    apply_method         text,
    apply_deadline       text,
    apply_deadline_type  varchar(50),
    support_type         varchar,
    service_field        varchar,
    user_type            varchar,
    agency_type          varchar,
    agency_name          varchar,
    agency_code          varchar,
    department           varchar,
    reception_org        text,
    reception_org_name   text,
    contact_phone        text,
    contact              text,
    detail_url           varchar(2000),
    online_apply_url     varchar(2000),
    view_count           bigint,
    required_docs        text,
    self_verify_docs     text,
    official_verify_docs text,
    laws                 text,
    local_laws           text,
    admin_rules          text,
    source_registered_at timestamptz,
    list_modified_at     timestamptz,
    detail_modified_at   timestamptz,
    status               varchar(20)   not null default 'ACTIVE',
    missing_count        int           not null default 0,
    first_seen_at        timestamptz,
    last_seen_at         timestamptz,
    removed_at           timestamptz,
    content_hash         char(64),
    hash_version         int,
    index_status         varchar(30),
    indexed_hash         char(64),
    detail_status        varchar(20),
    created_at           timestamptz,
    updated_at           timestamptz,
    version              bigint        not null default 0
);

-- 2.3 코드 사전
create table condition_code (
    code          varchar(20)  primary key,
    group_name    varchar(50)  not null,
    label         varchar(200) not null,
    value_kind    varchar(10)  not null,
    known         boolean      not null,
    first_seen_at timestamptz
);

-- 2.2 자격 조건 (세로형: 코드 하나당 한 행)
create table benefit_condition (
    id         bigint generated always as identity primary key,
    service_id varchar(64) not null references benefit (service_id),
    code       varchar(20) not null references condition_code (code),
    raw_value  varchar,
    num_value  int,
    row_index  int
);

-- 2.4 수집 컨텍스트
create table sync_run (
    id                 bigint generated always as identity primary key,
    type               varchar(20) not null,
    trigger            varchar(20),
    status             varchar(20) not null,
    started_at         timestamptz not null,
    finished_at        timestamptz,
    heartbeat_at       timestamptz,
    cursor             varchar,
    source_total_count int,
    fetched            int not null default 0,
    created            int not null default 0,
    updated            int not null default 0,
    unchanged          int not null default 0,
    removed            int not null default 0,
    failed             int not null default 0,
    error_code         varchar(50),
    error_summary      text
);

create table sync_failure (
    id            bigint generated always as identity primary key,
    run_id        bigint      not null references sync_run (id),
    stage         varchar(20) not null,
    service_id    varchar(64),
    page          int,
    http_status   int,
    error_code    varchar(50),
    message       text,
    attempt_count int,
    resolved_at   timestamptz,
    given_up      boolean     not null default false
);

create index sync_failure_run_id_idx on sync_failure (run_id);

create table raw_payload (
    id           bigint generated always as identity primary key,
    run_id       bigint       not null references sync_run (id),
    endpoint     varchar(100) not null,
    page         int,
    service_id   varchar(64),
    payload      jsonb        not null,
    payload_hash char(64)     not null,
    fetched_at   timestamptz  not null
);

create table sync_lock (
    name         varchar(100) primary key,
    owner        varchar(200),
    locked_until timestamptz
);
