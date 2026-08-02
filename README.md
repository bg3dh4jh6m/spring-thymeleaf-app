# HOREMAG catalog

The local catalog separates HOREMAG products from supplier listings:

- `catalog_product` — the HOREMAG master product and stable `HMG-xxxxxx` code.
- `supplier_product` — a listing from METRO, Gourmet Spice, or another supplier, linked to one master product.
- `supplier_offer` — one exact purchasable package/variation with its own weight, price, and price per kilogram.
- `supplier_offer_price_history` — exact offer price history; a row is added only when the price changes.
- `catalog_price_history` — history of the parent product's minimum and maximum supplier price.

This structure also supports future non-food categories such as cups, takeaway packaging, and catering consumables.

## Local Postgres

Copy `.env.example` to the ignored local `.env`, fill in the credentials, then start the database:

```powershell
docker compose up -d catalog-db
```

Load `.env` into the current PowerShell session and start Spring with Postgres:

```powershell
Get-Content .env | Where-Object { $_ -match '^[^#].+=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "Env:$name" -Value $value
}
$env:SPRING_PROFILES_ACTIVE='postgres'
.\mvnw.cmd spring-boot:run
```

Without the `postgres` profile the app uses a local H2 file only as a fallback development mode.

Supplier catalogs are scanned every day at 07:00 and 13:00 in `Europe/Sofia`. The schedule is configurable through `catalog.refresh.cron` and `catalog.refresh.zone`.
