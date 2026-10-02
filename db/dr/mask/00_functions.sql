-- S-114 masking helpers. pg_temp: they vanish with the session and never reach a backup or staging.

-- Scrubs personal values out of a JSON document, at any depth: string values under personal-looking keys become
-- "[masked]", coordinates are rounded to 2 decimals (about 1 km). Numbers, booleans, ids and structure stay, so the
-- apps still parse the document.
create function pg_temp.nl_scrub(doc jsonb) returns jsonb language sql immutable as $$
  select case jsonb_typeof(doc)
    when 'object' then coalesce((
      select jsonb_object_agg(key,
        case
          when key ~* '^(lat|lng|lon|latitude|longitude)$' and jsonb_typeof(value) = 'number'
            then to_jsonb(round((value::text)::numeric, 2))
          when jsonb_typeof(value) = 'string' and (
               key ~* '(name|email|phone|mobile|address|street|line1|line2|postal|zip|unit|note|access|instruction|description|vehicle|plate|contact|dob|birth|office|message|comment|statement|gate|buzzer|recipient)'
            or key ~* '^(text|body|reason|ip|city_line)$')
            then to_jsonb('[masked]'::text)
          else pg_temp.nl_scrub(value)
        end)
      from jsonb_each(doc)), '{}'::jsonb)
    when 'array' then coalesce((select jsonb_agg(pg_temp.nl_scrub(e) order by n)
                                from jsonb_array_elements(doc) with ordinality as a(e, n)), '[]'::jsonb)
    else doc
  end
$$;

-- Masked e-mail address, unique per row id: never deliverable (.invalid, RFC 2606).
create function pg_temp.nl_email(prefix text, id text) returns text language sql immutable as $$
  select prefix || '-' || lower(id) || '@example.invalid'
$$;

-- Snaps a point to a ~1 km grid: keeps the market and zone, loses the doorstep.
create function pg_temp.nl_coarse(g geography) returns geography language sql immutable as $$
  select case when g is null then null else st_snaptogrid(g::geometry, 0.01)::geography end
$$;
