-- Supervisor + specialist agents (12f): a Settings toggle routes each chat message to a specialist (network, accounts,
-- hardware, software or general), and the run remembers which one handled it.
alter table agent_settings add column specialists boolean not null default false;

alter table agent_run add column specialist varchar(40);
create index idx_agent_run_specialist on agent_run (specialist);
