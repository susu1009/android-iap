package com.banv.iap.sample

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.banv.iap.GooglePlayIapClient
import com.banv.iap.IapClient
import com.banv.iap.IapLogger
import com.banv.iap.ProductSpec
import com.banv.iap.ProductType
import com.banv.iap.getOrNull
import com.banv.iap.onFailure
import com.banv.iap.testing.FakeIapClient
import com.banv.iap.testing.TrustAllProcessor
import kotlinx.coroutines.launch

/**
 * Demo screen. Builds with FakeIapClient by default. For real Play purchases, create the
 * products below in Play Console, upload this app to a testing track under that app id, and
 * build with: ./gradlew :sample:installDebug -PsampleAppId=<your.app.id>
 */
class MainActivity : AppCompatActivity() {

    private val products = listOf(
        ProductSpec("gems_100", ProductType.CONSUMABLE),
        ProductSpec("remove_ads", ProductType.NON_CONSUMABLE),
        ProductSpec("premium", ProductType.SUBSCRIPTION),
    )

    private lateinit var iap: IapClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iap = if (BuildConfig.USE_FAKE_IAP) {
            FakeIapClient(
                listOf(
                    FakeIapClient.inAppProduct("gems_100", "25.000 ₫"),
                    FakeIapClient.inAppProduct("remove_ads", "49.000 ₫", ProductType.NON_CONSUMABLE),
                    FakeIapClient.subscription("premium", "99.000 ₫"),
                ),
            )
        } else {
            GooglePlayIapClient.Builder(this)
                .catalog { products }
                // Demo only: a real app verifies the token on its server before granting.
                .processor(TrustAllProcessor())
                .logger { level, msg, t -> Log.println(if (level >= IapLogger.Level.WARN) Log.WARN else Log.DEBUG, "IAP", "$msg ${t ?: ""}") }
                .build()
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        val owned = TextView(this)
        val log = TextView(this)
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(TextView(this).apply { text = if (BuildConfig.USE_FAKE_IAP) "FakeIapClient" else "Google Play" })
        column.addView(owned)

        lifecycleScope.launch { iap.purchaseEvents.collect { log.append("$it\n\n") } }
        lifecycleScope.launch { iap.ownedProducts.collect { owned.text = "Owned: $it" } }
        lifecycleScope.launch {
            iap.connect().onFailure { log.append("connect: $it\n\n") }
            iap.restorePurchases()
            val query = iap.queryProducts().onFailure { log.append("query: $it\n\n") }.getOrNull()
            query?.unavailable?.forEach { log.append("unavailable: $it\n") }
            query?.products.orEmpty().forEach { product ->
                column.addView(
                    Button(this@MainActivity).apply {
                        text = "${product.title} — ${product.displayPrice}"
                        setOnClickListener {
                            lifecycleScope.launch {
                                iap.launchPurchase(this@MainActivity, product.productId)
                                    .onFailure { log.append("launch: $it\n\n") }
                            }
                        }
                    },
                )
            }
            column.addView(log)
        }
    }

    override fun onDestroy() {
        iap.close()
        super.onDestroy()
    }
}
