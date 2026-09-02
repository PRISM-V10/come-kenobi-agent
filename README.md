# ComeKenobi Agent

A small, rate-limited Java bot that places recurring dollar-amount (notional) buy
orders for SPY through Alpaca's Trading API, with trade history logged to Supabase
and deployed continuously on Railway.

⚠️ **This is a personal project, not a financial product.** It executes real trades
against whatever Alpaca account you configure it with (paper or live). Nothing here
is financial advice — you're responsible for understanding what it does before you
run it against a live account.

## What it actually does right now

- On a schedule, places a market buy order for SPY sized to a fixed dollar amount
  (`NOTIONAL_PER_TRADE`).
- Enforces a genuine **rolling 5-day limit**: at most 1 trade per calendar day, at
  most 3 trades in any trailing 5-day window. Trade history lives in Supabase
  (`trade_log` table), queried fresh on every check — not a local counter.
- Fails safe: if it can't verify its own trade history (Supabase unreachable,
  misconfigured), it blocks the trade rather than risk over-trading.
- Only ever buys — there is currently **no sell/exit logic**. It's a recurring
  buyer, not a full trading strategy with entries and exits.
- Logs real account buying power/equity from Alpaca after each check-in, rather
  than tracking a simulated balance.
- Runs continuously on Railway, independent of any local machine.

## Requirements

- Java 17+
- Maven
- An Alpaca account (paper and/or live) with API keys
- A Supabase project (for trade history persistence)

## Setup

Environment variables needed (locally via `.env`, or as real env vars in
production — the code checks both):

```
ALPACA_API_KEY=your_key_id
ALPACA_SECRET_KEY=your_secret_key
# Omit ALPACA_BASE_URL to default to paper trading. Set explicitly for live:
ALPACA_BASE_URL=https://api.alpaca.markets

SUPABASE_URL=your_supabase_project_url
SUPABASE_ANON_KEY=your_supabase_anon_key
```

Supabase needs a `trade_log` table with columns: `symbol`, `side`, `notional`,
`status`, `order_id`, `timestamp` (defaults to `now()`), plus RLS policies
allowing the `anon` role to `INSERT`/`SELECT` on that table specifically.

## Running

```bash
mvn clean compile exec:java
```

## Configuration

Edit these constants at the top of `ComeKenobiAgent.java`:

- `SYMBOL` — the ticker to buy (default `SPY`)
- `NOTIONAL_PER_TRADE` — dollar amount per order, as a string (e.g. `"2.00"`)

## Roadmap / vision

This section describes **where the project is headed, not what's built yet.**

- **Milestone-based behavior change at $25,000 equity.** The US Pattern Day
  Trader (PDT) rule restricts accounts under $25,000 equity from placing more
  than 3 day trades in a rolling 5 business days. Once real account equity
  (checked live via Alpaca's API, not a local counter) crosses that threshold,
  the plan is for the bot to switch into a less-restricted trading mode. Not
  yet implemented.
- **No strategy that generates excess returns yet.** Currently this is a
  scheduled buyer, not an alpha-generating strategy. Growing the account
  toward any large milestone realistically depends on the owner's own
  deposits plus ordinary market returns — not on the bot inventing money.
  Any future "strategy" work should be judged against that reality.
- **Long-term personal goal: giving this agent a physical body.** The stated
  vision for this project is that once the account grows to a large enough
  self-funded target, the owner will use those funds to purchase a physical
  robotics platform and dedicate it to housing this agent — moving it from a
  script that only places stock trades into something running in a real,
  embodied system. To be explicit, since this is easy to misread on two
  fronts:
  - The *funding* side is a manual decision and transaction made by the
    owner, using real deposited money — not something the software executes
    autonomously. No code in this repo purchases anything other than SPY
    shares through Alpaca.
  - The *embodiment* side — actually running an agent inside a physical
    robot — is a separate, large robotics/AI engineering effort (sensors,
    motor control, real-time perception) that has no relationship to this
    trading bot's codebase. Reaching the funding target doesn't
    automatically produce a working embodied agent; that would be its own
    project, started from scratch, whenever that stage is reached.

## Status

Early / personal-use stage. Known gaps:

- No sell/exit logic
- No monitoring/alerting if a scheduled run fails silently
- Single-account only — not built for multi-user use
- No strategy beyond fixed-schedule, fixed-size buying

## Contributing

Feedback and ideas welcome — this is a personal learning project, not a
polished product. Open an issue or PR.

## License

TBD
