-- Why an experiment stopped when DeskPilot stopped it itself (provider disabled, repeated provider errors); null when
-- an admin stopped it.
alter table experiment add column stop_reason varchar(300);
