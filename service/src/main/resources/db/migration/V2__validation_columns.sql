ALTER TABLE generated_test
    ADD COLUMN validation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';

ALTER TABLE generated_test
    ADD COLUMN validation_detail VARCHAR(4096);
