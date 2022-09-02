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

        JSONObject oringalJson = new JSONObject();
        try {
            if(BillingClient.ProductType.SUBS.equals(details.getProductType()) || BillingClient.ProductType.INAPP.equals(details.getProductType())){
                oringalJson.put("title", details.getTitle());
                oringalJson.put("description", details.getDescription());
                oringalJson.put("productId", details.getProductId());
                oringalJson.put("type", details.getProductType());
                oringalJson.put("packageName", CoronaEnvironment.getApplicationContext().getPackageName());
            }

            if(BillingClient.ProductType.SUBS.equals(details.getProductType()))
            {
                List<ProductDetails.SubscriptionOfferDetails> subscriptionPlans = details.getSubscriptionOfferDetails();
                ProductDetails.SubscriptionOfferDetails pricingPlan = subscriptionPlans.get(0);

                int phaseIndex = 0;
                ProductDetails.PricingPhase firstPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex);
                oringalJson.put("skuDetailsToken", pricingPlan.getOfferToken());


                String trialDays = null; // null for no days?
                if(firstPricingPhase.getPriceAmountMicros() == 0) // free trial is always first
                {
                    oringalJson.put("trialDays", firstPricingPhase.getBillingPeriod());
                    phaseIndex++;
                    firstPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex);
                }
                oringalJson.put("introductoryPricePeriod", firstPricingPhase.getBillingPeriod());
                oringalJson.put("subscriptionPeriod", firstPricingPhase.getBillingPeriod()); //Same things?
                oringalJson.put("introductoryPriceCycles", firstPricingPhase.getBillingCycleCount());
                oringalJson.put("introductoryPriceAmountMicros", firstPricingPhase.getPriceAmountMicros());
                oringalJson.put("introductoryPrice", firstPricingPhase.getFormattedPrice());

                oringalJson.put("localizedPrice", firstPricingPhase.getFormattedPrice());
                oringalJson.put("price_amount_micros", firstPricingPhase.getPriceAmountMicros());
                oringalJson.put("price_currency_code", firstPricingPhase.getPriceCurrencyCode());


                ProductDetails.PricingPhase secondPricingPhase = pricingPlan.getPricingPhases().getPricingPhaseList().get(phaseIndex+1);
                if(secondPricingPhase != null){
                    //Second Price
                    oringalJson.put("original_price", secondPricingPhase.getFormattedPrice());
                    oringalJson.put("original_price_micros", secondPricingPhase.getPriceAmountMicros());

                }

            }else if(BillingClient.ProductType.INAPP.equals(details.getProductType())){
                oringalJson.put("localizedPrice", details.getOneTimePurchaseOfferDetails().getFormattedPrice());
                oringalJson.put("price_amount_micros", details.getOneTimePurchaseOfferDetails().getPriceAmountMicros());
                oringalJson.put("price_currency_code", details.getOneTimePurchaseOfferDetails().getPriceCurrencyCode());
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return oringalJson.toString();
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
