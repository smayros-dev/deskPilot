-- Conversations. busy_since marks the request currently running in the session (a lock shared by all instances).
create table agent_session (
    id         varchar(36) primary key,
    user_id    varchar(64) not null,
    created_at timestamp(6) with time zone not null,
    busy_since timestamp(6) with time zone
);

-- Working state of a run paused for approval (messages so far, held-back tool calls, settings snapshot) as JSON,
-- so the run can be approved after a restart. Deleted when the run resumes.
create table pending_turn (
    run_id     varchar(36) primary key,
    session_id varchar(36) not null unique,
    state      text        not null,
    created_at timestamp(6) with time zone not null
);

-- Short-term memory, managed by Spring AI's JdbcChatMemoryRepository (schema-postgresql.sql of spring-ai 2.0.1).
create table spring_ai_chat_memory (
    conversation_id varchar(36) not null,
    content         text        not null,
    type            varchar(10) not null check (type in ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     timestamp   not null,
    sequence_id     bigint      not null
);
create index spring_ai_chat_memory_conversation_id_timestamp_idx on spring_ai_chat_memory (conversation_id, "timestamp");
create index spring_ai_chat_memory_conversation_id_sequence_id_idx on spring_ai_chat_memory (conversation_id, sequence_id);
