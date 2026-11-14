-- Needed when Hibernate schema updates are disabled.
CREATE TABLE IF NOT EXISTS review_criterion_definitions (
 code varchar(80) PRIMARY KEY, title varchar(150) NOT NULL,
 description varchar(1000) NOT NULL, required boolean NOT NULL, active boolean NOT NULL DEFAULT true
);
CREATE TABLE IF NOT EXISTS custom_criterion_answers (
 id varchar(255) PRIMARY KEY, order_id varchar(255) NOT NULL,
 criterion_code varchar(80) NOT NULL, result varchar(255), note varchar(1000),
 actor_name varchar(255), decided_at timestamptz,
 UNIQUE(order_id, criterion_code)
);
