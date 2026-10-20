-- Keyword side of the hybrid search: weighted full-text index of the articles (title and tags count more than content).
alter table knowledge_article add column search_vector tsvector generated always as (
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(tags, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(content, '')), 'B')
) stored;
create index idx_knowledge_article_search on knowledge_article using gin (search_vector);

-- Settings toggle, so evaluations can compare hybrid and vector-only retrieval.
alter table agent_settings add column hybrid_search boolean not null default true;
