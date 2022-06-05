package plugin.google.iap.billing.v2;

import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.SkuDetails;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaRuntimeTask;
import com.naef.jnlua.LuaState;

import java.util.HashSet;
import java.util.List;

public class ProductListRuntimeTask implements CoronaRuntimeTask {

    private final List<ProductDetails> fInventory;
    private final int fListener;
    private final BillingResult fResult;
    private final HashSet<String> fManagedProducts;
    private final HashSet<String> fSubscriptionProducts;

    public ProductListRuntimeTask(List<ProductDetails> inventory, HashSet<String> managedProducts, HashSet<String> subscriptionProducts, BillingResult result, int listener) {
        fInventory = inventory;
        fListener = listener;
        fResult = result;
        fManagedProducts = managedProducts;
        fSubscriptionProducts = subscriptionProducts;
    }

    @Override
    public void executeUsing(com.ansca.corona.CoronaRuntime runtime) {
        if (fListener == CoronaLua.REFNIL) {
            return;
        }
        LuaState L = runtime.getLuaState();
        try {
            CoronaLua.newEvent(L, "productList");

            if (fResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                L.pushBoolean(true);
                L.setField(-2, "isError");

                L.pushInteger(fResult.getResponseCode());
                L.setField(-2, "errorType");

                L.pushString(fResult.getDebugMessage());
                L.setField(-2, "errorString");
            } else {
                int count;
                L.newTable();
                count = 1;

                for (ProductDetails details : fInventory) {
                    L.newTable();
                    BillingUtils.PushDetailsToLua(details, L, -1);
                    L.rawSet(-2, count);
                    count++;
                    fManagedProducts.remove(details.getProductId());
                    fSubscriptionProducts.remove(details.getProductId());
                }
                L.setField(-2, "products");

                L.newTable();
                count = 1;
                for (String fManagedProduct : fManagedProducts) {
                    L.pushString(fManagedProduct);
                    L.rawSet(-2, count);
                    count++;
                }
                for (String fSubscriptionProduct : fSubscriptionProducts) {
                    L.pushString(fSubscriptionProduct);
                    L.rawSet(-2, count);
                    count++;
                }
                L.setField(-2, "invalidProducts");
            }
            CoronaLua.dispatchEvent(L, fListener, 0);
        } catch (Throwable ex) {
            Log.e("Corona", "ProductListRuntimeTask: dispatching Google IAP productList event", ex);
        }
        CoronaLua.deleteRef(L, fListener);
    }
}
