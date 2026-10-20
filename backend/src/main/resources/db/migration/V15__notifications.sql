-- E-mail address of each user, taken from the access token's "email" claim when they start a conversation (12j).
create table user_contact (
    user_id    varchar(64)  primary key,
    email      varchar(254) not null,
    updated_at timestamp(6) with time zone not null
);

-- Runs waiting for approval longer than the SLA whose escalation e-mail was sent, so it is sent once.
create table approval_escalation (
    run_id      varchar(36) primary key,
    notified_at timestamp(6) with time zone not null
);
