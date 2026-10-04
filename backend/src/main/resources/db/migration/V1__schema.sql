-- =====================================================================
-- WODZ | V1 - Estrutura base
-- Banco: PostgreSQL 16
-- Enums são VARCHAR + CHECK para facilitar o mapeamento JPA
-- (@Enumerated(STRING)) e a evolução dos valores sem ALTER TYPE.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- unaccent() é STABLE; este wrapper IMMUTABLE permite usá-lo em índices.
CREATE OR REPLACE FUNCTION f_unaccent(text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS
$$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- ---------------------------------------------------------------------
-- Usuários: organizadores (donos de campeonato) e atletas
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
    id             BIGSERIAL    PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    email          VARCHAR(160) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    phone          VARCHAR(20),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ORGANIZER', 'ATHLETE'))
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));

-- ---------------------------------------------------------------------
-- Campeonato
-- ---------------------------------------------------------------------
CREATE TABLE championship (
    id                      BIGSERIAL    PRIMARY KEY,
    owner_id                BIGINT       NOT NULL REFERENCES app_user (id),
    name                    VARCHAR(150) NOT NULL,
    slug                    VARCHAR(160) NOT NULL,
    description             TEXT,
    venue_name              VARCHAR(150) NOT NULL,
    address                 VARCHAR(255),
    city                    VARCHAR(100) NOT NULL,
    state                   CHAR(2)      NOT NULL,
    start_date              DATE         NOT NULL,
    end_date                DATE         NOT NULL,
    registration_opens_at   TIMESTAMPTZ,
    registration_closes_at  TIMESTAMPTZ,
    registration_open       BOOLEAN      NOT NULL DEFAULT FALSE,
    scoring_system          VARCHAR(20)  NOT NULL DEFAULT 'POINTS_TABLE',
    points_first_place      INT          NOT NULL DEFAULT 100,
    points_step             INT          NOT NULL DEFAULT 5,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    banner_url              VARCHAR(500),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_championship_slug UNIQUE (slug),
    CONSTRAINT ck_championship_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_championship_registration_window CHECK (
        registration_opens_at IS NULL OR registration_closes_at IS NULL
        OR registration_closes_at > registration_opens_at),
    CONSTRAINT ck_championship_scoring CHECK (scoring_system IN ('POINTS_TABLE', 'RANK_SUM')),
    CONSTRAINT ck_championship_points CHECK (points_first_place > 0 AND points_step >= 0),
    CONSTRAINT ck_championship_status CHECK (
        status IN ('DRAFT', 'PUBLISHED', 'IN_PROGRESS', 'FINISHED', 'CANCELLED'))
);
CREATE INDEX ix_championship_owner ON championship (owner_id);
CREATE INDEX ix_championship_start ON championship (start_date);
-- Busca por nome/cidade/local sem acento e com tolerância a erro de digitação
CREATE INDEX ix_championship_search ON championship
    USING gin (f_unaccent(lower(name || ' ' || city || ' ' || venue_name)) gin_trgm_ops);
CREATE INDEX ix_championship_name_trgm ON championship
    USING gin (f_unaccent(lower(name)) gin_trgm_ops);

-- ---------------------------------------------------------------------
-- Categoria (Iniciante, Scaled, RX, Master...)
-- ---------------------------------------------------------------------
CREATE TABLE category (
    id               BIGSERIAL     PRIMARY KEY,
    championship_id  BIGINT        NOT NULL REFERENCES championship (id) ON DELETE CASCADE,
    name             VARCHAR(80)   NOT NULL,
    description      TEXT,
    team_size        INT           NOT NULL DEFAULT 2,
    max_teams        INT,
    price            NUMERIC(10,2) NOT NULL DEFAULT 0,
    display_order    INT           NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_name UNIQUE (championship_id, name),
    CONSTRAINT ck_category_team_size CHECK (team_size BETWEEN 1 AND 10),
    CONSTRAINT ck_category_max_teams CHECK (max_teams IS NULL OR max_teams > 0),
    CONSTRAINT ck_category_price CHECK (price >= 0)
);

