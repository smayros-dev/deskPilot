-- Prompt & model experiments (12m): named versions of the system prompt, and A/B experiments that split chat
-- traffic between two variants (provider + model + optional prompt version). At most one experiment runs at a time.
create table prompt_version (
    id         bigserial    primary key,
    name       varchar(100) not null unique,
    content    text         not null,
    created_by varchar(64)  not null,
    created_at timestamp(6) with time zone not null
);

create table experiment (
    id               varchar(36)  primary key,
    name             varchar(100) not null,
    status           varchar(20)  not null,
    a_provider       varchar(30)  not null,
    a_model          varchar(100) not null,
    a_prompt_id      bigint references prompt_version (id),
    b_provider       varchar(30)  not null,
    b_model          varchar(100) not null,
    b_prompt_id      bigint references prompt_version (id),
    b_percent        int          not null,
    started_by       varchar(64)  not null,
    started_at       timestamp(6) with time zone not null,
    stopped_by       varchar(64),
    stopped_at       timestamp(6) with time zone
);

-- Only one experiment can be running.
create unique index idx_experiment_running on experiment (status) where status = 'RUNNING';

-- The experiment and variant ('A' or 'B') a chat run was assigned to.
alter table agent_run add column experiment_id varchar(36);
alter table agent_run add column variant varchar(1);
create index idx_agent_run_experiment on agent_run (experiment_id);
