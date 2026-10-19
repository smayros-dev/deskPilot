-- Ask the model to search the knowledge base when it answers without using any tool (see AgentOrchestrator).
alter table agent_settings add column grounding_check boolean not null default true;
