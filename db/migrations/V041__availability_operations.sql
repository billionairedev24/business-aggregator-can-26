-- Operations workstream (range V040–V049): availability — weekly hours, booking rules, time off, holidays,
-- service area, calendar sync. Additive only. See docs/DECISIONS.md "Operations".

-- Weekly hours: one row per member, weekday (ISO 1 = Mon … 7 = Sun) and effective date. ranges = [["07:00","18:00"],…]
ALTER TABLE availability.availability_rules
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_weekday CHECK (weekday BETWEEN 1 AND 7);
CREATE UNIQUE INDEX ux_availability_rules ON availability.availability_rules(merchant_id, member_user_id, weekday, effective_from);

-- Booking rules: one row per merchant. Options the design offers that the baseline cannot express:
-- "Same day by 9 am" notice, "50% of job" late fee, the same-day emergency premium and the holiday premium.
ALTER TABLE availability.booking_rules
  ALTER COLUMN merchant_id SET NOT NULL,
  ADD PRIMARY KEY (merchant_id),
  ADD COLUMN same_day_cutoff_min integer,
  ADD COLUMN late_cancel_fee_bps integer,
  ADD COLUMN emergency_premium_cents bigint,
  ADD COLUMN emergency_premium_bps integer,
  ADD COLUMN holiday_premium_cents bigint NOT NULL DEFAULT 5000,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT chk_interval CHECK (interval_min IN (15, 30, 60)),
  ADD CONSTRAINT chk_buffer CHECK (buffer_min BETWEEN 0 AND 240),
  ADD CONSTRAINT chk_horizon CHECK (horizon_days BETWEEN 1 AND 366),
  ADD CONSTRAINT chk_max_jobs CHECK (max_jobs_per_day BETWEEN 1 AND 99),
  ADD CONSTRAINT chk_late_fee CHECK (late_cancel_fee_cents IS NULL OR late_cancel_fee_bps IS NULL),
  ADD CONSTRAINT chk_premium CHECK (emergency_premium_cents IS NULL OR emergency_premium_bps IS NULL);

-- Time off: audit + sane ranges.
ALTER TABLE availability.time_off
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by text,
  ADD CONSTRAINT chk_time_off_range CHECK (ends_on >= starts_on),
  ADD CONSTRAINT chk_time_off_special CHECK (kind = 'closed' OR special_ranges IS NOT NULL);
CREATE INDEX ix_time_off_merchant_start ON availability.time_off(merchant_id, starts_on);

-- Statutory holidays are closed by default; a row here opens one (at the holiday premium).
CREATE TABLE availability.holiday_openings (
  merchant_id text NOT NULL,
  holiday_date date NOT NULL,
  PRIMARY KEY (merchant_id, holiday_date)
);

-- Service area (Booking rules › Service area). Zone names until region.zones is populated for Calgary.
CREATE TABLE availability.service_areas (
  merchant_id text NOT NULL,
  zone text NOT NULL,
  PRIMARY KEY (merchant_id, zone)
);

-- Calendar sync: link per business + member + provider (Google two-way, Outlook two-way, iCal read-only feed).
ALTER TABLE availability.calendar_links
  ADD COLUMN merchant_id text,
  ADD COLUMN account_label text,
  ADD COLUMN mode text CHECK (mode IN ('two_way', 'read_only')),
  ADD COLUMN connected_at timestamptz,
  ADD CONSTRAINT chk_calendar_provider CHECK (provider IN ('google', 'outlook', 'ical'));
CREATE UNIQUE INDEX ux_calendar_links ON availability.calendar_links(merchant_id, member_user_id, provider);
