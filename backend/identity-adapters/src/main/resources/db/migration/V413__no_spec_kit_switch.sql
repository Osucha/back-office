-- ADR 0063: Back Office no longer wraps Spec Kit; the platform switch "Spec Kit allowed" (V409, ADR 0036) goes away.
ALTER TABLE identity_platform_settings DROP COLUMN IF EXISTS spec_kit_allowed;
