package plugin.google.iap.billing.v2;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.SkuDetails;
import com.ansca.corona.CoronaEnvironment;
import com.naef.jnlua.LuaState;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

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
    /*
        ToDo: Double-check data fields. Google Billing documentation does not explain the field very well
        ToDo: Support multiple pricingPlans
    */
    public static String getOriginalJson(ProductDetails details) {

        JSONObject originalJson = new JSONObject();
        try {
            if(BillingClient.ProductType.SUBS.equals(details.getProductType()) || BillingClient.ProductType.INAPP.equals(details.getProductType())){
                originalJson.put("title", details.getTitle());
                originalJson.put("description", details.getDescription());
                originalJson.put("productId", details.getProductId());
                originalJson.put("type", details.getProductType());
                originalJson.put("packageName", CoronaEnvironment.getApplicationContext().getPackageName());
            }

            if(BillingClient.ProductType.SUBS.equals(details.getProductType()))
            {
                List<ProductDetails.SubscriptionOfferDetails> subscriptionPlans = details.getSubscriptionOfferDetails();
                ProductDetails.SubscriptionOfferDetails pricingPlan = subscriptionPlans.get(0);

                int phaseIndex = 0;
                ProductDetails.PricingPhase firstPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex);
                originalJson.put("skuDetailsToken", pricingPlan.getOfferToken());


                String trialDays = null; // null for no days?
                if(firstPricingPhase.getPriceAmountMicros() == 0) // free trial is always first
                {
                    originalJson.put("trialDays", firstPricingPhase.getBillingPeriod());
                    phaseIndex++;
                    firstPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex);
                }
                originalJson.put("introductoryPricePeriod", firstPricingPhase.getBillingPeriod());
                originalJson.put("subscriptionPeriod", firstPricingPhase.getBillingPeriod()); //Same things?
                originalJson.put("introductoryPriceCycles", firstPricingPhase.getBillingCycleCount());
                originalJson.put("introductoryPriceAmountMicros", firstPricingPhase.getPriceAmountMicros());
                originalJson.put("introductoryPrice", firstPricingPhase.getFormattedPrice());

                originalJson.put("localizedPrice", firstPricingPhase.getFormattedPrice());
                originalJson.put("price_amount_micros", firstPricingPhase.getPriceAmountMicros());
                originalJson.put("price_currency_code", firstPricingPhase.getPriceCurrencyCode());


                ProductDetails.PricingPhase secondPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex+1);
                if(secondPricingPhase != null){
                    //Second Price
                    originalJson.put("original_price", secondPricingPhase.getFormattedPrice());
                    originalJson.put("original_price_micros", secondPricingPhase.getPriceAmountMicros());

                }

            }else if(BillingClient.ProductType.INAPP.equals(details.getProductType())){
                originalJson.put("localizedPrice", details.getOneTimePurchaseOfferDetails().getFormattedPrice());
                originalJson.put("price_amount_micros", details.getOneTimePurchaseOfferDetails().getPriceAmountMicros());
                originalJson.put("price_currency_code", details.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return originalJson.toString();
    }

    public static void PushDetailsToLua(ProductDetails details, LuaState L, int tableIndex) {
        tableIndex = normalizeIndex(L, tableIndex);
        L.pushString(details.getTitle());
        L.setField(tableIndex, "title");
        L.pushString(details.getDescription());
        L.setField(tableIndex, "description");

        if(BillingClient.ProductType.INAPP.equals(details.getProductType())){
            L.pushString(details.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
            L.setField(tableIndex, "priceCurrencyCode");
            L.pushString(String.valueOf(details.getOneTimePurchaseOfferDetails().getPriceAmountMicros()));
            L.setField(tableIndex, "priceAmountMicros");
            L.pushString(details.getOneTimePurchaseOfferDetails().getFormattedPrice());
            L.setField(tableIndex, "localizedPrice");
            L.pushString(getOriginalJson(details));
            L.setField(tableIndex, "originalJson");
        }else if(BillingClient.ProductType.SUBS.equals(details.getProductType())){

            List<ProductDetails.SubscriptionOfferDetails> subscriptionPlans = details.getSubscriptionOfferDetails();
            ProductDetails.SubscriptionOfferDetails pricingPlan = subscriptionPlans.get(0);

            ProductDetails.PricingPhase pricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(0);
            if(pricingPhase.getPriceAmountMicros() == 0) // check to make sure phase is not free trial
            {pricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(1);}
            L.pushString(pricingPhase.getPriceCurrencyCode());
            L.setField(tableIndex, "priceCurrencyCode");
            L.pushString(String.valueOf(pricingPhase.getPriceAmountMicros()));
            L.setField(tableIndex, "priceAmountMicros");
            L.pushString(pricingPhase.getFormattedPrice());
            L.setField(tableIndex, "localizedPrice");

            L.pushString(getOriginalJson(details));
            L.setField(tableIndex, "originalJson");


        }
        L.pushString(details.getProductId());
        L.setField(tableIndex, "productIdentifier");
        L.pushString(details.getProductType());
        L.setField(tableIndex, "type");


    }
}
