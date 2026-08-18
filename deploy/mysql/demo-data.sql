INSERT INTO tenant (code, name)
VALUES ('acme', 'Acme Operations'), ('beta', 'Beta Operations')
ON DUPLICATE KEY UPDATE name = VALUES(name);

SET @acme_id = (SELECT id FROM tenant WHERE code = 'acme');
SET @beta_id = (SELECT id FROM tenant WHERE code = 'beta');
SET @demo_hash = '$2a$10$OEDSginzDrksQ/a.5uhk9OxSfdiQHr9DjlAnu1HU5pfb/ft14GXJ.';

INSERT INTO user_account (tenant_id, username, password_hash, display_name, role)
VALUES
    (@acme_id, 'admin', @demo_hash, 'Acme Admin', 'ADMIN'),
    (@acme_id, 'operator', @demo_hash, 'Acme Operator', 'OPERATOR'),
    (@beta_id, 'operator', @demo_hash, 'Beta Operator', 'OPERATOR')
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    display_name = VALUES(display_name),
    role = VALUES(role),
    status = 'ACTIVE';
