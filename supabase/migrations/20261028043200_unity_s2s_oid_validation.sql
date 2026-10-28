-- Unity's documented S2S example uses a 10-character OID; do not require 16 characters.
create or replace function public.grant_ad_reward(
    p_session_id uuid,
    p_user_id uuid,
    p_transaction_id text,
    p_reward_amount integer,
    p_reward_item text
)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_session public.ad_reward_sessions%rowtype;
    v_existing_user uuid;
    v_balance integer;
begin
    if p_reward_amount <> 15 or p_reward_item <> 'gold_coins' then
        raise exception 'unexpected reward';
    end if;
    if length(coalesce(p_transaction_id, '')) not between 1 and 512
       or p_transaction_id ~ '[[:cntrl:]]' then
        raise exception 'invalid transaction id';
    end if;

    select user_id into v_existing_user
      from public.coin_transactions
     where kind = 'ad_reward'
       and reference_id = p_transaction_id
     limit 1;
    if found then
        if v_existing_user <> p_user_id then
            raise exception 'ad reward transaction belongs to another user';
        end if;
        select balance into v_balance
          from public.coin_wallets
         where user_id = p_user_id;
        return v_balance;
    end if;

    select * into v_session
      from public.ad_reward_sessions
     where id = p_session_id
     for update;
    if not found
       or v_session.user_id <> p_user_id
       or v_session.status <> 'pending'
       or v_session.expires_at <= now() then
        raise exception 'reward session is invalid or expired';
    end if;

    insert into public.coin_wallets(user_id)
    values (p_user_id)
    on conflict (user_id) do nothing;
    update public.coin_wallets
       set balance = balance + 15,
           updated_at = now()
     where user_id = p_user_id;
    insert into public.coin_transactions(user_id, delta, kind, idempotency_key, reference_id)
    values (p_user_id, 15, 'ad_reward', 'unity:' || p_transaction_id, p_transaction_id);
    update public.ad_reward_sessions
       set status = 'credited',
           ad_transaction_id = p_transaction_id,
           credited_at = now()
     where id = p_session_id;

    select balance into v_balance
      from public.coin_wallets
     where user_id = p_user_id;
    return v_balance;
end;
$$;

revoke all on function public.grant_ad_reward(uuid, uuid, text, integer, text) from public, anon, authenticated;
grant execute on function public.grant_ad_reward(uuid, uuid, text, integer, text) to service_role;
