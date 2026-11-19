# android-iap

Google Play in-app purchases (consumables, non-consumables, subscriptions) behind a small,
Billing-version-independent Kotlin API. Built on Play Billing 9.1.0, minSdk 23, Kotlin 2.0+.

> Status: 0.1.0-SNAPSHOT — `GooglePlayIapClient` implemented and unit-tested against a fake
> gateway; not yet verified against real Play purchases (license testers).

## Usage

```kotlin
val iap: IapClient = GooglePlayIapClient.Builder(context)
    .catalog { listOf(ProductSpec("gems_100", ProductType.CONSUMABLE)) }
    .processor(object : PurchaseProcessor {
        override suspend fun verify(purchase: IapPurchase) =
            if (api.verify(purchase.productId, purchase.purchaseToken)) Verification.Valid else Verification.Invalid
        override suspend fun grant(purchase: IapPurchase) =
            wallet.creditOnce(purchase.purchaseToken, purchase.productId) // idempotent by token
    })
    .build()

// on app start / resume
iap.restorePurchases()

// in UI
iap.purchaseEvents.collect { event -> /* Completed / Pending / Cancelled / Failed */ }
iap.launchPurchase(activity, "gems_100")
```

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.<github-user>.android-iap:iap:<tag>")
    debugImplementation("com.github.<github-user>.android-iap:iap-testing:<tag>") // FakeIapClient
}
```

## Concepts

- **`ProductCatalog`**: what the app sells, each `ProductSpec` marked `CONSUMABLE`,
  `NON_CONSUMABLE` or `SUBSCRIPTION` (Play cannot tell the first two apart).
- **`PurchaseProcessor`**: implemented by the app. `verify` checks the token with your server;
  `grant` delivers the goods and **must be idempotent by `purchaseToken`**. The library consumes /
  acknowledges only after `grant` returns true, so a crash never loses a paid purchase.
- **`IapClient`**: one instance per app. `launchPurchase` only opens the sheet; outcomes arrive
  on `purchaseEvents`. Call `restorePurchases()` on app start / resume.
- **`IapError`** carries no user-facing text: map it to your own strings.

## Releasing

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Then open `https://jitpack.io/#<github-user>/android-iap` and build the tag. Local check:

```bash
./gradlew :iap:testDebugUnitTest :iap:publishToMavenLocal :iap-testing:publishToMavenLocal
```

## Updating Play Billing

Google deprecates each Billing major version about two years after release. Bump `billing` in
`gradle/libs.versions.toml`, adapt the internals, and release a minor version — the public API
exposes no Billing types, so consumer apps need no code changes.
