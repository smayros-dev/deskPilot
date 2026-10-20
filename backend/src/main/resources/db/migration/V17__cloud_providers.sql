-- Cloud model providers (12s): Mistral, Gemini. API keys entered in the UI are stored encrypted (AES-GCM);
-- an environment variable (MISTRAL_API_KEY, GEMINI_API_KEY) takes precedence over the stored key.
create table cloud_provider (
    id                varchar(30)  primary key,
    enabled           boolean      not null,
    api_key_encrypted text,
    key_hint          varchar(8),
    updated_by        varchar(64),
    updated_at        timestamp(6) with time zone not null
);

-- Which provider serves the chat model and the vision model ('ollama' = local).
alter table agent_settings add column chat_provider varchar(30) not null default 'ollama';
alter table agent_settings add column vision_provider varchar(30);
update agent_settings set vision_provider = 'ollama' where vision_model is not null;
