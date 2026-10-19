-- Runtime agent configuration editable from the UI (single row, id = 1).
create table agent_settings (
    id                       integer primary key,
    chat_model               varchar(100)     not null,
    temperature              double precision not null,
    top_p                    double precision not null,
    context_window           integer          not null,
    max_output_tokens        integer          not null,
    thinking                 boolean          not null,
    max_iterations           integer          not null,
    rag_top_k                integer          not null,
    rag_similarity_threshold double precision not null,
    disabled_tools           varchar(1000)    not null,
    approval_tools           varchar(1000)    not null,
    system_prompt            text,
    updated_at               timestamp(6) with time zone not null,
    version                  bigint           not null
);

-- Which model answered each run, to compare models on the dashboard.
alter table agent_run add column model varchar(100);
