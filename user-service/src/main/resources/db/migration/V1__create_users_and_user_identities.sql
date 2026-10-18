CREATE TABLE users (
                       id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       email            VARCHAR(320) NOT NULL,
                       display_name     VARCHAR(100) NOT NULL,
                       avatar_url       VARCHAR(2048),
                       default_currency CHAR(3)      NOT NULL DEFAULT 'INR',
                       locale           VARCHAR(10),
                       status           VARCHAR(20)  NOT NULL,
                       created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       deleted_at       TIMESTAMPTZ,

                       CONSTRAINT uq_users_email UNIQUE (email),
                       CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
                       CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'INVITED', 'DELETED'))
);

CREATE TABLE user_identities (
                                 id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                 user_id          UUID         NOT NULL,
                                 provider         VARCHAR(30)  NOT NULL,
                                 provider_subject VARCHAR(255) NOT NULL,
                                 created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

                                 CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id) REFERENCES users (id),
                                 CONSTRAINT uq_user_identities_provider_subject UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_user_identities_user_id ON user_identities (user_id);