-- A payee must be someone who can be identified.
--
-- Added as a second migration rather than by editing V1: that migration has already been applied,
-- and validate-on-migrate compares checksums, so changing it would stop every deployed instance
-- from starting.
--
-- The rule this enforces is a product rule before it is a technical one. Confirming a payment
-- means seeing who is about to be paid, and a user with no profile has no name to show - so an
-- account that cannot be found in the directory cannot be saved as a payee either. Putting it in
-- the schema means a beneficiary row can never point at somebody unnameable, whatever the
-- application does.
--
-- Deliberately asymmetric: only the beneficiary is constrained. The owner is whoever is
-- authenticated, and requiring them to have filled in a profile before they could save anybody
-- would gate a private action on a public one.
ALTER TABLE beneficiaries
    ADD CONSTRAINT fk_beneficiaries_profile
    FOREIGN KEY (beneficiary_user_id) REFERENCES user_profiles (user_id)
    -- If a profile is ever removed, the person disappears from every payee list rather than
    -- lingering as an entry nobody can render or pay.
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT fk_beneficiaries_profile ON beneficiaries IS
    'A payee must have a profile: a payment confirmation has to be able to show who is being paid.';
