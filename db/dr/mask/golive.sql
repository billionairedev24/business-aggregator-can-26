-- golive (S-118): staff's free text (gate evidence, launch notes and reasons) may quote a person's address or number.
update golive.gate_records set evidence = '[masked evidence]', evidence_url = null
 where evidence ~ '@|\+?\d[\d ().-]{8,}\d' or evidence_url is not null;
update golive.launch_requests set note = null, decision_note = null;
