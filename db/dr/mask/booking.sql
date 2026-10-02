-- booking: the job address, what the customer wrote, gate/lockbox codes (access_notes, sealed with prod's key) and
-- where things happened. Quotes and quote lines are the merchant's commercial text and stay (they are immutable).
delete from booking.access_notes;
update booking.bookings
   set address_line = case when address_line is null then null else 'Masked address' end,
       details = pg_temp.nl_scrub(details)
 where address_line is not null or details is not null;
update booking.booking_events set note = null, geom = pg_temp.nl_coarse(geom) where note is not null or geom is not null;
update booking.media set file_name = 'file-' || lower(right(id, 6));
update booking.quote_requests set details = pg_temp.nl_scrub(details) where details is not null;
