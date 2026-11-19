package com.banv.iap.sample

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.banv.iap.IapClient
import com.banv.iap.ProductType
import com.banv.iap.getOrNull
import com.banv.iap.testing.FakeIapClient
import kotlinx.coroutines.launch

/**
 * Demo screen. Uses [FakeIapClient] until the Play implementation lands; then switch to the
 * real client and run with -PsampleAppId=<an app id uploaded to Play Console>.
 */
class MainActivity : AppCompatActivity() {

    private val iap: IapClient = FakeIapClient(
        listOf(
            FakeIapClient.inAppProduct("gems_100", "25.000 ₫"),
            FakeIapClient.inAppProduct("remove_ads", "49.000 ₫", ProductType.NON_CONSUMABLE),
            FakeIapClient.subscription("premium", "99.000 ₫"),
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        val log = TextView(this)
        setContentView(root)

        lifecycleScope.launch { iap.purchaseEvents.collect { log.append("$it\n\n") } }
        lifecycleScope.launch {
            iap.connect()
            iap.queryProducts().getOrNull()?.products.orEmpty().forEach { product ->
                root.addView(
                    Button(this@MainActivity).apply {
                        text = "${product.title} — ${product.displayPrice}"
                        setOnClickListener { lifecycleScope.launch { iap.launchPurchase(this@MainActivity, product.productId) } }
                    },
                )
            }
            root.addView(log)
        }
    }

    override fun onDestroy() {
        iap.close()
        super.onDestroy()
    }
}
