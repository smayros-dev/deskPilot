-- Reflection pass (12g): check answers and ticket requests against the retrieved articles.
alter table agent_settings add column self_check boolean not null default true;
