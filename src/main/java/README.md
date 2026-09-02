# ComeKenobi Agent

A small, rate-limited Java bot that places recurring dollar-amount (notional) buy
orders for SPY through Alpaca's Trading API.

⚠️ **This is a personal project, not a financial product.** It executes real trades
against whatever Alpaca account you configure it with (paper or live). Nothing here
is financial advice — you're responsible for understanding what it does before you
run it against a live account.

## What it does

- Once started, checks in on a schedule and places a market buy order for SPY sized
  to a fixed dollar amount (`NOTIONAL_PER_TRADE`).
- Enforces a **rolling 5-day limit**: at most 1 trade per calendar day, at most 3
  trades in any trailing 5-day window. This is tracked in `trade_state.txt`
  (not committed to the repo — it's local runtime state).
- Only ever buys — there is currently no sell/exit logic. It's a recurring buyer,
  not a full trading strategy.
- Logs real account buying power/equity from Alpaca after each check-in, rather
  than tracking a locally simulated balance.

## Requirements

- Java 17+
- Maven
- An Alpaca account (paper and/or live) with API keys

## Setup

Create a `.env` file in the project root (this file is gitignored — never commit it):

```
ALPACA_API_KEY=your_key_id
ALPACA_SECRET_KEY=your_secret_key
# Omit ALPACA_BASE_URL entirely to default to paper trading.
# Set it explicitly to go live:
ALPACA_BASE_URL=https://api.alpaca.markets
```

## Running

```bash
mvn clean compile exec:java
```

## Configuration

Edit these constants at the top of `ComeKenobiAgent.java`:

- `SYMBOL` — the ticker to buy (default `SPY`)
- `NOTIONAL_PER_TRADE` — dollar amount per order, as a string (e.g. `"2.00"`)

## Status

Early / personal-use stage. Known gaps:

- No sell/exit logic
- No monitoring/alerting if a scheduled run fails silently
- Single-account only — not built for multi-user use

## License

TBD