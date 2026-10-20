-- Conversation history (12r): a title (the first question, renameable) and the time of the last message.
alter table agent_session add column title varchar(200);
alter table agent_session add column last_activity_at timestamp(6) with time zone;

update agent_session s set
    title = (select left(r.user_input, 80) from agent_run r where r.session_id = s.id order by r.created_at limit 1),
    last_activity_at = coalesce((select max(r.created_at) from agent_run r where r.session_id = s.id), s.created_at);

create index idx_agent_session_user_activity on agent_session (user_id, last_activity_at desc);
