# HOREMAG catalog

The local catalog separates HOREMAG products from supplier listings:

- `catalog_product` — the HOREMAG master product and stable `HMG-xxxxxx` code.
- `supplier_product` — a listing from METRO, Gourmet Spice, or another supplier, linked to one master product.
- `supplier_offer` — one exact purchasable package/variation with quantity, measure, package count and normalized unit price.
- `supplier_offer_price_history` — exact offer price history; a row is added only when the price changes.
- `catalog_price_history` — history of the parent product's minimum and maximum supplier price.

This structure also supports future non-food categories such as cups, takeaway packaging, and catering consumables.

The `/catalog` page lists all HOREMAG products and can add ingredients, packaging, labels and other consumables with a new stable internal code.

## Local Postgres

Copy `.env.example` to the ignored local `.env`, fill in the credentials, then start the database:

```powershell
docker compose up -d catalog-db
```

Start Spring in dev mode with Postgres and LiveReload. The script loads the ignored `.env` locally:

```powershell
.\scripts\run-dev.ps1
```

Without the `postgres` profile the app uses a local H2 file only as a fallback development mode.

Supplier catalogs are scanned every day at 07:00 and 13:00 in `Europe/Sofia`. The schedule is configurable through `catalog.refresh.cron` and `catalog.refresh.zone`.
