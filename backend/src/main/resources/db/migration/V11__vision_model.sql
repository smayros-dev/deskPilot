-- Vision model that reads screenshots attached to chat messages (12e); null = screenshots are off.
alter table agent_settings add column vision_model varchar(100);
