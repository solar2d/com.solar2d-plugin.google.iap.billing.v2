package plugin.google.iap.billing.v2;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.SkuDetails;
import com.naef.jnlua.LuaState;

public class BillingUtils {
    public static class SynchronizedWaiter {
        private boolean isSet = false;
        private int tasks = 0;
        private Runnable exec;

        public void Set(int tasks, Runnable exec) {
            if (!isSet) {
                this.tasks += tasks;
                this.exec = exec;
                isSet = true;
                checkAndRun();
            }
        }

        public synchronized void Hit() {
            tasks--;
            checkAndRun();
        }

        private void checkAndRun() {
            if (this.tasks == 0 && exec != null) {
                exec.run();
                exec = null;
            }
        }
    }


    public static int normalizeIndex(LuaState L, int tableIndex) {
        if (tableIndex < 0) {
            tableIndex = L.getTop() + tableIndex + 1;
        }
        return tableIndex;
    }

    public static void PushDetailsToLua(ProductDetails details, LuaState L, int tableIndex) {
        tableIndex = normalizeIndex(L, tableIndex);
        L.pushString(details.getTitle());
        L.setField(tableIndex, "title");
        L.pushString(details.getDescription());
        L.setField(tableIndex, "description");
        if(details.getProductType() == BillingClient.ProductType.INAPP){
            L.pushString(details.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
            L.setField(tableIndex, "priceCurrencyCode");
            L.pushString(String.valueOf(details.getOneTimePurchaseOfferDetails().getPriceAmountMicros()));
            L.setField(tableIndex, "priceAmountMicros");
            L.pushString(details.getOneTimePurchaseOfferDetails().getFormattedPrice());
            L.setField(tableIndex, "localizedPrice");
        }else if(details.getProductType() == BillingClient.ProductType.SUBS){
            L.pushString(details.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
            L.setField(tableIndex, "priceCurrencyCode");
            L.pushString(String.valueOf(details.getOneTimePurchaseOfferDetails().getPriceAmountMicros()));
            L.setField(tableIndex, "priceAmountMicros");
            L.pushString(details.getOneTimePurchaseOfferDetails().getFormattedPrice());
            L.setField(tableIndex, "localizedPrice");
        }
        L.pushString(details.getProductId());
        L.setField(tableIndex, "productIdentifier");
        L.pushString(details.getProductType());
        L.setField(tableIndex, "type");


    }
}
