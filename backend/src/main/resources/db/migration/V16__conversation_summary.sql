-- Conversation summaries (12h): older messages condensed so long conversations fit the context window.
alter table agent_session add column summary text;
