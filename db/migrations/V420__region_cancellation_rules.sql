-- S-153: late-cancellation defaults and limits as region data (a market's NULL = its province's, a province's NULL =
-- the platform configuration, northline.region.cancellation.*). The default applies to a business that never saved its
-- Availability › Booking rules; the limits cap what one chose. Never a place in code: a province or market that caps
-- cancellation fees (consumer protection) sets the max here. Additive.
ALTER TABLE region.regions
  ADD COLUMN cancel_window_hours     integer CHECK (cancel_window_hours BETWEEN 0 AND 720),
  ADD COLUMN cancel_fee_bps          integer CHECK (cancel_fee_bps BETWEEN 0 AND 10000),
  ADD COLUMN cancel_max_window_hours integer CHECK (cancel_max_window_hours BETWEEN 0 AND 720),
  ADD COLUMN cancel_max_fee_bps      integer CHECK (cancel_max_fee_bps BETWEEN 0 AND 10000);