-- ---------------------------------------------------------------------
-- Prova (workout). Cada prova pertence a uma categoria:
-- "Prova 1 - Iniciante" e "Prova 1 - Scaled" são registros distintos.
-- ---------------------------------------------------------------------
CREATE TABLE workout (
    id                BIGSERIAL    PRIMARY KEY,
    category_id       BIGINT       NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    number            INT          NOT NULL,
    name              VARCHAR(120) NOT NULL,
    description       TEXT,
    type              VARCHAR(20)  NOT NULL,
    time_cap_seconds  INT,
    reps_per_round    INT,
    has_tiebreak      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_workout_number UNIQUE (category_id, number),
    CONSTRAINT ck_workout_number CHECK (number > 0),
    CONSTRAINT ck_workout_type CHECK (type IN ('FOR_TIME', 'AMRAP', 'MAX_LOAD', 'MAX_REPS')),
    CONSTRAINT ck_workout_time_cap CHECK (time_cap_seconds IS NULL OR time_cap_seconds > 0),
    CONSTRAINT ck_workout_reps_per_round CHECK (
        reps_per_round IS NULL OR (reps_per_round > 0 AND type = 'AMRAP')),
    CONSTRAINT ck_workout_amrap_cap CHECK (type <> 'AMRAP' OR time_cap_seconds IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- Equipe e atletas
-- ---------------------------------------------------------------------
CREATE TABLE team (
    id                 BIGSERIAL    PRIMARY KEY,
    category_id        BIGINT       NOT NULL REFERENCES category (id),
    captain_id         BIGINT       NOT NULL REFERENCES app_user (id),
    name               VARCHAR(80)  NOT NULL,
    box_name           VARCHAR(120),
    city               VARCHAR(100),
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING_PAYMENT',
    registration_code  VARCHAR(12)  NOT NULL,
    confirmed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_registration_code UNIQUE (registration_code),
    CONSTRAINT ck_team_status CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED'))
);
CREATE UNIQUE INDEX ux_team_name_per_category ON team (category_id, lower(name))
    WHERE status <> 'CANCELLED';
CREATE INDEX ix_team_captain ON team (captain_id);
CREATE INDEX ix_team_category_status ON team (category_id, status);

CREATE TABLE team_member (
    id          BIGSERIAL    PRIMARY KEY,
    team_id     BIGINT       NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    full_name   VARCHAR(120) NOT NULL,
    email       VARCHAR(160) NOT NULL,
    cpf         VARCHAR(11)  NOT NULL,
    birth_date  DATE         NOT NULL,
    gender      CHAR(1)      NOT NULL,
    shirt_size  VARCHAR(4),
    is_captain  BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_team_member_cpf UNIQUE (team_id, cpf),
    CONSTRAINT ck_team_member_gender CHECK (gender IN ('M', 'F')),
    CONSTRAINT ck_team_member_shirt CHECK (
        shirt_size IS NULL OR shirt_size IN ('PP', 'P', 'M', 'G', 'GG', 'XGG'))
);
CREATE INDEX ix_team_member_cpf ON team_member (cpf);

-- ---------------------------------------------------------------------
-- Pagamento da inscrição (Pix, cartão, boleto)
-- ---------------------------------------------------------------------
CREATE TABLE payment (
    id                    BIGSERIAL     PRIMARY KEY,
    team_id               BIGINT        NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    amount                NUMERIC(10,2) NOT NULL,
    method                VARCHAR(20)   NOT NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    provider              VARCHAR(30)   NOT NULL,
    provider_payment_id   VARCHAR(100),
    status_detail         VARCHAR(160),
    pix_qr_code           TEXT,
    pix_qr_code_base64    TEXT,
    boleto_url            VARCHAR(500),
    boleto_barcode        VARCHAR(100),
    card_last_digits      VARCHAR(4),
    installments          INT,
    expires_at            TIMESTAMPTZ,
    paid_at               TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_method CHECK (method IN ('PIX', 'CREDIT_CARD', 'BOLETO')),
    CONSTRAINT ck_payment_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_payment_installments CHECK (installments IS NULL OR installments BETWEEN 1 AND 12)
);
CREATE INDEX ix_payment_team ON payment (team_id);
CREATE UNIQUE INDEX ux_payment_provider_id ON payment (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;
-- Uma equipe nunca pode ter dois pagamentos aprovados
CREATE UNIQUE INDEX ux_payment_one_approved ON payment (team_id) WHERE status = 'APPROVED';
CREATE INDEX ix_payment_pending_expiration ON payment (expires_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- Baterias (heats) e raias
-- ---------------------------------------------------------------------
CREATE TABLE heat (
    id            BIGSERIAL   PRIMARY KEY,
    workout_id    BIGINT      NOT NULL REFERENCES workout (id) ON DELETE CASCADE,
    number        INT         NOT NULL,
    scheduled_at  TIMESTAMPTZ NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_heat_number UNIQUE (workout_id, number),
    CONSTRAINT ck_heat_number CHECK (number > 0),
    CONSTRAINT ck_heat_status CHECK (status IN ('UPCOMING', 'IN_PROGRESS', 'FINISHED'))
);
CREATE INDEX ix_heat_scheduled_at ON heat (scheduled_at);

CREATE TABLE heat_lane (
    id       BIGSERIAL PRIMARY KEY,
    heat_id  BIGINT    NOT NULL REFERENCES heat (id) ON DELETE CASCADE,
    team_id  BIGINT    NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    lane     INT       NOT NULL,
    CONSTRAINT uq_heat_lane UNIQUE (heat_id, lane),
    CONSTRAINT uq_heat_team UNIQUE (heat_id, team_id),
    CONSTRAINT ck_heat_lane CHECK (lane > 0)
);
CREATE INDEX ix_heat_lane_team ON heat_lane (team_id);

-- ---------------------------------------------------------------------
-- Resultado de cada equipe em cada prova.
-- placement/points são calculados pelo banco (fn_rank_workout).
-- ---------------------------------------------------------------------
CREATE TABLE score (
    id           BIGSERIAL     PRIMARY KEY,
    workout_id   BIGINT        NOT NULL REFERENCES workout (id) ON DELETE CASCADE,
    team_id      BIGINT        NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    status       VARCHAR(10)   NOT NULL DEFAULT 'VALID',
    time_ms      INT,
    reps         INT,
    load_kg      NUMERIC(7,2),
    tiebreak_ms  INT,
    placement    INT,
    points       NUMERIC(8,2),
    notes        VARCHAR(255),
    created_by   BIGINT        REFERENCES app_user (id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_score_team_workout UNIQUE (workout_id, team_id),
    CONSTRAINT ck_score_status CHECK (status IN ('VALID', 'CAPPED', 'DNS', 'DQ')),
    CONSTRAINT ck_score_values CHECK (
        (time_ms IS NULL OR time_ms > 0)
        AND (reps IS NULL OR reps >= 0)
        AND (load_kg IS NULL OR load_kg >= 0)
        AND (tiebreak_ms IS NULL OR tiebreak_ms > 0))
);
CREATE INDEX ix_score_team ON score (team_id);

-- Auditoria de lançamentos (quem alterou o quê e quando)
CREATE TABLE score_audit (
    id          BIGSERIAL   PRIMARY KEY,
    score_id    BIGINT      NOT NULL,
    workout_id  BIGINT      NOT NULL,
    team_id     BIGINT      NOT NULL,
    operation   CHAR(1)     NOT NULL,
    old_data    JSONB,
    new_data    JSONB,
    changed_by  BIGINT,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_score_audit_operation CHECK (operation IN ('I', 'U', 'D'))
);
CREATE INDEX ix_score_audit_workout ON score_audit (workout_id, changed_at DESC);
