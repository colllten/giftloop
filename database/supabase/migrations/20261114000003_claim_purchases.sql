-- =============================================================================
-- Migration: claim_purchases
-- Why: Claims record intent ("I'll get 2"), not whether anything was bought.
--   People on a claim need to mark how many units they've purchased.
--   - Claim.purchased_quantity: 0..quantity. The same check stops the creator
--     lowering quantity below what's already bought.
--   - Any participant (creator or co-buyer) sets it via
--     private.set_claim_purchased. No column update grant: the existing
--     creator-only update policy would shut co-buyers out, and a participant
--     update policy would let co-buyers change quantity too.
--   - Readable with the claim, so Claim RLS hides it from owners until reveal.
-- =============================================================================

alter table public."Claim"
  add column purchased_quantity integer not null default 0,
  add constraint claim_purchased_within_quantity
    check (purchased_quantity between 0 and quantity);

grant select (purchased_quantity) on public."Claim" to authenticated;

-- Sets how many units of the claim have been bought. Participants only;
-- works on removed gifts, since they may have been bought before removal.
create function private.set_claim_purchased(p_claim_id uuid, p_quantity integer)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not private.is_claim_participant(p_claim_id) then
    raise exception 'Not allowed to mark this claim as purchased'
      using errcode = 'insufficient_privilege';
  end if;

  update public."Claim" set purchased_quantity = p_quantity where id = p_claim_id;
end;
$$;

revoke execute on function private.set_claim_purchased(uuid, integer) from public;
grant execute on function private.set_claim_purchased(uuid, integer) to authenticated;
